package edu.ncku.aiglass

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** AAC persistence and decode happen on one worker; an overflowing input is treated as lost audio. */
class AudioPipeline(
    directory: File,
    onPcmChunk: (PcmChunk) -> Unit,
    private val onError: (String) -> Unit,
    onCommitted: (File) -> Unit,
) {
    private val worker = HandlerThread("meeting-aac").apply { start() }
    private val handler = Handler(worker.looper)
    private val queued = AtomicInteger()
    private val closed = AtomicBoolean()
    private val archive = AudioArchive(directory, onCommitted)
    private val chunker = PcmChunker(onPcmChunk)
    private val outputInfo = MediaCodec.BufferInfo()
    private val ptsGuard = AacPtsGuard()
    private var decoder: MediaCodec? = null
    private var config: AacConfig? = null
    private var converter: SpeechPcmConverter? = null
    private var lastInputPtsUs = 0L
    private var lastOutputPtsUs = 0L
    private var sawEnd = false

    fun offer(packet: EncodedPacket): Boolean {
        if (closed.get()) return false
        if (queued.incrementAndGet() > 80) {
            queued.decrementAndGet()
            return false
        }
        val posted = handler.post {
            queued.decrementAndGet()
            try { process(packet) } catch (error: Exception) {
                onError("音訊寫入或解碼失敗：${error.message}")
            }
        }
        if (!posted) queued.decrementAndGet()
        return posted
    }

    fun finish(onFinished: () -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            try {
                endDecoder()
                converter?.finish()?.takeIf { it.isNotEmpty() }?.let { chunker.append(it, lastOutputPtsUs) }
                chunker.finish()
                archive.close()
            } catch (error: Exception) {
                onError("音訊收尾失敗：${error.message}")
            } finally {
                decoder?.runCatching { stop(); release() }
                decoder = null
                onFinished()
                worker.quitSafely()
            }
        }
    }

    private fun process(packet: EncodedPacket) {
        if ((packet.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            archive.close()
            config = AacHeader.fromAsc(packet.bytes)
            configure(config!!)
            return
        }
        if (packet.bytes.isEmpty()) return
        if (config == null && AacHeader.isAdts(packet.bytes)) {
            config = AacHeader.fromAdts(packet.bytes)
            configure(config!!)
        }
        val format = config ?: error("AAC configuration not received")
        ptsGuard.accept(packet.ptsUs, format.sampleRate)
        archive.write(format, packet)
        val data = AacHeader.rawAccessUnit(packet.bytes)
        val codec = decoder ?: error("AAC decoder unavailable")
        val inputIndex = codec.dequeueInputBuffer(50_000)
        check(inputIndex >= 0) { "AAC decoder input stalled" }
        val input = codec.getInputBuffer(inputIndex) ?: error("AAC input buffer unavailable")
        input.clear()
        input.put(data)
        codec.queueInputBuffer(inputIndex, 0, data.size, packet.ptsUs, 0)
        lastInputPtsUs = packet.ptsUs
        drain()
    }

    private fun endDecoder() {
        val codec = decoder ?: return
        val input = codec.dequeueInputBuffer(500_000)
        check(input >= 0) { "AAC decoder cannot flush final samples" }
        codec.queueInputBuffer(input, 0, 0, lastInputPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        val deadline = android.os.SystemClock.uptimeMillis() + 2000
        while (!sawEnd && android.os.SystemClock.uptimeMillis() < deadline) {
            drain()
            if (!sawEnd) android.os.SystemClock.sleep(10)
        }
        check(sawEnd) { "AAC decoder final output timed out" }
    }

    private fun configure(config: AacConfig) {
        decoder?.runCatching { stop(); release() }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channels
        )
        format.setByteBuffer("csd-0", ByteBuffer.wrap(config.asc))
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
        converter = SpeechPcmConverter(config.sampleRate, config.channels)
        sawEnd = false
    }

    private fun drain() {
        val codec = decoder ?: return
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(outputInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    converter = SpeechPcmConverter(
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    )
                }
                else -> if (outputIndex >= 0) {
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && outputInfo.size > 0) {
                        val pcm = BufferSlice.copy(
                            output, outputInfo.offset, outputInfo.size,
                            outputInfo.presentationTimeUs, outputInfo.flags
                        ).bytes
                        val normalized = converter?.convert(pcm) ?: ByteArray(0)
                        if (normalized.isNotEmpty()) chunker.append(normalized, outputInfo.presentationTimeUs)
                        lastOutputPtsUs = outputInfo.presentationTimeUs
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEnd = true
                        return
                    }
                } else return
            }
        }
    }
}
