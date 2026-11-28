package com.metavault.cleaner

import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

private val localInput = listOf("-protocol_whitelist","file","-format_whitelist","mov,matroska,webm")

private data class Clip(val w: Int,val h: Int,val seconds: Double,val audio: Boolean,val fps: Double)

private fun probe(file: File): Clip {
    val args = listOf("-v","error") + localInput + listOf("-show_entries",
        "format=duration:stream=codec_type,width,height,avg_frame_rate:stream_side_data=rotation",
        "-of","json",file.path)
    val probe = FFprobeKit.executeWithArguments(args.toTypedArray())
    require(ReturnCode.isSuccess(probe.returnCode)) { "Unsupported or damaged video container." }
    val json = JSONObject(probe.output)
    val streams = json.getJSONArray("streams")
    val tracks = (0 until streams.length()).map { streams.getJSONObject(it) }
    val track = tracks.firstOrNull { it.optString("codec_type") == "video" } ?: error("No video track.")
    val w = track.getInt("width")
    val h = track.getInt("height")
    require(w > 0 && h > 0 && w.toLong()*h <= 36_000_000) { "Video dimensions exceed the decoder limit." }
    val rotation = track.optJSONArray("side_data_list")?.optJSONObject(0)?.optInt("rotation") ?: 0
    val swapped = abs(rotation)%180 == 90
    val duration = json.getJSONObject("format").optDouble("duration")
    require(duration.isFinite() && duration in 0.001..14400.0) { "Video must have a known duration of up to four hours." }
    val fraction = track.optString("avg_frame_rate").split('/')
    val fps = (fraction.firstOrNull()?.toDoubleOrNull() ?: 0.0)/(fraction.getOrNull(1)?.toDoubleOrNull() ?: 1.0)
    return Clip(if(swapped) h else w,if(swapped) w else h,duration,tracks.any { it.optString("codec_type")=="audio" },
        if(fps.isFinite() && fps>0) fps else 30.0)
}

internal fun transformVideo(input: File, output: File, plan: TransformPlan, active: () -> Unit, progress: (Float)->Unit): String {
    FFmpegKitConfig.setSessionHistorySize(4)
    val clip = probe(input)
    val (w,h) = plan.dimensions(clip.w,clip.h,true)
    val audioRate = if (clip.audio) 96_000 else 0
    val maxRate = (w*h*clip.fps.coerceIn(1.0,60.0)*(0.035+plan.quality*0.0014)).roundToLong().coerceIn(140_000,60_000_000)
    val floor = (w*h*clip.fps.coerceIn(1.0,60.0)*0.028).roundToLong().coerceIn(100_000,maxRate)
    var rate = plan.target?.let { ((it*8.0*0.97/clip.seconds)-audioRate).toLong().coerceIn(floor,maxRate) } ?: maxRate
    val webm = plan.video == Output.WEBM
    val candidate = File.createTempFile("video-", "."+plan.video.ext, output.parentFile)
    try {
        for (pass in 0..2) {
            active()
            val args = listOf("-hide_banner","-loglevel","error","-nostdin","-y","-filter_threads","1","-threads","2") + localInput +
                listOf("-i",input.path,"-map","0:v:0","-map","0:a:0?","-map_metadata","-1","-map_chapters","-1","-sn","-dn",
                    "-vf","scale=$w:$h,setsar=1","-pix_fmt","yuv420p","-c:v",if(webm) "libvpx" else "mpeg4",
                    "-threads","2","-b:v",rate.toString(),"-c:a",if(webm) "libvorbis" else "aac","-b:a","96k") +
                (if(webm) listOf("-deadline","realtime","-cpu-used","6") else emptyList()) +
                listOf("-f",when(plan.video) { Output.MKV -> "matroska"; Output.WEBM -> "webm"; else -> "mp4" },candidate.path)
            val done = CountDownLatch(1)
            val session = FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(), { done.countDown() })
            try {
                val estimate = (rate+audioRate)*clip.seconds/8.0
                while (!done.await(250,TimeUnit.MILLISECONDS)) {
                    active()
                    check(candidate.length() <= TRANSFORM_LIMIT && candidate.usableSpace > 24L.shl(20)) { "Not enough space to complete this video." }
                    progress((candidate.length()/estimate).toFloat().coerceIn(0f,0.99f))
                }
            } finally {
                if (done.count != 0L) {
                    FFmpegKit.cancel(session.sessionId)
                    // A cancel issued before native startup may be missed. Wait for the writer to release the file.
                    while (!done.await(200,TimeUnit.MILLISECONDS)) FFmpegKit.cancel(session.sessionId)
                }
            }
            active()
            require(ReturnCode.isSuccess(session.returnCode)) { "Video encoding failed for this file or codec." }
            require(candidate.length() in 1..TRANSFORM_LIMIT)
            val check = probe(candidate)
            require(check.w == w && check.h == h && check.audio == clip.audio &&
                abs(check.seconds-clip.seconds) < maxOf(1.0,clip.seconds*0.01)) { "Output video verification failed." }
            val size = candidate.length()
            val target = plan.target
            if (!output.exists() || output.length()==0L || target==null ||
                (output.length()>target && size<output.length()) || (size<=target && size>output.length())) {
                candidate.copyTo(output,overwrite=true)
            }
            if (target == null || size in (target*9/10)..target) break
            val next = (rate * (target.toDouble()/size) * 0.96).roundToLong().coerceIn(floor,maxRate)
            if (next==rate) break
            rate=next
        }
        return "$w × $h · first video and audio tracks"
    } finally { candidate.delete() }
}
