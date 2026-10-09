package com.beefsoup.ai.glass

import android.app.Activity
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.htc.viveglass.sdk.StreamingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Explicit synthetic input for emulator smoke tests; it is never labeled as eye-glass capture. */
class DemoGlassLink : GlassLink {
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    override fun connect() { _connected.value = true }
    override fun disconnect() { stop(StreamMode.AUDIO); _connected.value = false }
    override suspend fun ensurePermissions(activity: Activity, mode: StreamMode) = true

    override fun start(mode: StreamMode, epoch: Long, events: GlassEvents): Boolean {
        if (!_connected.value || job != null || mode != StreamMode.AUDIO) return false
        job = scope.launch {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                events.onStreamEvent(epoch, StreamingEvent.STARTED)
                val info = MediaCodec.BufferInfo()
                var frame = 0L
                while (isActive) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = encoder.getInputBuffer(index)!!
                        input.clear()
                        repeat(1024) { sample ->
                            val value = (sin(2 * PI * 440 * (frame + sample) / 16000) * 1200).toInt()
                            input.put((value and 255).toByte())
                            input.put((value ushr 8).toByte())
                        }
                        encoder.queueInputBuffer(index, 0, 2048, frame * 1_000_000 / 16000, 0)
                        frame += 1024
                    }
                    while (true) {
                        val out = encoder.dequeueOutputBuffer(info, 0)
                        if (out < 0) break
                        val packet = BufferSlice.copy(encoder.getOutputBuffer(out)!!,
                            info.offset, info.size, info.presentationTimeUs, info.flags)
                        events.onAudio(epoch, packet)
                        encoder.releaseOutputBuffer(out, false)
                    }
                    delay(64)
                }
            } finally {
                encoder.runCatching { stop(); release() }
                events.onStreamEvent(epoch, StreamingEvent.STOPPED)
            }
        }
        return true
    }

    override fun stop(mode: StreamMode) { job?.cancel(); job = null }
}
