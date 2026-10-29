package com.beefsoup.ai.glass

import android.app.Activity
import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Foreground-only diagnostics; never writes into the meeting store. */
data class DebugToolsState(
    val busy: Boolean = false,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val status: String = "測試結果會顯示在這裡。",
    val audioPackets: Long = 0,
    val audioBytes: Long = 0,
    val peak: Int = 0,
    val files: List<String> = emptyList(),
    val playing: Boolean = false,
)

class DebugSdkTools(private val context: Context, private val link: () -> GlassLink,
    private val canStart: () -> Boolean) {
    private val mutable = MutableStateFlow(DebugToolsState())
    val state: StateFlow<DebugToolsState> = mutable
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    @Volatile private var audio: AudioPipeline? = null
    private var source: GlassLink? = null
    private var player: MediaPlayer? = null
    private var finishing = false
    private val packets = AtomicLong()
    private val bytes = AtomicLong()

    private fun available(): Boolean = BuildConfig.DEBUG && !state.value.busy && !state.value.playing && canStart() && link().connected.value &&
        !link().diagnostics.value.speaking && !link().diagnostics.value.transcribing

    fun refresh() { runCatching { link().refreshDiagnostics() }.onFailure { status("讀取失敗：${it.message}") } }
    private fun status(text: String) { mutable.update { it.copy(status = text) } }

    suspend fun requestPermission(activity: Activity, camera: Boolean) {
        if (!available()) return
        val epoch = ++generation
        mutable.update { it.copy(busy = true) }
        try {
            val ok = if (camera) link().ensurePhotoPermission(activity) else link().ensurePermissions(activity, StreamMode.AUDIO)
            if (epoch == generation) status(if (ok) "權限已授予" else "權限未授予")
            refresh()
        } catch (error: Exception) { if (epoch == generation) status("授權失敗：${error.message}") }
        finally { if (epoch == generation) mutable.update { it.copy(busy = false) } }
    }

    suspend fun startAudio(activity: Activity) {
        if (!available()) return
        val epoch = ++generation
        val device = link()
        mutable.update { it.copy(busy = true, status = "正在取得麥克風授權…", files = emptyList(), audioPackets = 0, audioBytes = 0, peak = 0) }
        try {
            if (!device.ensurePermissions(activity, StreamMode.AUDIO) || epoch != generation || !canStart()) {
                if (epoch == generation) stop()
                return
            }
            val directory = File(context.filesDir, "debug-audio/${UUID.randomUUID()}")
            packets.set(0); bytes.set(0)
            audio = AudioPipeline(directory, onPcmChunk = { chunk ->
                var peak = 0
                for (i in chunk.pcm16.indices step 2) {
                    val sample = ((chunk.pcm16[i].toInt() and 255) or (chunk.pcm16[i + 1].toInt() shl 8)).toShort().toInt()
                    peak = maxOf(peak, kotlin.math.abs(sample))
                }
                if (epoch == generation) mutable.update { it.copy(peak = peak) }
            }, onError = { reason -> main.post { if (epoch == generation) { stop(); status(reason) } } },
                onCommitted = { file -> mutable.update { it.copy(files = it.files + file.absolutePath) } })
            source = device
            mutable.update { it.copy(recording = true, status = "測試收音中；M4A 音訊儲存於 App 私有目錄。") }
            check(device.start(StreamMode.AUDIO, epoch, object : GlassEvents {
                override fun onAudio(epoch: Long, packet: EncodedPacket) {
                    if (epoch != generation) return
                    packets.incrementAndGet(); bytes.addAndGet(packet.bytes.size.toLong())
                    if (audio?.offer(packet) == false) main.post { if (epoch == generation) { stop(); status("音訊佇列已滿，測試已停止。") } }
                }
                override fun onVideo(epoch: Long, packet: EncodedPacket) = Unit
                override fun onStreamEvent(epoch: Long, event: com.htc.viveglass.sdk.StreamingEvent) {
                    if (event != com.htc.viveglass.sdk.StreamingEvent.STARTED) main.post {
                        if (epoch == generation) { stop(); status("音訊串流事件：$event") }
                    }
                }
            })) { "SDK 拒絕啟動音訊" }
            fun tick() {
                if (epoch != generation) return
                mutable.update { it.copy(audioPackets = packets.get(), audioBytes = bytes.get()) }
                main.postDelayed({ tick() }, 500)
            }
            tick()
            main.postDelayed({ if (epoch == generation && packets.get() == 0L) { stop(); status("等待音訊逾時") } }, 15_000)
        } catch (error: Exception) { if (epoch == generation) { stop(); status("收音失敗：${error.message}") } }
    }

    suspend fun startTranscription(activity: Activity) {
        if (!available()) return
        val epoch = ++generation
        val device = link()
        mutable.update { it.copy(busy = true) }
        try {
            val allowed = device.ensurePermissions(activity, StreamMode.AUDIO)
            if (epoch != generation) return
            check(allowed && canStart()) { "請確認麥克風授權與目前測試狀態" }
            check(device.transcribe(true)) { "SDK 拒絕啟動轉錄" }
            source = device
            mutable.update { it.copy(transcribing = true, status = "SDK 連續轉錄中；結果顯示於下方。") }
        } catch (error: Exception) { if (epoch == generation) { stop(); status("轉錄失敗：${error.message}") } }
    }

    fun speak(text: String, language: String) {
        if (!available() || text.isBlank()) return
        runCatching { check(link().speak(text.take(200), Locale.forLanguageTag(language))) { "SDK 拒絕播報" } }
            .onSuccess { status("已送出 SDK 播報，請查看事件結果。") }
            .onFailure { status("播報失敗：${it.message}") }
    }

    fun stop() {
        generation++
        stopPlayback()
        val device = source
        source = null
        if (state.value.transcribing) runCatching { device?.stopTranscription() }
        if (state.value.recording) runCatching { device?.stop(StreamMode.AUDIO) }
        val pipeline = audio
        audio = null
        finishing = finishing || pipeline != null
        mutable.update { it.copy(recording = false, transcribing = false, busy = finishing,
            audioPackets = packets.get(), audioBytes = bytes.get(), status = if (pipeline != null) "正在保存測試音訊…" else "測試已停止。") }
        pipeline?.finish { main.post { finishing = false; mutable.update { it.copy(busy = false, status = "測試音訊已保存，可選取片段播放。") } } }
    }

    fun play(path: String) {
        if (state.value.busy || !canStart() || link().diagnostics.value.speaking || path !in state.value.files) return
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnPreparedListener { it.start(); mutable.update { s -> s.copy(playing = true) } }
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); status("音訊播放失敗"); true }
                prepareAsync()
            }
            mutable.update { it.copy(playing = true) }
        } catch (error: Exception) { stopPlayback(); status("無法播放：${error.message}") }
    }
    fun stopPlayback() { player?.release(); player = null; mutable.update { it.copy(playing = false) } }
    fun deleteAudio() {
        if (state.value.busy || state.value.playing) return
        val root = File(context.filesDir, "debug-audio").canonicalFile
        state.value.files.forEach { path -> File(path).canonicalFile.takeIf { it.parentFile?.parentFile == root }?.delete() }
        mutable.update { it.copy(files = emptyList(), status = "此次測試音訊已刪除。") }
    }
}
