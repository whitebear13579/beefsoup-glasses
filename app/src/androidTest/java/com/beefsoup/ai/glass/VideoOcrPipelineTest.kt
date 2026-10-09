package com.beefsoup.ai.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VideoOcrPipelineTest {
    @Test fun encodedAnnexBVideoDecodesIntoPhoneOcr() {
        val bitmap = Bitmap.createBitmap(856, 480, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("MEETING", 80f, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 115f
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        val observations = mutableListOf<OcrObservation>()
        val warnings = mutableListOf<String>()
        val observed = CountDownLatch(1)
        val pipeline = VideoOcrPipeline({
            synchronized(observations) { observations.add(it) }
            observed.countDown()
        }, { synchronized(warnings) { warnings.add(it) } })
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 856, 480).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 800_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var packets = 0
        var spsCount = 0
        var ppsCount = 0
        var bytes = 0
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            repeat(3) { frame ->
                val index = encoder.dequeueInputBuffer(2_000_000)
                assertTrue("encoder input unavailable", index >= 0)
                encoder.getInputImage(index)!!.let { image ->
                    val pixels = IntArray(856 * 480)
                    bitmap.getPixels(pixels, 0, 856, 0, 0, 856, 480)
                    val y = image.planes[0]
                    for (row in 0 until 480) for (x in 0 until 856) {
                        val color = pixels[row * 856 + x]
                        val value = if (Color.red(color) < 128) 16 else 235
                        y.buffer.put(row * y.rowStride + x * y.pixelStride, value.toByte())
                    }
                    for (plane in image.planes.drop(1)) {
                        for (row in 0 until 240) for (x in 0 until 428) {
                            plane.buffer.put(row * plane.rowStride + x * plane.pixelStride, 128.toByte())
                        }
                    }
                }
                encoder.queueInputBuffer(index, 0, 856 * 480 * 3 / 2,
                    frame * 1_000_000L, if (frame == 2) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            }
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val out = encoder.dequeueOutputBuffer(info, 10_000)
                if (out < 0) continue
                val packet = BufferSlice.copy(encoder.getOutputBuffer(out)!!,
                    info.offset, info.size, info.presentationTimeUs, info.flags)
                packets++
                bytes += packet.bytes.size
                spsCount += H264Config.find(packet.bytes).count { it.first == 7 }
                ppsCount += H264Config.find(packet.bytes).count { it.first == 8 }
                pipeline.offer(packet)
                encoder.releaseOutputBuffer(out, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
            assertTrue("OCR missing; packets=$packets bytes=$bytes sps=$spsCount pps=$ppsCount warnings=$warnings",
                observed.await(20, TimeUnit.SECONDS))
            assertTrue("OCR text=$observations", observations.any { it.text.contains("MEET", true) })
        } finally {
            encoder.runCatching { stop(); release() }
            pipeline.close()
            bitmap.recycle()
        }
    }
}
