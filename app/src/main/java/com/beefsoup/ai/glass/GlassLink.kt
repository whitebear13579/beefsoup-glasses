package com.beefsoup.ai.glass

import android.app.Activity
import android.content.Context
import android.media.MediaCodec
import com.htc.viveglass.sdk.AudioChannel
import com.htc.viveglass.sdk.AudioStreamingFormat
import com.htc.viveglass.sdk.CaptureEvent
import com.htc.viveglass.sdk.ConnectionState
import com.htc.viveglass.sdk.ImageQuality
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
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer

interface GlassEvents {
    fun onAudio(epoch: Long, packet: EncodedPacket)
    fun onVideo(epoch: Long, packet: EncodedPacket)
    fun onStreamEvent(epoch: Long, event: StreamingEvent)
}

interface GlassLink {
    val diagnostics: StateFlow<SdkDiagnostics> get() = noSdkDiagnostics
    fun refreshDiagnostics() = Unit
    fun clearDiagnostics() = Unit
    fun transcribe(continuous: Boolean): Boolean = false
    fun stopTranscription() = Unit
    fun speak(text: String, locale: Locale): Boolean = false
    val connected: StateFlow<Boolean>
    fun connect()
    fun disconnect()
    suspend fun ensurePermissions(activity: Activity, mode: StreamMode): Boolean
    fun start(mode: StreamMode, epoch: Long, events: GlassEvents,
        videoQuality: VideoStreamingFormat.VideoQuality = VideoStreamingFormat.VideoQuality.DEFAULT,
        videoFrameRate: VideoStreamingFormat.FrameRate = VideoStreamingFormat.FrameRate.FPS_15): Boolean
    fun stop(mode: StreamMode)
    suspend fun ensurePhotoPermission(activity: Activity): Boolean = ensurePermissions(activity, StreamMode.AUDIO_VIDEO)
    suspend fun capturePhoto(): ByteArray = error("此來源不支援拍照")
}

class HtcGlassLink(context: Context, private val glass: ViveGlass = ViveGlass()) : GlassLink {
    private val _diagnostics = MutableStateFlow(SdkDiagnostics())
    override val diagnostics: StateFlow<SdkDiagnostics> = _diagnostics
    private fun event(text: String) {
        if (BuildConfig.DEBUG) _diagnostics.update { it.copy(events =
            (it.events + "${java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(java.util.Date())}  $text").takeLast(40)) }
    }
    override fun clearDiagnostics() { _diagnostics.update { it.copy(events = emptyList(), transcription = "") } }
    override fun refreshDiagnostics() {
        if (!BuildConfig.DEBUG) return
        fun read(block: () -> Any?): String = runCatching { block()?.toString() ?: "未提供" }.getOrElse { "無法讀取：${it.message}" }
        _diagnostics.update { it.copy(
            cameraPermission = read { glass.checkPermission(Permission.CAMERA) },
            microphonePermission = read { glass.checkPermission(Permission.MICROPHONE) },
            preferredLocale = read { glass.getUserPreferredLocale()?.toLanguageTag() },
            sdkConnected = runCatching { glass.isConnected() }.getOrNull(),
            audioStreaming = runCatching { glass.isAudioStreaming() }.getOrNull(),
            videoStreaming = runCatching { glass.isVideoStreaming() }.getOrNull()) }
    }
    override fun transcribe(continuous: Boolean): Boolean {
        if (!BuildConfig.DEBUG) return false
        _diagnostics.update { it.copy(transcribing = true, transcription = "") }
        return try { (glass.startTranscription(continuous) == 0).also { ok ->
            event("startTranscription($continuous): $ok")
            if (!ok) _diagnostics.update { it.copy(transcribing = false) }
        } } catch (error: Exception) { _diagnostics.update { it.copy(transcribing = false) }; throw error }
    }
    override fun stopTranscription() {
        try { glass.stopTranscription() } finally { _diagnostics.update { it.copy(transcribing = false) }; event("stopTranscription") }
    }
    override fun speak(text: String, locale: Locale): Boolean {
        if (!BuildConfig.DEBUG) return false
        _diagnostics.update { it.copy(speaking = true) }
        return try { (glass.speakText(text, locale) == 0).also { ok ->
            event("speakText(${locale.toLanguageTag()}): $ok")
            if (!ok) _diagnostics.update { it.copy(speaking = false) }
        } } catch (error: Exception) { _diagnostics.update { it.copy(speaking = false) }; throw error }
    }
    private val kit = ViveGlassKit(context.applicationContext)
    private val pendingPermissions = mutableMapOf<Permission, CompletableDeferred<Boolean>>()
    private val permissionLock = Any()
    private val photoLock = Any()
    private var pendingPhoto: CompletableDeferred<ByteArray>? = null
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    init {
        ViveGlass.adapter = kit
    }

    private val clientCallback = object : ViveGlassClientCallback {
        override fun onConnectionStateChanged(state: ConnectionState?) {
            _connected.value = state == ConnectionState.CONNECTED
            event("connection: $state")
            _diagnostics.update { it.copy(connection = state?.name ?: "UNKNOWN",
                speaking = if (_connected.value) it.speaking else false,
                transcribing = if (_connected.value) it.transcribing else false) }
        }

        override fun onPermissionResult(permission: Permission?, result: PermissionResult?) {
            event("permission: $permission → $result")
            if (permission == null) return
            val request = synchronized(permissionLock) { pendingPermissions.remove(permission) }
            request?.complete(result == PermissionResult.GRANT)
        }

        override fun onImageCaptured(event: CaptureEvent?, payload: ImagePayload?) {
            event("capture: $event, bytes=${payload?.length ?: 0}")
            val request = synchronized(photoLock) { pendingPhoto.also { pendingPhoto = null } } ?: return
            val bytes = payload?.byteArray
            if (event == CaptureEvent.SUCCESS && bytes != null && payload.length in 1..bytes.size && payload.length <= 30_000_000)
                request.complete(bytes.copyOf(payload.length))
            else request.completeExceptionally(IllegalStateException("眼鏡拍照失敗：$event"))
        }
        override fun onSpeechTranscribed(event: TranscribedEvent?, text: String?) {
            event("transcription: $event")
            if (BuildConfig.DEBUG) _diagnostics.update { it.copy(
                transcription = if (it.transcribing && !text.isNullOrBlank()) (it.transcription + "\n" + text).takeLast(8000) else it.transcription,
                transcribing = it.transcribing && event == TranscribedEvent.SUCCESS) }
        }
        override fun onTextSpoken(event: SynthesisEvent?) {
            event("speech: $event")
            _diagnostics.update { it.copy(speaking = false) }
        }
        override fun onKeyEvent(event: KeyEvent?) { event("key: $event") }
    }

    override fun connect() = glass.connect(clientCallback)

    override fun disconnect() {
        glass.disconnect()
        _connected.value = false
        _diagnostics.update { it.copy(connection = "DISCONNECTED", transcribing = false, speaking = false) }
    }

    override suspend fun ensurePermissions(activity: Activity, mode: StreamMode): Boolean {
        val requested = if (mode == StreamMode.AUDIO) listOf(Permission.MICROPHONE)
        else listOf(Permission.CAMERA, Permission.MICROPHONE)
        return ensureRequestedPermissions(activity, requested)
    }

    override suspend fun ensurePhotoPermission(activity: Activity): Boolean =
        ensureRequestedPermissions(activity, listOf(Permission.CAMERA))

    private suspend fun ensureRequestedPermissions(activity: Activity, requested: List<Permission>): Boolean {
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

    override fun start(mode: StreamMode, epoch: Long, events: GlassEvents, videoQuality: VideoStreamingFormat.VideoQuality,
        videoFrameRate: VideoStreamingFormat.FrameRate): Boolean {
        val audioCallback = object : StreamingBufferCallback {
            override fun onReceiveBuffer(buffer: ByteBuffer?, info: MediaCodec.BufferInfo?) {
                val packet = packetOrNull(buffer, info) ?: return
                events.onAudio(epoch, packet)
            }
        }
        val streamCallback = StreamingEventCallback { event ->
            event("stream: $event")
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
                videoQuality,
                videoFrameRate,
                false,
            )
            glass.startVideoStreaming(videoFormat, audioFormat, videoCallback, audioCallback, streamCallback)
        }
        return result == 0
    }

    override suspend fun capturePhoto(): ByteArray {
        val request = CompletableDeferred<ByteArray>()
        synchronized(photoLock) {
            check(pendingPhoto == null) { "上次拍照尚未返回，請稍候或重新開啟 App。" }
            pendingPhoto = request
        }
        try {
            check(glass.captureImage(ImageQuality.DEFAULT) == 0) { "SDK 拒絕拍照，請確認相機未被使用。" }
        } catch (error: Exception) {
            synchronized(photoLock) { if (pendingPhoto === request) pendingPhoto = null }
            throw error
        }
        // Capture callbacks have no request ID. Retain the slot after timeout/cancellation so
        // a late image cannot be mistaken for the result of a newer capture.
        return withTimeout(20_000) { request.await() }
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
