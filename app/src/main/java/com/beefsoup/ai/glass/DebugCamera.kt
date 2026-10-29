package com.beefsoup.ai.glass

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.htc.viveglass.sdk.StreamingEvent
import com.htc.viveglass.sdk.VideoStreamingFormat.VideoQuality
import com.htc.viveglass.sdk.VideoStreamingFormat.FrameRate
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

enum class PhotoSaveQuality(val label: String, val jpegQuality: Int?) {
    ORIGINAL("原始影像（不重新壓縮）", null), HIGH("高品質 JPEG（95）", 95), SMALL("較小檔案 JPEG（80）", 80)
}

internal val previewFrameRates = listOf(5, 8, 10, 15, 24, 30)
internal fun streamFrameRateFor(fps: Int): FrameRate = FrameRate.values().first { it.value >= fps }

/** Preview state is separate from meeting data. Only one decoded frame is retained. */
data class DebugCameraState(
    val active: Boolean = false,
    val quality: VideoQuality = VideoQuality.DEFAULT,
    val previewFps: Int = 15,
    val takingPhoto: Boolean = false,
    val photoPreview: Bitmap? = null,
    val photoInfo: String = "",
    val photoSaved: Boolean = false,
    val photoQuality: PhotoSaveQuality = PhotoSaveQuality.ORIGINAL,
    val photoStatus: String = "停止預覽後，可直接使用眼鏡相機拍照。",
    val frame: Bitmap? = null,
    val ptsUs: Long = 0,
    val deliveredFps: Float = 0f,
    val status: String = "開始預覽後，眼鏡畫面會顯示在這裡。",
    val saving: Boolean = false,
    val savedUri: Uri? = null,
)

class DebugCamera(private val context: Context, private val link: () -> GlassLink,
    private val canStart: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private val mutableState = MutableStateFlow(DebugCameraState())
    val state: StateFlow<DebugCameraState> = mutableState
    @Volatile private var generation = 0L
    private val latestFrame = AtomicReference<Triple<Long, Bitmap, Long>?>(null)
    private val framePosted = AtomicBoolean(false)
    @Volatile private var pipeline: VideoOcrPipeline? = null
    private var streamingLink: GlassLink? = null
    private var capturedPhoto: CapturedPhoto? = null

    fun setQuality(quality: VideoQuality) {
        if (BuildConfig.DEBUG && !state.value.active && !state.value.takingPhoto) mutableState.update { it.copy(quality = quality) }
    }

    fun setPreviewFps(fps: Int) {
        if (BuildConfig.DEBUG && !state.value.active && !state.value.takingPhoto && fps in previewFrameRates)
            mutableState.update { it.copy(previewFps = fps) }
    }

    suspend fun start(activity: Activity) {
        if (!BuildConfig.DEBUG || state.value.active || state.value.takingPhoto) return
        if (!canStart() || !link().connected.value) {
            mutableState.update { it.copy(status = "請先停止會議、關閉模擬來源並連接眼鏡。") }
            return
        }
        val quality = state.value.quality
        val previewFps = state.value.previewFps
        val epoch = ++generation
        val source = link()
        var delivered = 0
        var lastRateAt = android.os.SystemClock.elapsedRealtime()
        mutableState.update { it.copy(active = true, frame = null, deliveredFps = 0f, status = "正在取得相機授權…") }
        try {
            val allowed = source.ensurePermissions(activity, StreamMode.AUDIO_VIDEO)
            if (epoch != generation) return
            if (!allowed || !source.connected.value || !canStart()) {
                stop("無法開始預覽，請確認眼鏡相機／麥克風授權與連線。")
                return
            }
            pipeline = VideoOcrPipeline({}, { reason -> main.post {
                if (epoch == generation) stop(reason)
            } }, automaticSelection = false, analyzeText = false, onPreview = { bitmap, ptsUs ->
                if (epoch == generation) {
                    latestFrame.set(Triple(epoch, bitmap, ptsUs))
                    if (framePosted.compareAndSet(false, true)) main.post {
                        val latest = latestFrame.getAndSet(null)
                        framePosted.set(false)
                        if (latest != null && latest.first == generation && state.value.active) {
                            delivered++
                            val now = android.os.SystemClock.elapsedRealtime()
                            val elapsed = now - lastRateAt
                            val measured = if (elapsed >= 1000) delivered * 1000f / elapsed else state.value.deliveredFps
                            if (elapsed >= 1000) { delivered = 0; lastRateAt = now }
                            mutableState.update { it.copy(frame = latest.second, ptsUs = latest.third, deliveredFps = measured,
                                status = "即時預覽中 · 目標 $previewFps fps") }
                        }
                    }
                }
            }, previewFps = previewFps, initialWidth = quality.width, initialHeight = quality.height)
            streamingLink = source
            mutableState.update { it.copy(status = "等待眼鏡影像…") }
            val started = source.start(StreamMode.AUDIO_VIDEO, epoch, object : GlassEvents {
                // The SDK's camera stream includes audio. Preview never saves or sends that audio.
                override fun onAudio(epoch: Long, packet: EncodedPacket) = Unit
                override fun onVideo(epoch: Long, packet: EncodedPacket) {
                    if (epoch == generation) pipeline?.offer(packet)
                }
                override fun onStreamEvent(epoch: Long, event: StreamingEvent) {
                    main.post {
                        if (epoch == generation && event != StreamingEvent.STARTED)
                            stop("眼鏡預覽已停止：$event")
                    }
                }
            }, videoQuality = quality, videoFrameRate = streamFrameRateFor(previewFps))
            if (!started) stop("SDK 無法啟動預覽，請確認眼鏡連線與授權。")
            else main.postDelayed({
                if (epoch == generation && state.value.frame == null) stop("等待影像逾時，請確認眼鏡仍在配戴並重試。")
            }, 15_000)
        } catch (error: Exception) {
            if (epoch == generation) stop("預覽失敗：${error.message}")
        }
    }

    fun stop(message: String = "預覽已停止。") {
        generation++
        latestFrame.set(null)
        val source = streamingLink
        streamingLink = null
        pipeline?.close()
        pipeline = null
        mutableState.update { it.copy(active = false, frame = null, status = message) }
        runCatching { source?.stop(StreamMode.AUDIO_VIDEO) }
    }

    fun setPhotoQuality(quality: PhotoSaveQuality) {
        if (BuildConfig.DEBUG && !state.value.takingPhoto && !state.value.saving) mutableState.update { it.copy(photoQuality = quality) }
    }

    suspend fun captureStill(activity: Activity) {
        if (!BuildConfig.DEBUG || state.value.active || state.value.takingPhoto || state.value.saving) return
        if (!canStart() || !link().connected.value) {
            mutableState.update { it.copy(photoStatus = "請先停止會議並連接眼鏡。") }
            return
        }
        val epoch = ++generation
        val source = link()
        mutableState.update { it.copy(takingPhoto = true, savedUri = null, photoStatus = "正在取得相機授權…") }
        try {
            val allowed = source.ensurePhotoPermission(activity)
            if (epoch != generation) return
            check(allowed && source.connected.value && canStart()) { "請確認眼鏡相機授權與連線。" }
            mutableState.update { it.copy(photoStatus = "眼鏡正在拍照…") }
            val bytes = source.capturePhoto()
            if (epoch != generation) return
            mutableState.update { it.copy(photoStatus = "正在讀取照片…") }
            val photo = withContext(Dispatchers.IO) { decodeStillPhoto(bytes) }
            if (epoch != generation) { photo.preview.recycle(); return }
            capturedPhoto = photo
            mutableState.update { it.copy(photoPreview = photo.preview, photoSaved = false,
                photoInfo = "${photo.mimeType} · ${photo.width} × ${photo.height} · ${bytes.size} bytes",
                photoStatus = "照片已拍攝，尚未儲存。請確認後按「儲存照片」。") }
        } catch (error: Exception) {
            if (epoch == generation) mutableState.update { it.copy(photoStatus = if (error is TimeoutCancellationException)
                "拍照逾時，請稍候或重新開啟 App。" else "拍照失敗：${error.message}") }
        } finally {
            mutableState.update { it.copy(takingPhoto = false) }
        }
    }

    fun discardStill() {
        if (state.value.saving || state.value.takingPhoto) return
        capturedPhoto = null
        mutableState.update { it.copy(photoPreview = null, photoInfo = "", photoSaved = false,
            photoStatus = "已放棄預覽照片，沒有刪除相簿中的檔案。") }
    }

    suspend fun saveStill() {
        if (!BuildConfig.DEBUG || state.value.takingPhoto || state.value.saving || state.value.photoSaved) return
        val photo = capturedPhoto ?: return
        val quality = state.value.photoQuality
        mutableState.update { it.copy(saving = true) }
        try {
            val uri = withContext(Dispatchers.IO) {
                val encoded = encodeStillPhoto(photo, quality)
                saveDebugImage(context, encoded.bytes, encoded.mimeType)
            }
            mutableState.update { it.copy(savedUri = uri, photoSaved = true, photoStatus = "照片已儲存至 Pictures/AI Glass Debug") }
        } catch (error: Exception) {
            mutableState.update { it.copy(photoStatus = "儲存失敗：${error.message}") }
        } finally { mutableState.update { it.copy(saving = false) } }
    }

    fun capture() {
        if (!BuildConfig.DEBUG || !state.value.active || state.value.saving || state.value.takingPhoto) return
        val frame = state.value.frame ?: return
        val epoch = generation
        mutableState.update { it.copy(saving = true, savedUri = null) }
        scope.launch {
            try {
                val photo = withContext(Dispatchers.IO) {
                    val bytes = ByteArrayOutputStream()
                    check(frame.compress(Bitmap.CompressFormat.JPEG, 95, bytes)) { "無法擷取畫面" }
                    CapturedPhoto(bytes.toByteArray(), "image/jpeg", frame.width, frame.height, frame)
                }
                if (epoch == generation) {
                    capturedPhoto = photo
                    mutableState.update { it.copy(photoPreview = photo.preview, photoSaved = false,
                        photoInfo = "串流影格 · ${photo.width} × ${photo.height}",
                        photoStatus = "畫面已擷取，尚未儲存。請在下方確認後按「儲存照片」。") }
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(photoStatus = "擷取失敗：${error.message}") }
            } finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}

/** Publish only a complete JPEG; failed writes leave no empty gallery entry. Android 10+. */
internal fun saveDebugPhoto(context: Context, frame: Bitmap): Uri {
    val bytes = ByteArrayOutputStream()
    check(frame.compress(Bitmap.CompressFormat.JPEG, 95, bytes)) { "無法編碼照片" }
    return saveDebugJpeg(context, bytes.toByteArray())
}

internal fun saveDebugJpeg(context: Context, bytes: ByteArray): Uri = saveDebugImage(context, bytes, "image/jpeg")

internal fun saveDebugImage(context: Context, bytes: ByteArray, mimeType: String): Uri {
    val extension = imageExtension(mimeType)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "AI_Glass_${UUID.randomUUID()}.$extension")
        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AI Glass Debug")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) { "無法建立照片" }
    try {
        checkNotNull(resolver.openOutputStream(uri)).use { output ->
            output.write(bytes)
        }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
        return uri
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}
