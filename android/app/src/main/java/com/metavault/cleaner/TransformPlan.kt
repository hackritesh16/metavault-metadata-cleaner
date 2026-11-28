package com.metavault.cleaner

import kotlin.math.roundToInt
import java.util.Locale

internal enum class FileFamily { IMAGE, VIDEO, PDF, OFFICE, OTHER }
internal enum class Output(val ext: String, val mime: String) {
    JPG("jpg","image/jpeg"), JPEG("jpeg","image/jpeg"), PNG("png","image/png"), WEBP("webp","image/webp"),
    AVIF("avif","image/avif"), HEIC("heic","image/heic"), MP4("mp4","video/mp4"), WEBM("webm","video/webm"),
    MKV("mkv","video/x-matroska"), PDF("pdf","application/pdf"), TXT("txt","text/plain"), ORIGINAL("","")
}
internal enum class Resize(val label: String) { PERCENT("Percentage"), PIXELS("Dimensions"), RESOLUTION("Resolution") }
internal data class TransformPlan(
    val resize: Resize = Resize.PERCENT, val percent: Int = 100, val width: Int = 1280, val height: Int = 720,
    val lockRatio: Boolean = true, val resolution: Int = 720, val quality: Int = 82, val target: Long? = null,
    val image: Output = Output.JPG, val video: Output = Output.MP4, val pdf: Output = Output.PDF,
    val metadataOnly: Boolean = false
) {
    fun format(family: FileFamily) = if (metadataOnly) Output.ORIGINAL else when (family) {
        FileFamily.IMAGE -> image
        FileFamily.VIDEO -> video
        FileFamily.PDF -> pdf
        else -> Output.ORIGINAL
    }
    fun dimensions(w: Int, h: Int, video: Boolean = false): Pair<Int, Int> {
        require(w > 0 && h > 0)
        val scale = when (resize) {
            Resize.PERCENT -> percent.also { require(it in 1..400) } / 100.0
            Resize.PIXELS -> width.also { require(it in 1..8192) }.toDouble() / w
            Resize.RESOLUTION -> resolution.toDouble() / minOf(w, h)
        }
        fun scaled(n: Int) = (n * scale).roundToInt().coerceAtLeast(1)
        var x = scaled(w)
        var y = if (resize == Resize.PIXELS && !lockRatio) height else scaled(h)
        require(x in 1..8192 && y in 1..8192 && x.toLong()*y <= (if (video) 8_847_360 else 16_000_000)) {
            "Requested dimensions exceed the processing limit. Choose a smaller size."
        }
        if (video) { x = (x / 2 * 2).coerceAtLeast(2); y = (y / 2 * 2).coerceAtLeast(2) }
        return x to y
    }
}
internal fun family(name: String, mime: String): FileFamily {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        ext in listOf("jpg","jpeg","png","webp","avif","heic","heif") || mime in listOf("image/jpeg","image/png","image/webp","image/avif","image/heic","image/heif") -> FileFamily.IMAGE
        ext in listOf("mp4","mov","m4v","mkv","webm") || mime in listOf("video/mp4","video/webm","video/x-matroska","video/quicktime") -> FileFamily.VIDEO
        ext == "pdf" || mime == "application/pdf" -> FileFamily.PDF
        ext in listOf("docx","xlsx","pptx") -> FileFamily.OFFICE
        else -> FileFamily.OTHER
    }
}

internal fun sizeText(n: Long): String = if (n < 1024) "$n B" else if (n < 1024*1024)
    String.format(Locale.US,"%.1f KB",n/1024.0) else String.format(Locale.US,"%.2f MB",n/1048576.0)
internal fun comparison(before: Long, after: Long): String {
    if (before <= 0) return sizeText(after)
    val percent = (before-after)*100.0/before
    return "${sizeText(before)} → ${sizeText(after)} · " + String.format(Locale.US,"%.1f%% %s",kotlin.math.abs(percent),if (percent >= 0) "smaller" else "larger")
}

/** Each trial writes to the same scratch file; the caller retains the winner separately. */
internal fun searchQuality(quality: Int, target: Long?, trial: (Int) -> Long, keep: () -> Unit) {
    require(quality in 35..100 && (target == null || target > 0))
    var best = Long.MAX_VALUE
    fun attempt(q: Int): Long {
        val bytes = trial(q)
        require(bytes > 0)
        if (target == null || best == Long.MAX_VALUE || (best > target && bytes < best) ||
            (bytes <= target && best <= target && bytes > best)) {
            keep()
            best = bytes
        }
        return bytes
    }
    if (target == null || attempt(quality) <= target) {
        if (target == null) attempt(quality)
        return
    }
    if (quality == 35 || attempt(35) >= target) return
    var low = 36
    var high = quality - 1
    while (low <= high) {
        val q = (low+high)/2
        if (attempt(q) <= target) low=q+1 else high=q-1
    }
}
