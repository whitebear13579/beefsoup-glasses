package edu.ncku.aiglass

import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.graphics.ImageFormat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class OcrObservation(val ptsUs: Long, val text: String)

/** Decodes every H.264 reference frame but analyzes at most one image per second. */
class VideoOcrPipeline(
    private val onObservation: (OcrObservation) -> Unit,
    private val onWarning: (String) -> Unit,
) {
    private val thread = HandlerThread("meeting-video").apply { start() }
    private val handler = Handler(thread.looper)
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val images = ImageReader.newInstance(856, 480, ImageFormat.YUV_420_888, 3)
    private val queued = ArrayDeque<EncodedPacket>()
    private val busy = AtomicBoolean(false)
    private val waitingCallbacks = AtomicInteger()
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastOcrPtsUs = -1_000_000L
    private var lastText = ""
    private var closed = false
    private val info = MediaCodec.BufferInfo()
    private val outputPoll = object : Runnable {
        override fun run() {
            val active = codec ?: return
            if (closed) return
            try { drain(active) } catch (error: Exception) {
                onWarning("影像輸出失敗：${error.message}")
                return
            }
            handler.postDelayed(this, 30)
        }
    }

    init {
        images.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val ptsUs = image.timestamp / 1000
            if (busy.get() || ptsUs - lastOcrPtsUs < 1_000_000L) {
                image.close()
                return@setOnImageAvailableListener
            }
            busy.set(true)
            lastOcrPtsUs = ptsUs
            recognizer.process(InputImage.fromMediaImage(image, 0))
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    if (text.isNotBlank() && text != lastText) {
                        lastText = text
                        onObservation(OcrObservation(ptsUs, text))
                    }
                }
                .addOnFailureListener { onWarning("OCR 辨識失敗：${it.message}") }
                .addOnCompleteListener {
                    image.close()
                    busy.set(false)
                }
        }, handler)
    }

    fun offer(packet: EncodedPacket) {
        if (waitingCallbacks.incrementAndGet() > 120) {
            waitingCallbacks.decrementAndGet()
            onWarning("影像接收積壓，已略過影格；音訊仍持續錄製")
            return
        }
        if (!handler.post {
            waitingCallbacks.decrementAndGet()
            if (closed) return@post
            if (queued.size >= 120) {
                queued.clear()
                codec?.flush()
                onWarning("影像解碼積壓，已跳至下一個關鍵影格；音訊仍持續錄製")
            }
            queued.addLast(packet)
            consume()
        }) waitingCallbacks.decrementAndGet()
    }

    fun close() {
        handler.post {
            closed = true
            handler.removeCallbacks(outputPoll)
            queued.clear()
            codec?.runCatching { stop(); release() }
            codec = null
            images.close()
            recognizer.close()
            thread.quitSafely()
        }
    }

    private fun consume() {
        while (queued.isNotEmpty()) {
            val packet = queued.removeFirst()
            try {
                H264Config.find(packet.bytes).forEach { (type, bytes) ->
                    if (type == 7) sps = bytes
                    if (type == 8) pps = bytes
                }
                if (codec == null && sps != null && pps != null) configure()
                val active = codec ?: continue
                if ((packet.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) continue
                val index = active.dequeueInputBuffer(0)
                if (index < 0) {
                    queued.addFirst(packet)
                    drain(active)
                    handler.postDelayed({ consume() }, 10)
                    return
                }
                active.getInputBuffer(index)!!.apply { clear(); put(packet.bytes) }
                active.queueInputBuffer(index, 0, packet.bytes.size, packet.ptsUs, packet.flags)
                drain(active)
            } catch (error: Exception) {
                onWarning("影像解碼失敗：${error.message}")
                queued.clear()
                codec?.runCatching { stop(); release() }
                codec = null
                return
            }
        }
    }

    private fun configure() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 856, 480)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, images.surface, null, 0)
            start()
        }
        handler.post(outputPoll)
    }

    private fun drain(active: MediaCodec) {
        while (true) {
            val index = active.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            active.releaseOutputBuffer(index, true)
        }
    }
}

/** The SDK sample sends Annex B NAL units; retain start codes for MediaCodec CSD. */
object H264Config {
    fun find(data: ByteArray): List<Pair<Int, ByteArray>> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i <= data.size - 3) {
            val three = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val four = i <= data.size - 4 && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            if (three || four) {
                starts.add(i)
                i += if (four) 4 else 3
            } else i++
        }
        return starts.mapIndexedNotNull { index, start ->
            val end = starts.getOrNull(index + 1) ?: data.size
            val header = start + if (start + 3 < end && data[start + 2] == 0.toByte()) 4 else 3
            if (header >= end) null else Pair(data[header].toInt() and 31, data.copyOfRange(start, end))
        }
    }
}
