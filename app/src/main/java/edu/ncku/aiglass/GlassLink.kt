package edu.ncku.aiglass

import android.app.Activity
import android.content.Context
import android.media.MediaCodec
import com.htc.viveglass.sdk.AudioChannel
import com.htc.viveglass.sdk.AudioStreamingFormat
import com.htc.viveglass.sdk.CaptureEvent
import com.htc.viveglass.sdk.ConnectionState
import com.htc.viveglass.sdk.ImagePayload
import com.htc.viveglass.sdk.KeyEvent
import com.htc.viveglass.sdk.Microphone
import com.htc.viveglass.sdk.Permission
import com.htc.viveglass.sdk.PermissionResult
import com.htc.viveglass.sdk.StreamingEvent
import com.htc.viveglass.sdk.SynthesisEvent
import com.htc.viveglass.sdk.TranscribedEvent
import com.htc.viveglass.sdk.VideoStreamingFormat
import com.htc.viveglass.sdk.ViveGlass
import com.htc.viveglass.sdk.ViveGlassKit
import com.htc.viveglass.sdk.client.StreamingBufferCallback
import com.htc.viveglass.sdk.client.StreamingEventCallback
import com.htc.viveglass.sdk.client.ViveGlassClientCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

interface GlassEvents {
    fun onAudio(epoch: Long, packet: EncodedPacket)
    fun onVideo(epoch: Long, packet: EncodedPacket)
    fun onStreamEvent(epoch: Long, event: StreamingEvent)
}

interface GlassLink {
    val connected: StateFlow<Boolean>
    fun connect()
    fun disconnect()
    suspend fun ensurePermissions(activity: Activity, mode: StreamMode): Boolean
    fun start(mode: StreamMode, epoch: Long, events: GlassEvents): Boolean
    fun stop(mode: StreamMode)
}

class HtcGlassLink(context: Context) : GlassLink {
    private val glass = ViveGlass()
    private val kit = ViveGlassKit(context.applicationContext)
    private val pendingPermissions = mutableMapOf<Permission, CompletableDeferred<Boolean>>()
    private val permissionLock = Any()
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    init {
        ViveGlass.adapter = kit
    }

    private val clientCallback = object : ViveGlassClientCallback {
        override fun onConnectionStateChanged(state: ConnectionState?) {
            _connected.value = state == ConnectionState.CONNECTED
        }

        override fun onPermissionResult(permission: Permission?, result: PermissionResult?) {
            if (permission == null) return
            val request = synchronized(permissionLock) { pendingPermissions.remove(permission) }
            request?.complete(result == PermissionResult.GRANT)
        }

        override fun onImageCaptured(event: CaptureEvent?, payload: ImagePayload?) = Unit
        override fun onSpeechTranscribed(event: TranscribedEvent?, text: String?) = Unit
        override fun onTextSpoken(event: SynthesisEvent?) = Unit
        override fun onKeyEvent(event: KeyEvent?) = Unit
    }

    override fun connect() = glass.connect(clientCallback)

    override fun disconnect() {
        glass.disconnect()
        _connected.value = false
    }

    override suspend fun ensurePermissions(activity: Activity, mode: StreamMode): Boolean {
        val requested = if (mode == StreamMode.AUDIO) listOf(Permission.MICROPHONE)
        else listOf(Permission.CAMERA, Permission.MICROPHONE)
        for (permission in requested) {
            if (glass.checkPermission(permission) == PermissionResult.GRANT) continue
            val deferred = synchronized(permissionLock) {
                pendingPermissions[permission] ?: CompletableDeferred<Boolean>().also {
                    pendingPermissions[permission] = it
                }
            }
            withContext(Dispatchers.Main) { glass.requestPermission(activity, permission) }
            val granted = withTimeoutOrNull(30_000) { deferred.await() } == true
            synchronized(permissionLock) { pendingPermissions.remove(permission) }
            if (!granted) return false
        }
        return true
    }

    override fun start(mode: StreamMode, epoch: Long, events: GlassEvents): Boolean {
        val audioCallback = object : StreamingBufferCallback {
            override fun onReceiveBuffer(buffer: ByteBuffer?, info: MediaCodec.BufferInfo?) {
                val packet = packetOrNull(buffer, info) ?: return
                events.onAudio(epoch, packet)
            }
        }
        val streamCallback = StreamingEventCallback { event ->
            if (event != null) events.onStreamEvent(epoch, event)
        }
        val audioFormat = if (mode == StreamMode.AUDIO) {
            AudioStreamingFormat(
                Microphone.MIC_DIRECTION_SURROUNDING,
                AudioStreamingFormat.DEFAULT_BITRATE,
                AudioStreamingFormat.DEFAULT_SAMPLE_RATE_AUDIO,
                AudioChannel.MONO,
            )
        } else {
            AudioStreamingFormat(
                Microphone.MIC_DIRECTION_SURROUNDING,
                AudioStreamingFormat.DEFAULT_BITRATE,
                AudioStreamingFormat.DEFAULT_SAMPLE_RATE_VIDEO,
                AudioChannel.STEREO,
            )
        }
        val result = if (mode == StreamMode.AUDIO) {
            glass.startAudioStreaming(audioFormat, audioCallback, streamCallback)
        } else {
            val videoCallback = object : StreamingBufferCallback {
                override fun onReceiveBuffer(buffer: ByteBuffer?, info: MediaCodec.BufferInfo?) {
                    val packet = packetOrNull(buffer, info) ?: return
                    events.onVideo(epoch, packet)
                }
            }
            val videoFormat = VideoStreamingFormat(
                VideoStreamingFormat.DEFAULT_BITRATE,
                VideoStreamingFormat.VideoQuality.DEFAULT,
                VideoStreamingFormat.FrameRate.FPS_15,
                false,
            )
            glass.startVideoStreaming(videoFormat, audioFormat, videoCallback, audioCallback, streamCallback)
        }
        return result == 0
    }

    override fun stop(mode: StreamMode) {
        if (mode == StreamMode.AUDIO) glass.stopAudioStreaming() else glass.stopVideoStreaming()
    }

    private fun packetOrNull(buffer: ByteBuffer?, info: MediaCodec.BufferInfo?): EncodedPacket? {
        if (buffer == null || info == null) return null
        return try {
            BufferSlice.copy(buffer, info.offset, info.size, info.presentationTimeUs, info.flags)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
