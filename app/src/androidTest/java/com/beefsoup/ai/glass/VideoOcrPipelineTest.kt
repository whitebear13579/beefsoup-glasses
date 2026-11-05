package com.beefsoup.ai.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VideoOcrPipelineTest {
    @Test fun detailedFullHdFrameIsBoundedWithoutChangingResolution() {
        val width = 1080
        val height = 1920
        val nv21 = ByteArray(width * height * 3 / 2).also { java.util.Random(42).nextBytes(it) }
        val jpeg = encodeOcrFrame(nv21, width, height)
        assertTrue(jpeg.size <= MAX_HIGHLIGHT_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        assertEquals(width, bounds.outWidth)
        assertEquals(height, bounds.outHeight)
    }

    @Test fun encodedAnnexBVideoDecodesIntoPhoneOcr() {
        assertEncodedVideoOcr(856, 480)
    }

    @Test fun portraitVideoWithPaddedDecoderHeightDecodesIntoPhoneOcr() {
        assertEncodedVideoOcr(480, 856)
    }

    @Test fun manualCaptureUsesLocalOcrWithoutAutomaticSelection() {
        assertEncodedVideoOcr(856, 480, manualCapture = true)
    }

    @Test fun fullHdManualCaptureUsesLocalOcr() {
        val quality = com.htc.viveglass.sdk.VideoStreamingFormat.VideoQuality.FULL_HD
        assertEncodedVideoOcr(quality.width, quality.height, frameStepUs = 200_000L, manualCapture = true)
    }

    @Test fun previewDecodesWithoutOcrOrInference() {
        assertEncodedVideoOcr(856, 480, previewOnly = true)
    }

    @Test fun previewCanDeliverFifteenFpsInsteadOfFive() {
        assertEncodedVideoOcr(856, 480, previewOnly = true, previewFps = 15, frameStepUs = 66_667L, expectedFrames = 3)
    }

    @Test fun previewCanDeliverThirtyFps() {
        assertEncodedVideoOcr(856, 480, previewOnly = true, previewFps = 30, frameStepUs = 33_334L, expectedFrames = 3)
    }

    @Test fun fullHdPreviewUsesSelectedDecoderDimensions() {
        val quality = com.htc.viveglass.sdk.VideoStreamingFormat.VideoQuality.FULL_HD
        assertEncodedVideoOcr(quality.width, quality.height, previewOnly = true)
    }

    private fun assertEncodedVideoOcr(width: Int, height: Int, previewOnly: Boolean = false,
        previewFps: Int = 5, frameStepUs: Long = 1_000_000L, expectedFrames: Int = 1,
        manualCapture: Boolean = false) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText("MEETING", width * 0.09f, height / 2f + 20, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = minOf(width * 0.134f, 120f)
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        val observations = mutableListOf<OcrObservation>()
        val warnings = mutableListOf<String>()
        val observed = CountDownLatch(expectedFrames)
        val captured = CountDownLatch(1)
        val candidate = java.util.concurrent.atomic.AtomicReference<VisualFrame?>()
        val preview = java.util.concurrent.atomic.AtomicReference<Bitmap?>()
        val pipeline = VideoOcrPipeline(InstrumentationRegistry.getInstrumentation().targetContext, {
            synchronized(observations) { observations.add(it) }
            observed.countDown()
        }, { synchronized(warnings) { warnings.add(it) } }, onCandidate = { frame ->
            if (frame.manual) { candidate.set(frame); captured.countDown() }
            true
        }, automaticSelection = !manualCapture, analyzeText = !previewOnly,
            onPreview = if (previewOnly) { frame, _ -> preview.set(frame); observed.countDown() } else null,
            previewFps = previewFps, initialWidth = width, initialHeight = height)
        if (manualCapture) pipeline.requestCapture()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
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
                    val pixels = IntArray(width * height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    val y = image.planes[0]
                    for (row in 0 until height) for (x in 0 until width) {
                        val color = pixels[row * width + x]
                        val value = if (Color.red(color) < 128) 16 else 235
                        y.buffer.put(row * y.rowStride + x * y.pixelStride, value.toByte())
                    }
                    for (plane in image.planes.drop(1)) {
                        for (row in 0 until height / 2) for (x in 0 until width / 2) {
                            plane.buffer.put(row * plane.rowStride + x * plane.pixelStride, 128.toByte())
                        }
                    }
                }
                encoder.queueInputBuffer(index, 0, width * height * 3 / 2,
                    frame * frameStepUs, if (frame == 2) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
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
            if (previewOnly) {
                assertEquals(width, preview.get()!!.width)
                assertEquals(height, preview.get()!!.height)
                assertTrue(observations.isEmpty())
            } else assertTrue("OCR text=$observations", observations.any { it.text.contains("MEET", true) })
            if (manualCapture) {
                assertTrue("Manual frame not delivered", captured.await(20, TimeUnit.SECONDS))
                assertEquals(observations.first().ptsUs, candidate.get()!!.ptsUs)
                assertTrue(candidate.get()!!.text.contains("MEET", true))
                assertTrue(candidate.get()!!.jpeg.isNotEmpty())
            }
            assertTrue("Decoder/OCR warnings=$warnings", synchronized(warnings) { warnings.isEmpty() })
        } finally {
            encoder.runCatching { stop(); release() }
            pipeline.close()
            bitmap.recycle()
        }
    }
}
