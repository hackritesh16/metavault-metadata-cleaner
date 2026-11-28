package com.metavault.cleaner

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.MediaCodecList
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun optionalImageEncoders(): Set<Output> {
    val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        .flatMap { it.supportedTypes.toList() }.toSet()
    return buildSet {
        if (Build.VERSION.SDK_INT >= 28 && types.any { it == "image/vnd.android.heic" || it == "video/hevc" }) add(Output.HEIC)
        if (Build.VERSION.SDK_INT >= 34 && types.any { it == "image/avif" || it == "video/av01" }) add(Output.AVIF)
    }
}

internal fun transformImage(input: File, output: File, plan: TransformPlan, active: () -> Unit): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(input.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Android cannot decode this image." }
    val exif = runCatching { ExifInterface(input) }.getOrNull()
    val angle = exif?.rotationDegrees ?: 0
    val swapped = angle == 90 || angle == 270
    val (w,h) = plan.dimensions(if (swapped) bounds.outHeight else bounds.outWidth, if (swapped) bounds.outWidth else bounds.outHeight)
    val destW = if (swapped) h else w
    val destH = if (swapped) w else h
    val memory = minOf(144L shl 20, Runtime.getRuntime().maxMemory()/3)
    val targetBytes = w.toLong()*h*4
    require(targetBytes <= memory/2) { "Use smaller dimensions on this device." }
    var sample = 1
    while ((bounds.outWidth.toLong()/sample* bounds.outHeight/sample)*4 > memory-targetBytes ||
        (bounds.outWidth/(sample*2) >= destW && bounds.outHeight/(sample*2) >= destH)) sample *= 2
    active()
    val src = BitmapFactory.decodeFile(input.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Cannot decode this image.")
    val bitmap = try {
        val result = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        if (plan.image !in listOf(Output.PNG, Output.WEBP)) canvas.drawColor(Color.WHITE)
        canvas.translate(w/2f,h/2f)
        canvas.rotate(angle.toFloat())
        if (exif?.isFlipped == true) canvas.scale(-1f,1f)
        canvas.drawBitmap(src,null,RectF(-destW/2f,-destH/2f,destW/2f,destH/2f),Paint(Paint.FILTER_BITMAP_FLAG))
        result
    } finally { src.recycle() }
    try {
        active()
        if (plan.image == Output.PDF) {
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(w,h,1).create())
                page.canvas.drawBitmap(bitmap,0f,0f,null)
                pdf.finishPage(page)
                output.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
        } else {
            val scratch = File.createTempFile("encode-", ".tmp", output.parentFile)
            try {
                searchQuality(plan.quality, if(plan.image == Output.PNG) null else plan.target, { q ->
                    active()
                    when (plan.image) {
                        Output.HEIC -> { require(Build.VERSION.SDK_INT >= 28)
                            HeifWriter.Builder(scratch.path,w,h,HeifWriter.INPUT_MODE_BITMAP).setQuality(q).build().use {
                            it.start(); it.addBitmap(bitmap); it.stop(30_000)
                        }
                        }
                        Output.AVIF -> { require(Build.VERSION.SDK_INT >= 34)
                            AvifWriter.Builder(scratch.path,w,h,AvifWriter.INPUT_MODE_BITMAP).setQuality(q).build().use {
                            it.start(); it.addBitmap(bitmap); it.stop(30_000)
                        }
                        }
                        else -> {
                            val encoder = when(plan.image) {
                                Output.PNG -> Bitmap.CompressFormat.PNG
                                Output.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                                Output.JPG,Output.JPEG -> Bitmap.CompressFormat.JPEG
                                else -> error("Unsupported image encoder")
                            }
                            scratch.outputStream().use { check(bitmap.compress(encoder,q,it)) { "Image encoding failed." } }
                        }
                    }
                    scratch.length()
                }, { Files.copy(scratch.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING) })
            } finally { scratch.delete() }
            val check = BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(output.path,check)
            require(check.outWidth==w && check.outHeight==h) { "Cannot verify encoded image on this device." }
        }
        return "$w × $h px · still image"
    } finally { bitmap.recycle() }
}
