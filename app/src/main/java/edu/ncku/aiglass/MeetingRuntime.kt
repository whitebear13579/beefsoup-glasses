package edu.ncku.aiglass

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.htc.viveglass.sdk.StreamingEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class MeetingUiState(
    val session: SessionSnapshot = SessionSnapshot(SessionPhase.IDLE, null, 0),
    val connected: Boolean = false,
    val demo: Boolean = false,
    val status: String = "尚未連線",
    val transcript: List<String> = emptyList(),
    val ocr: List<String> = emptyList(),
    val summary: String = "",
    val savedDirectory: String = "",
    val pendingAudio: Int = 0,
)

data class OffloadSettings(val endpoint: String, val token: String)

class MeetingRuntime(private val app: Application) {
    private val main = Handler(Looper.getMainLooper())
    private val machine = SessionMachine()
    private var link: GlassLink = HtcGlassLink(app)
    private var audio: AudioPipeline? = null
    private var video: VideoOcrPipeline? = null
    private var remote: RemoteInferenceClient? = null
    private var store: MeetingStore? = null
    private var transcriptTimeline = TranscriptTimeline()
    private var ocrSequence = 0L
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _state = MutableStateFlow(MeetingUiState())
    val state: StateFlow<MeetingUiState> = _state

    init {
        observeConnection()
        MeetingStore.latest(app)?.let { previous ->
            store = previous
            val events = previous.events()
            events.filter { it.optString("type") == "transcript" }.forEach { event ->
                val data = event.getJSONObject("data")
                transcriptTimeline.accept(InferenceResult(data.getLong("sequence"), data.getLong("startUs"),
                    data.getString("text"), data.optString("translation"), data.optString("model")))
            }
            ocrSequence = previous.ocrObservations().size.toLong()
            _state.update { it.copy(
                savedDirectory = previous.directory.absolutePath,
                pendingAudio = previous.pendingCount(),
                transcript = transcriptTimeline.display().takeLast(50),
                ocr = previous.ocrObservations().map { entry -> entry.second.text }.takeLast(20),
                summary = events.lastOrNull { it.optString("type") == "summary" }
                    ?.optJSONObject("data")?.optString("text").orEmpty(),
                status = if (previous.stoppedCleanly()) "已載入最近會議，可補送待轉譯片段"
                    else "上次錄製未正常結束；請檢查音訊缺口與 .partial 檔案",
            ) }
        }
    }

    private fun observeConnection() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            link.connected.collect { connected ->
                _state.update { it.copy(connected = connected) }
                if (!connected && machine.snapshot().phase == SessionPhase.RECORDING)
                    fail(machine.snapshot().epoch, "眼鏡連線中斷")
            }
        }
    }

    fun setDemo(enabled: Boolean) {
        if (machine.snapshot().phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)) return
        link.disconnect()
        link = if (enabled) DemoGlassLink() else HtcGlassLink(app)
        observeConnection()
        _state.update { it.copy(demo = enabled, connected = false,
            status = if (enabled) "模擬來源：合成音訊，不代表眼鏡實測" else "請先以 VIVE Connect 配對眼鏡") }
    }

    fun connect() {
        try {
            link.connect()
            _state.update { it.copy(connected = link.connected.value,
                status = if (it.demo) "模擬來源已連線" else "正在連接 VIVE Eagle，請確認 VIVE Connect 已配對") }
        } catch (error: Exception) {
            _state.update { it.copy(status = "眼鏡連線失敗：${error.message}") }
        }
    }

    fun showPermissionDenied() {
        _state.update { it.copy(status = "附近裝置權限未授予，請於系統設定允許後重試") }
    }

    suspend fun start(activity: Activity, mode: StreamMode, offload: OffloadSettings?): Boolean {
        if (!link.connected.value) {
            _state.update { it.copy(status = "請先連線") }
            return false
        }
        if (state.value.demo && mode == StreamMode.AUDIO_VIDEO) {
            _state.update { it.copy(status = "模擬來源僅提供合成音訊；相機 OCR 須眼鏡真機") }
            return false
        }
        if (!link.ensurePermissions(activity, mode)) {
            _state.update { it.copy(status = "眼鏡麥克風／相機授權未通過") }
            return false
        }
        val epoch = machine.begin(mode) ?: return false
        remote?.close()
        remote = null
        transcriptTimeline = TranscriptTimeline()
        ocrSequence = 0L
        _state.update { it.copy(session = machine.snapshot(), status = "準備錄製…",
            transcript = emptyList(), ocr = emptyList(), summary = "", pendingAudio = 0) }
        try {
            val sessionStore = MeetingStore.create(app)
            store = sessionStore
            sessionStore.event("start", JSONObject().put("mode", mode.name)
                .put("epoch", epoch).put("demo", state.value.demo))
            _state.update { it.copy(savedDirectory = sessionStore.directory.absolutePath) }
            if (offload != null) {
                remote = RemoteInferenceClient(sessionStore, epoch,
                    onResult = { result ->
                        sessionStore.event("transcript", JSONObject().put("sequence", result.sequence)
                            .put("startUs", result.startUs).put("text", result.transcript)
                            .put("translation", result.translation).put("model", result.model))
                        synchronized(transcriptTimeline) {
                            transcriptTimeline.accept(result)
                            _state.update { old -> old.copy(
                                transcript = transcriptTimeline.display().takeLast(50),
                                pendingAudio = (sessionStore.pendingCount() - 1).coerceAtLeast(0)) }
                        }
                    },
                    onSummary = { summary ->
                        sessionStore.event("summary", JSONObject().put("text", summary))
                        _state.update { it.copy(summary = summary) }
                    },
                    onStatus = { status -> _state.update { it.copy(status = status) } })
                remote!!.connect(offload.endpoint, offload.token, BuildConfig.DEBUG)
            }
            audio = AudioPipeline(File(sessionStore.directory, "audio"),
                onPcmChunk = { chunk ->
                    try {
                        sessionStore.savePending(chunk)
                        _state.update { it.copy(pendingAudio = it.pendingAudio + 1) }
                        remote?.onPendingChunk()
                    } catch (error: Exception) { main.post { fail(epoch, "音訊保存失敗：${error.message}") } }
                },
                onError = { reason -> main.post { fail(epoch, reason) } },
                onCommitted = { file ->
                    sessionStore.event("audio_segment", JSONObject().put("name", file.name)
                        .put("bytes", file.length()))
                })
            if (mode == StreamMode.AUDIO_VIDEO) {
                video = VideoOcrPipeline(
                    onObservation = { observation ->
                        val sequence = synchronized(this) { ocrSequence++ }
                        sessionStore.event("ocr", JSONObject().put("sequence", sequence)
                            .put("ptsUs", observation.ptsUs)
                            .put("text", observation.text))
                        remote?.sendOcr(sequence, observation)
                        _state.update { it.copy(ocr = (it.ocr + observation.text).takeLast(20)) }
                    },
                    onWarning = { reason ->
                        sessionStore.event("warning", JSONObject().put("reason", reason))
                        _state.update { it.copy(status = reason) }
                    })
            }
            ContextCompat.startForegroundService(app, Intent(app, MeetingService::class.java)
                .setAction(MeetingService.ACTION_START))
            val events = object : GlassEvents {
                override fun onAudio(epoch: Long, packet: EncodedPacket) {
                    if (machine.snapshot().epoch != epoch || audio?.offer(packet) == false) {
                        main.post { fail(epoch, "音訊接收佇列溢位或已關閉，錄製已停止") }
                    }
                }
                override fun onVideo(epoch: Long, packet: EncodedPacket) {
                    if (machine.snapshot().epoch == epoch) video?.offer(packet)
                }
                override fun onStreamEvent(epoch: Long, event: StreamingEvent) {
                    main.post { handleStreamEvent(epoch, event) }
                }
            }
            if (!link.start(mode, epoch, events)) {
                fail(epoch, "SDK 拒絕啟動串流")
                return false
            }
            main.postDelayed({
                if (machine.snapshot().epoch == epoch && machine.snapshot().phase == SessionPhase.STARTING)
                    fail(epoch, "等待 SDK 串流啟動逾時")
            }, 15_000)
            return true
        } catch (error: Exception) {
            fail(epoch, "無法開始錄製：${error.message}")
            return false
        }
    }

    fun stop() {
        val epoch = machine.stopping() ?: return
        val mode = state.value.session.mode ?: return
        _state.update { it.copy(session = machine.snapshot(), status = "正在收尾並保存音訊…") }
        try { link.stop(mode) } catch (_: Exception) { }
        video?.close()
        video = null
        val pipeline = audio
        audio = null
        pipeline?.finish {
            remote?.finish()
            store?.event("stop", JSONObject().put("epoch", epoch))
            main.post {
                if (machine.stopped(epoch))
                    _state.update { it.copy(session = machine.snapshot(), status = "錄製已保存；待轉譯 ${it.pendingAudio} 段") }
                else _state.update { it.copy(session = machine.snapshot()) }
                app.stopService(Intent(app, MeetingService::class.java))
            }
        } ?: run {
            machine.stopped(epoch)
            _state.update { it.copy(session = machine.snapshot(), status = "錄製已停止") }
            app.stopService(Intent(app, MeetingService::class.java))
        }
    }

    private fun handleStreamEvent(epoch: Long, event: StreamingEvent) {
        if (epoch != machine.snapshot().epoch) return
        when (event) {
            StreamingEvent.STARTED -> if (machine.started(epoch))
                _state.update { it.copy(session = machine.snapshot(), status =
                    if (it.demo) "模擬錄製中：合成音訊" else "眼鏡錄製中") }
            StreamingEvent.STOPPED -> if (machine.snapshot().phase == SessionPhase.RECORDING)
                fail(epoch, "眼鏡串流意外停止")
            StreamingEvent.BATTERY_LOW, StreamingEvent.GLASS_OVERHEATING,
            StreamingEvent.ERROR_RESOURCE_CONFLICT, StreamingEvent.ERROR ->
                fail(epoch, "眼鏡串流事件：$event")
        }
    }

    private fun fail(epoch: Long, reason: String) {
        if (!machine.fail(epoch, reason)) return
        store?.event("health", JSONObject().put("epoch", epoch).put("reason", reason))
        val mode = state.value.session.mode
        if (mode != null) try { link.stop(mode) } catch (_: Exception) { }
        video?.close(); video = null
        val pipeline = audio
        audio = null
        pipeline?.finish {
            remote?.finish()
            app.stopService(Intent(app, MeetingService::class.java))
        } ?: app.stopService(Intent(app, MeetingService::class.java))
        _state.update { it.copy(session = machine.snapshot(), status = reason) }
    }

    fun exportTimeline(): String = store?.exportText().orEmpty()

    fun deleteCurrent() {
        if (machine.snapshot().phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)) return
        try {
            remote?.close(); remote = null
            store?.delete(app)
            store = null
            transcriptTimeline = TranscriptTimeline()
            _state.update { it.copy(savedDirectory = "", pendingAudio = 0, transcript = emptyList(),
                ocr = emptyList(), summary = "", status = "會議資料已刪除") }
        } catch (error: Exception) {
            _state.update { it.copy(status = "無法刪除會議：${error.message}") }
        }
    }

    fun retryPending(settings: OffloadSettings) {
        if (machine.snapshot().phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)) return
        val sessionStore = store ?: return
        val epoch = sessionStore.startEpoch() ?: return
        remote?.close()
        remote = RemoteInferenceClient(sessionStore, epoch,
            onResult = { result ->
                sessionStore.event("transcript", JSONObject().put("sequence", result.sequence)
                    .put("startUs", result.startUs).put("text", result.transcript)
                    .put("translation", result.translation).put("model", result.model))
                synchronized(transcriptTimeline) {
                    transcriptTimeline.accept(result)
                        _state.update { it.copy(transcript = transcriptTimeline.display().takeLast(50),
                        pendingAudio = (sessionStore.pendingCount() - 1).coerceAtLeast(0)) }
                }
            },
            onSummary = { summary ->
                sessionStore.event("summary", JSONObject().put("text", summary))
                _state.update { it.copy(summary = summary) }
            },
            onStatus = { status -> _state.update { it.copy(status = status) } })
        try {
            remote!!.connect(settings.endpoint, settings.token, BuildConfig.DEBUG)
            remote!!.finish()
        } catch (error: Exception) {
            _state.update { it.copy(status = "補送設定錯誤：${error.message}") }
        }
    }

    fun closeInference() { remote?.close(); remote = null }
}
