package com.metavault.cleaner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransformDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var dir: File

    @Before
    fun start() {
        dir =
            File(context.cacheDir, "transform-test").apply {
                deleteRecursively()
                mkdirs()
            }
    }

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun process(source: File, plan: TransformPlan): ToolResult {
        val uri = Uri.fromFile(source)
        return prepareTransform(
            context,
            ToolSource(uri, source.name, "", source.length()),
            plan,
            dir,
            {},
            {},
        )
    }

    @Test
    fun resizeEncodeAndBoundTargetWithoutChangingInput() {
        val source = File(dir, "image.png")
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val colors = IntArray(320 * 180) { (it * 1327217885) or (255 shl 24) }
        bitmap.setPixels(colors, 0, 320, 0, 0, 320, 180)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val original = source.readBytes()
        for (format in listOf(Output.JPG, Output.PNG, Output.WEBP)) {
            val result = process(source, TransformPlan(percent = 50, image = format, target = 5000))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(result.file.path, bounds)
            Assert.assertEquals(format.mime, bounds.outMimeType)
            Assert.assertEquals(160, bounds.outWidth)
            Assert.assertEquals(90, bounds.outHeight)
            if (format == Output.JPG) Assert.assertTrue(result.file.length() <= 5000)
        }
        val pdf = process(source, TransformPlan(image = Output.PDF))
        PDDocument.load(pdf.file).use { Assert.assertEquals(1, it.numberOfPages) }
        Assert.assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun transcodeThreeRealVideoContainersWithAudio() {
        val clip = File(dir, "clip.mp4")
        val session =
            FFmpegKit.executeWithArguments(
                arrayOf(
                    "-v",
                    "error",
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=blue:s=160x120:r=12",
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=440:sample_rate=44100",
                    "-t",
                    "1",
                    "-c:v",
                    "mpeg4",
                    "-c:a",
                    "aac",
                    clip.path,
                )
            )
        Assert.assertTrue(ReturnCode.isSuccess(session.returnCode))
        for (format in listOf(Output.MP4, Output.WEBM, Output.MKV)) {
            val result =
                process(clip, TransformPlan(percent = 50, video = format, target = 24 * 1024))
            val head = result.file.inputStream().use { it.readNBytesCompat(64) }
            when (format) {
                Output.MP4 -> Assert.assertEquals("ftyp", String(head, 4, 4))
                Output.WEBM -> Assert.assertTrue(String(head, Charsets.ISO_8859_1).contains("webm"))
                else -> Assert.assertTrue(String(head, Charsets.ISO_8859_1).contains("matroska"))
            }
            Assert.assertTrue(result.detail.contains("80 × 60"))
        }
    }

    @Test
    fun pdfOptimizationRetainsSearchableTextAndTextExport() {
        PDFBoxResourceLoader.init(context)
        val source = File(dir, "doc.pdf")
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            PDPageContentStream(pdf, page).use {
                it.beginText()
                it.setFont(PDType1Font.HELVETICA, 12f)
                it.newLineAtOffset(25f, 300f)
                it.showText("MetaVault offline")
                it.endText()
            }
            pdf.save(source)
        }
        val result = process(source, TransformPlan())
        Assert.assertTrue(result.file.length() <= source.length())
        val saved = File(dir, "optimized.pdf")
        result.file.copyTo(saved)
        Assert.assertTrue(
            process(saved, TransformPlan(pdf = Output.TXT))
                .file
                .readText()
                .contains("MetaVault offline")
        )
    }

    @Test
    fun recompressEmbeddedPdfPhotosAndKeepDimensions() {
        PDFBoxResourceLoader.init(context)
        val source = File(dir, "photos.pdf")
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bmp.setPixels(IntArray(65536) { (it * 927277) or (255 shl 24) }, 0, 256, 0, 0, 256, 256)
        PDDocument().use { pdf ->
            val page = PDPage()
            pdf.addPage(page)
            val photo = JPEGFactory.createFromImage(pdf, bmp, 1f)
            PDPageContentStream(pdf, page).use { it.drawImage(photo, 0f, 0f) }
            pdf.save(source)
        }
        bmp.recycle()
        val output =
            process(source, TransformPlan(compressPdfPhotos = true, quality = 50, target = 8000))
        Assert.assertTrue(output.file.length() < source.length())
        Assert.assertTrue(output.detail, output.detail.contains("embedded photos recompressed"))
        PDDocument.load(output.file).use { pdf ->
            val resources = pdf.getPage(0).resources
            val image =
                resources.getXObject(resources.xObjectNames.first())
                    as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
            Assert.assertEquals(256, image.image.width)
        }
    }

    @Test
    fun cancellationRemovesPartialResultsAndOriginalStaysReadable() {
        val source = File(dir, "cancel.png").apply { writeBytes(ByteArray(160_000) { 42 }) }
        val before = source.readBytes()
        var calls = 0
        try {
            prepareTransform(
                context,
                ToolSource(Uri.fromFile(source), source.name, "image/png", source.length()),
                TransformPlan(),
                dir,
                { if (++calls >= 2) throw kotlinx.coroutines.CancellationException("cancel") },
                {},
            )
            Assert.fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {}
        Assert.assertArrayEquals(before, source.readBytes())
        Assert.assertEquals(listOf(source.name), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun batchReviewsSuccessfulItemsAndBacksUpBeforeOverwrite() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val session = FileToolsSession.get(context)
        val bmp = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        val sources =
            listOf("batch-one.png", "batch-two.png").map { name ->
                File(dir, name).also { file ->
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        bmp.recycle()
        val originals = sources.map { it.readBytes() }
        val unsupported = File(dir, "unknown.xyz").apply { writeText("unsupported") }
        fun awaitIdle() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 30000
            while (true) {
                var busy = true
                instrumentation.runOnMainSync { busy = session.busy }
                if (!busy) return
                check(android.os.SystemClock.elapsedRealtime() < deadline) { session.message }
                Thread.sleep(80)
            }
        }
        try {
            instrumentation.runOnMainSync {
                session.select((sources + unsupported).map(Uri::fromFile))
            }
            awaitIdle()
            instrumentation.runOnMainSync {
                session.prepare(TransformPlan(percent = 50, image = Output.PNG))
            }
            awaitIdle()
            Assert.assertEquals(session.message, 2, session.results.size)
            Assert.assertTrue(session.errors.containsKey(Uri.fromFile(unsupported)))
            sources.forEachIndexed { i, file ->
                Assert.assertArrayEquals(originals[i], file.readBytes())
            }
            instrumentation.runOnMainSync { session.save(null) }
            awaitIdle()
            Assert.assertEquals(session.errors.toString(), 2, session.saved.size)
            sources.forEachIndexed { i, file ->
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, info)
                Assert.assertEquals(48, info.outWidth)
                val backup =
                    File(context.filesDir, "backups").listFiles()!!.single {
                        it.name.endsWith("__" + file.name + ".bak")
                    }
                Assert.assertArrayEquals(originals[i], backup.readBytes())
                File(backup.path + ".uri").delete()
                backup.delete()
            }
        } finally {
            instrumentation.runOnMainSync { session.close() }
        }
    }

    private fun java.io.InputStream.readNBytesCompat(length: Int) =
        ByteArray(length).also { java.io.DataInputStream(this).readFully(it) }
}
