package com.beefsoup.ai.glass

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class OcrObservation(val ptsUs: Long, val text: String)

/** Decodes every H.264 reference frame but analyzes at most one image per second. */
class VideoOcrPipeline(
    private val onObservation: (OcrObservation) -> Unit,
    private val onWarning: (String) -> Unit,
    private val onCandidate: (VisualFrame) -> Boolean = { true },
    private val automaticSelection: Boolean = true,
    private val analyzeText: Boolean = true,
    private val onPreview: ((Bitmap, Long) -> Unit)? = null,
    private val previewFps: Int = 5,
    private val initialWidth: Int = 856,
    private val initialHeight: Int = 480,
) {
    init { require(previewFps in 1..30 && initialWidth > 0 && initialHeight > 0) }
    private val thread = HandlerThread("meeting-video").apply { start() }
    private val handler = Handler(thread.looper)
    private val recognizer = if (analyzeText) TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) else null
    private val queued = ArrayDeque<EncodedPacket>()
    private val busy = AtomicBoolean(false)
    private val waitingCallbacks = AtomicInteger()
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastOcrPtsUs = -1_000_000L
    private var lastText = ""
    private var nextPreviewPtsUs = Long.MIN_VALUE
    private val candidateGate = VisualCandidateGate()
    private val captureRequested = AtomicBoolean(false)
    @Volatile private var closed = false
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

    fun offer(packet: EncodedPacket) {
        if (closed) return
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
        closed = true
        handler.post {
            handler.removeCallbacks(outputPoll)
            queued.clear()
            codec?.runCatching { stop(); release() }
            codec = null
            recognizer?.close()
            thread.quitSafely()
        }
    }

    fun requestCapture() { captureRequested.set(true) }
    fun retryCandidate() { handler.post { if (!closed) candidateGate.retry() } }

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
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, initialWidth, initialHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            // CPU-readable output avoids the S23 ImageReader nativeCreatePlanes abort.
            configure(format, null, null, 0)
            start()
        }
        handler.removeCallbacks(outputPoll)
        handler.post(outputPoll)
    }

    private fun drain(active: MediaCodec) {
        while (true) {
            val index = active.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            try {
                val ptsUs = info.presentationTimeUs
                val previewDue = onPreview != null && ptsUs >= nextPreviewPtsUs
                val ocrDue = analyzeText && !busy.get() && ptsUs - lastOcrPtsUs >= 1_000_000L
                if (info.size > 0 && (previewDue || ocrDue)) {
                    // Copy before releasing the codec buffer; consumers never retain decoder images.
                    val packed = checkNotNull(active.getOutputImage(index)) {
                        "解碼器未提供可讀取的 YUV 影像"
                    }.use { copyForOcr(it) }
                    if (previewDue) {
                        val intervalUs = 1_000_000L / previewFps
                        nextPreviewPtsUs = if (nextPreviewPtsUs == Long.MIN_VALUE) ptsUs + intervalUs
                            else nextPreviewPtsUs + ((ptsUs - nextPreviewPtsUs) / intervalUs + 1) * intervalUs
                        val jpeg = ByteArrayOutputStream()
                        check(YuvImage(packed.bytes, ImageFormat.NV21, packed.width, packed.height, null)
                            .compressToJpeg(Rect(0, 0, packed.width, packed.height), 90, jpeg))
                        val bytes = jpeg.toByteArray()
                        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        if (!closed) onPreview?.invoke(bitmap, ptsUs)
                    }
                    if (!ocrDue) continue
                    lastOcrPtsUs = ptsUs
                    busy.set(true)
                    try {
                        recognizer!!.process(InputImage.fromByteArray(packed.bytes, packed.width, packed.height,
                            0, InputImage.IMAGE_FORMAT_NV21))
                            .addOnSuccessListener { result ->
                                val text = result.text.trim()
                                handler.post {
                                    if (closed) return@post
                                    if (text.isNotBlank() && text != lastText) {
                                        lastText = text
                                        onObservation(OcrObservation(ptsUs, text))
                                    }
                                    val manual = captureRequested.getAndSet(false)
                                    val luma = ByteArray(256) { i ->
                                        val x = ((i % 16 + 0.5) * packed.width / 16).toInt().coerceAtMost(packed.width - 1)
                                        val y = ((i / 16 + 0.5) * packed.height / 16).toInt().coerceAtMost(packed.height - 1)
                                        packed.bytes[y * packed.width + x]
                                    }
                                    if (manual || automaticSelection && candidateGate.offer(ptsUs, text, luma)) {
                                        val jpeg = ByteArrayOutputStream()
                                        if (YuvImage(packed.bytes, ImageFormat.NV21, packed.width, packed.height, null)
                                                .compressToJpeg(Rect(0, 0, packed.width, packed.height), 85, jpeg)) {
                                            if (!onCandidate(VisualFrame(ptsUs, text, jpeg.toByteArray(), manual)) && !manual)
                                                candidateGate.retry()
                                        }
                                    }
                                }
                            }
                            .addOnFailureListener {
                                if (!closed) onWarning("OCR 辨識失敗：${it.message}")
                            }
                            .addOnCompleteListener { busy.set(false) }
                    } catch (error: Exception) {
                        busy.set(false)
                        throw error
                    }
                }
            } finally {
                active.releaseOutputBuffer(index, false)
            }
        }
    }

    private data class PackedFrame(val bytes: ByteArray, val width: Int, val height: Int)

    private fun copyForOcr(image: Image): PackedFrame {
        require(image.format == ImageFormat.YUV_420_888)
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        require(width > 0 && height > 0 &&
            (width or height or crop.left or crop.top) and 1 == 0) { "不支援奇數 YUV 裁切邊界" }
        val nv21 = ByteArray(width * height * 3 / 2)
        image.planes.forEachIndexed { planeIndex, plane ->
            val divisor = if (planeIndex == 0) 1 else 2
            val step = if (planeIndex == 0) 1 else 2
            val offset = when (planeIndex) { 0 -> 0; 1 -> width * height + 1; else -> width * height }
            val source = plane.buffer.duplicate()
            val origin = source.position() + crop.top / divisor * plane.rowStride +
                crop.left / divisor * plane.pixelStride
            for (row in 0 until height / divisor) for (x in 0 until width / divisor) {
                nv21[offset + (row * (width / divisor) + x) * step] =
                    source.get(origin + row * plane.rowStride + x * plane.pixelStride)
            }
        }
        return PackedFrame(nv21, width, height)
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
