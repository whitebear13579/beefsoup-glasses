package com.beefsoup.ai.glass

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
    val connecting: Boolean = false,
    val userIssue: String? = null,
    val needsSystemSettings: Boolean = false,
    val highlights: List<MeetingHighlight> = emptyList(),
    val utterances: List<TranslatedUtterance> = emptyList(),
    val speechStatus: String = "自動播報已關閉",
    val automaticSpeech: Boolean = false,
    val continuousTranslation: Boolean = false,
    val inferenceStatus: String = "尚未啟用翻譯服務",
    val visionStatus: String = "只保留與會議有關的重點影像",
    val recordingSinceMs: Long = 0,
)

data class OffloadSettings(val endpoint: String, val token: String, val targetLanguage: String = "zh-TW")

class MeetingRuntime(private val app: Application) {
    private val main = Handler(Looper.getMainLooper())
    private val machine = SessionMachine()
    private var link: GlassLink = HtcGlassLink(app)
    private var audio: AudioPipeline? = null
    private var video: VideoOcrPipeline? = null
    private var remote: RemoteInferenceClient? = null
    private var visionReviewer: VisionReviewer? = null
    private var speaker: TranslationSpeaker? = null
    private val utteranceIds = mutableSetOf<String>()
    private var store: MeetingStore? = null
    private var transcriptTimeline = TranscriptTimeline()
    private var ocrSequence = 0L
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _state = MutableStateFlow(MeetingUiState())
    val state: StateFlow<MeetingUiState> = _state
    private fun meetingActive() = machine.snapshot().phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)
    private val _sdkDiagnostics = MutableStateFlow(SdkDiagnostics())
    val sdkDiagnostics: StateFlow<SdkDiagnostics> = _sdkDiagnostics
    private var diagnosticsJob: Job? = null
    val debugCamera: DebugCamera? = if (BuildConfig.DEBUG) DebugCamera(app, { link }, {
        !state.value.demo && !meetingActive() && debugTools?.state?.value?.busy != true &&
            debugTools?.state?.value?.playing != true && !link.diagnostics.value.speaking && !link.diagnostics.value.transcribing
    }) else null
    val debugCameraState: StateFlow<DebugCameraState> = debugCamera?.state ?: MutableStateFlow(DebugCameraState())
    val debugTools: DebugSdkTools? = if (BuildConfig.DEBUG) DebugSdkTools(app, { link }, {
        !state.value.demo && !meetingActive() && !debugCameraState.value.active && !debugCameraState.value.takingPhoto
    }) else null
    val debugToolsState: StateFlow<DebugToolsState> = debugTools?.state ?: MutableStateFlow(DebugToolsState())
    private fun debugBusy() = debugCameraState.value.active || debugCameraState.value.takingPhoto ||
        debugToolsState.value.busy || debugToolsState.value.playing || link.diagnostics.value.speaking || link.diagnostics.value.transcribing

    fun stopDebugTests() { debugCamera?.stop(); debugTools?.stop() }
    fun disconnectDebug() {
        if (!BuildConfig.DEBUG || meetingActive()) return
        stopDebugTests()
        link.disconnect()
        _state.update { it.copy(connected = false, connecting = false, status = "眼鏡已斷線") }
    }
    fun clearDebugEvents() { if (BuildConfig.DEBUG) link.clearDiagnostics() }

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
            ocrSequence = (previous.highlights().maxOfOrNull { it.sequence } ?: -1) + 1
            utteranceIds.addAll(previous.utterances().map { it.id })
            _state.update { it.copy(
                savedDirectory = previous.directory.absolutePath,
                pendingAudio = previous.pendingCount(),
                transcript = transcriptTimeline.display(),
                ocr = previous.ocrObservations().map { entry -> entry.second.text }.takeLast(20),
                highlights = previous.highlights().filterNot { it.excluded }.takeLast(100),
                utterances = previous.utterances(),
                summary = events.lastOrNull { it.optString("type") == "summary" }
                    ?.optJSONObject("data")?.optString("text").orEmpty(),
                status = if (previous.stoppedCleanly()) "已載入最近會議，可補送待轉譯片段"
                    else "上次錄製未正常結束；請檢查音訊缺口與 .partial 檔案",
            ) }
        }
    }

    private fun observeConnection() {
        connectionJob?.cancel()
        diagnosticsJob?.cancel()
        if (BuildConfig.DEBUG) {
            val source = link
            diagnosticsJob = scope.launch { source.diagnostics.collect { _sdkDiagnostics.value = it } }
        }
        connectionJob = scope.launch {
            link.connected.collect { connected ->
                _state.update { old -> old.copy(connected = connected,
                    connecting = if (connected) false else old.connecting,
                    userIssue = if (connected) null else old.userIssue,
                    status = if (connected && old.connecting) "眼鏡已連線，可以開始記錄" else old.status) }
                if (!connected && debugBusy()) stopDebugTests()
                if (!connected && machine.snapshot().phase == SessionPhase.RECORDING)
                    fail(machine.snapshot().epoch, "眼鏡連線中斷")
            }
        }
    }

    fun setDemo(enabled: Boolean) {
        if (machine.snapshot().phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)) return
        stopDebugTests()
        link.disconnect()
        link = if (enabled) DemoGlassLink() else HtcGlassLink(app)
        observeConnection()
        _state.update { it.copy(demo = enabled, connected = false,
            status = if (enabled) "模擬來源：合成音訊，不代表眼鏡實測" else "請先以 VIVE Connect 配對眼鏡") }
    }

    fun connect() {
        if (state.value.connected || state.value.connecting) return
        try {
            _state.update { it.copy(connecting = true, userIssue = null, needsSystemSettings = false,
                status = "正在連接眼鏡，請保持配戴") }
            link.connect()
            main.postDelayed({
                if (state.value.connecting && !state.value.connected)
                    _state.update { it.copy(connecting = false,
                        userIssue = "連接眼鏡逾時。請確認已配戴，並在 VIVE Connect 完成連線後重試。",
                        status = "連接眼鏡逾時") }
            }, 15_000)
        } catch (error: Exception) {
            _state.update { it.copy(connecting = false, status = "眼鏡連線失敗：${error.message}",
                userIssue = "無法連接眼鏡。請在 VIVE Connect 確認配對與此 App 的授權後重試。") }
        }
    }

    fun showPermissionDenied() {
        _state.update { it.copy(status = "附近裝置權限未授予", needsSystemSettings = true,
            userIssue = "尚未允許附近裝置權限。請在系統設定允許後，回來重新連接眼鏡。") }
    }

    fun dismissIssue() { _state.update { it.copy(userIssue = null, needsSystemSettings = false) } }

    fun showViveConnectUnavailable() {
        _state.update { it.copy(userIssue = "請先安裝 VIVE Connect 並完成眼鏡配對。", needsSystemSettings = false) }
    }

    suspend fun start(activity: Activity, mode: StreamMode, offload: OffloadSettings?,
        continuousTranslation: Boolean = false, autoSpeak: Boolean = false): Boolean {
        if (debugBusy()) {
            _state.update { it.copy(userIssue = "請先在「測試」停止目前操作，再開始會議。") }
            return false
        }
        if (!link.connected.value) {
            _state.update { it.copy(status = "請先連線", userIssue = "請先連接眼鏡，再開始記錄。") }
            return false
        }
        if (state.value.demo && mode == StreamMode.AUDIO_VIDEO) {
            _state.update { it.copy(status = "模擬來源僅提供合成音訊；相機 OCR 須眼鏡真機",
                userIssue = "模擬來源不提供相機，請關閉模擬來源並連接眼鏡。") }
            return false
        }
        if (continuousTranslation && offload == null) {
            _state.update { it.copy(status = "請先在設定中連接翻譯服務", userIssue = "請先在設定中連接翻譯服務。") }
            return false
        }
        if (!link.ensurePermissions(activity, mode)) {
            _state.update { it.copy(status = "眼鏡麥克風／相機授權未通過", needsSystemSettings = false,
                userIssue = "眼鏡麥克風／相機尚未授權。請在 VIVE Connect 允許此 App 使用眼鏡，回來後再次開始。") }
            return false
        }
        if (debugBusy()) return false
        val epoch = machine.begin(mode) ?: return false
        remote?.close()
        remote = null
        visionReviewer?.close(); visionReviewer = null
        synchronized(this) { utteranceIds.clear() }
        if (speaker == null && autoSpeak) speaker = TranslationSpeaker(app) { status ->
            _state.update { it.copy(speechStatus = status) }
        }
        speaker?.begin(autoSpeak)
        transcriptTimeline = TranscriptTimeline()
        ocrSequence = 0L
        _state.update { it.copy(session = machine.snapshot(), status = "準備錄製…", userIssue = null,
            transcript = emptyList(), ocr = emptyList(), summary = "", pendingAudio = 0,
            highlights = emptyList(), utterances = emptyList(), automaticSpeech = autoSpeak,
            continuousTranslation = continuousTranslation,
            recordingSinceMs = System.currentTimeMillis(),
            visionStatus = if (offload == null) "可手動保留照片；連接電腦後可自動找重點" else "等待清楚、穩定的畫面") }
        try {
            val sessionStore = MeetingStore.create(app)
            store = sessionStore
            sessionStore.event("start", JSONObject().put("mode", mode.name)
                .put("epoch", epoch).put("demo", state.value.demo)
                .put("continuousTranslation", continuousTranslation).put("targetLanguage", offload?.targetLanguage ?: "zh-TW"))
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
                                transcript = transcriptTimeline.display(),
                                pendingAudio = (sessionStore.pendingCount() - 1).coerceAtLeast(0)) }
                        }
                    },
                    onSummary = { summary ->
                        sessionStore.event("summary", JSONObject().put("text", summary))
                        _state.update { it.copy(summary = summary) }
                    },
                    onStatus = { status -> _state.update { it.copy(inferenceStatus = status) } },
                    targetLanguage = offload.targetLanguage, continuousTranslation = continuousTranslation,
                    onUtterance = { item -> acceptUtterance(sessionStore, epoch, item, true) })
                remote!!.connect(offload.endpoint, offload.token, BuildConfig.DEBUG)
                if (mode == StreamMode.AUDIO_VIDEO) visionReviewer = VisionReviewer(offload, sessionStore.id, epoch,
                    onReview = { frame, review ->
                        runCatching { sessionStore.event("vision_review", JSONObject().put("ptsUs", frame.ptsUs).put("keep", review.keep)
                            .put("category", review.category).put("model", review.model)) }
                        if (review.keep) saveHighlight(sessionStore, epoch, frame, review)
                        else if (store?.id == sessionStore.id) _state.update {
                            it.copy(visionStatus = "已略過與會議無關或不清楚的畫面") }
                    }, onIssue = { ptsUs, issue -> if (store?.id == sessionStore.id) {
                        runCatching { sessionStore.event("vision_error", JSONObject().put("ptsUs", ptsUs).put("reason", issue.take(500))) }
                        video?.retryCandidate()
                        _state.update { it.copy(visionStatus = "$issue；仍可手動保留照片") }
                    } })
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
                    onObservation = { /* Raw OCR is a candidate signal, not a meeting record. */ },
                    onWarning = { reason ->
                        sessionStore.event("warning", JSONObject().put("reason", reason))
                        _state.update { it.copy(status = reason) }
                    }, onCandidate = { frame ->
                        if (frame.manual) {
                            saveHighlight(sessionStore, epoch, frame, VisualReview(true, frame.text,
                                "document", "由你手動保留，請核對照片與辨識文字", listOf(0.0, 0.0, 1.0, 1.0), "phone-ocr"))
                            true
                        }
                        else {
                            runCatching { sessionStore.event("vision_candidate", JSONObject().put("ptsUs", frame.ptsUs)) }
                            _state.update { it.copy(visionStatus = "正在判斷畫面與會議的關聯") }
                            visionReviewer?.offer(frame, synchronized(transcriptTimeline) {
                                transcriptTimeline.display().takeLast(4).joinToString("\n") }) ?: false
                        }
                    }, automaticSelection = offload != null)
            }
            ContextCompat.startForegroundService(app, Intent(app, MeetingService::class.java)
                .setAction(MeetingService.ACTION_START).putExtra(MeetingService.EXTRA_TRANSLATION, continuousTranslation))
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
        speaker?.stop()
        visionReviewer?.close(); visionReviewer = null
        _state.update { it.copy(automaticSpeech = false, speechStatus = "播報已停止") }
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
                _state.update { it.copy(session = machine.snapshot(), userIssue = null, status =
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
        speaker?.stop()
        visionReviewer?.close(); visionReviewer = null
        val mode = state.value.session.mode
        if (mode != null) try { link.stop(mode) } catch (_: Exception) { }
        video?.close(); video = null
        val pipeline = audio
        audio = null
        pipeline?.finish {
            remote?.finish()
            app.stopService(Intent(app, MeetingService::class.java))
        } ?: app.stopService(Intent(app, MeetingService::class.java))
        _state.update { it.copy(session = machine.snapshot(), status = reason, automaticSpeech = false,
            userIssue = "記錄已中斷，已保存內容仍在手機。請檢查眼鏡連線、電量與授權後再開始。") }
    }

    fun exportTimeline(): String = store?.exportText().orEmpty()

    fun exportMeetingText(): String = buildString {
        appendLine("AI 眼鏡會議紀錄")
        appendLine()
        if (state.value.summary.isNotBlank()) {
            appendLine("會議摘要"); appendLine(state.value.summary); appendLine()
        }
        appendLine("逐字稿與翻譯")
        state.value.transcript.forEach { appendLine(it); appendLine() }
        appendLine("已確認的影像重點")
        state.value.highlights.filter { it.confirmed && !it.excluded }.forEach {
            appendLine("${mediaTime(it.ptsUs)}\n${it.text}\n")
        }
    }

    private fun acceptUtterance(sessionStore: MeetingStore, epoch: Long, item: TranslatedUtterance, live: Boolean) {
        synchronized(this) {
            if (store?.id != sessionStore.id || item.id in utteranceIds) return
            sessionStore.event("translation_utterance", JSONObject().put("id", item.id).put("source", item.source)
                .put("translation", item.translation).put("language", item.language)
                .put("startUs", item.startUs).put("endUs", item.endUs))
            utteranceIds.add(item.id)
            _state.update { it.copy(utterances = it.utterances + item) }
        }
        if (live) main.post {
            if (machine.snapshot().epoch == epoch && machine.snapshot().phase == SessionPhase.RECORDING)
                speaker?.offer(item)
        }
    }

    private fun saveHighlight(sessionStore: MeetingStore, epoch: Long, frame: VisualFrame, review: VisualReview) {
        if (store?.id != sessionStore.id || machine.snapshot().epoch != epoch ||
            machine.snapshot().phase != SessionPhase.RECORDING) return
        try {
            synchronized(this) {
                if (!frame.manual && state.value.highlights.lastOrNull()?.text
                        ?.filter { it.isLetterOrDigit() } == review.text.filter { it.isLetterOrDigit() }) {
                    _state.update { it.copy(visionStatus = "重點內容未變動，已避免重複保存") }
                    return
                }
                val item = sessionStore.saveHighlight(ocrSequence++, frame, review)
                _state.update { it.copy(highlights = (it.highlights + item).takeLast(100),
                    visionStatus = "已找到重點照片，請到「重點」核對並確認") }
            }
        } catch (error: Exception) {
            _state.update { it.copy(visionStatus = "照片保存失敗：${error.message}") }
        }
    }

    fun captureHighlight() {
        if (machine.snapshot().phase == SessionPhase.RECORDING && video != null) {
            video?.requestCapture()
            _state.update { it.copy(visionStatus = "正在保留下一張可解碼影格") }
        }
    }

    fun updateHighlight(id: String, text: String, confirmed: Boolean, excluded: Boolean) {
        val sessionStore = store ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val item = sessionStore.updateHighlight(id, text, confirmed, excluded)
                if (store?.id != sessionStore.id) return@launch
                remote?.removeOcr(item.sequence)
                if (item.confirmed) remote?.sendOcr(item.sequence, OcrObservation(item.ptsUs, item.text))
                _state.update { it.copy(highlights = sessionStore.highlights().filterNot { h -> h.excluded }.takeLast(100),
                    summary = "", visionStatus = if (excluded) "已排除此片段並刪除照片" else "重點已更新") }
            } catch (error: Exception) {
                _state.update { it.copy(visionStatus = "無法更新重點：${error.message}") }
            }
        }
    }

    fun setAutomaticSpeech(enabled: Boolean) {
        if (speaker == null) speaker = TranslationSpeaker(app) { status -> _state.update { it.copy(speechStatus = status) } }
        speaker?.setAutomatic(enabled)
        _state.update { it.copy(automaticSpeech = enabled) }
    }

    fun replay(item: TranslatedUtterance) {
        if (speaker == null) speaker = TranslationSpeaker(app) { status -> _state.update { it.copy(speechStatus = status) } }
        speaker?.replay(item)
    }

    fun deleteCurrent() {
        if (machine.snapshot().phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)) return
        try {
            remote?.close(); remote = null
            speaker?.stop()
            visionReviewer?.close(); visionReviewer = null
            store?.delete(app)
            store = null
            transcriptTimeline = TranscriptTimeline()
            _state.update { it.copy(savedDirectory = "", pendingAudio = 0, transcript = emptyList(),
                ocr = emptyList(), highlights = emptyList(), utterances = emptyList(), summary = "",
                automaticSpeech = false, status = "會議資料已刪除") }
        } catch (error: Exception) {
            _state.update { it.copy(status = "無法刪除會議：${error.message}") }
        }
    }

    fun retryPending(settings: OffloadSettings) {
        if (machine.snapshot().phase in setOf(SessionPhase.STARTING, SessionPhase.RECORDING, SessionPhase.STOPPING)) return
        val sessionStore = store ?: return
        val epoch = sessionStore.startEpoch() ?: return
        val startData = sessionStore.events().firstOrNull { it.optString("type") == "start" }?.optJSONObject("data")
        remote?.close()
        remote = RemoteInferenceClient(sessionStore, epoch,
            onResult = { result ->
                sessionStore.event("transcript", JSONObject().put("sequence", result.sequence)
                    .put("startUs", result.startUs).put("text", result.transcript)
                    .put("translation", result.translation).put("model", result.model))
                synchronized(transcriptTimeline) {
                    transcriptTimeline.accept(result)
                        _state.update { it.copy(transcript = transcriptTimeline.display(),
                        pendingAudio = (sessionStore.pendingCount() - 1).coerceAtLeast(0)) }
                }
            },
            onSummary = { summary ->
                sessionStore.event("summary", JSONObject().put("text", summary))
                _state.update { it.copy(summary = summary) }
            },
            onStatus = { status -> _state.update { it.copy(inferenceStatus = status) } },
            targetLanguage = startData?.optString("targetLanguage", "zh-TW") ?: "zh-TW",
            continuousTranslation = startData?.optBoolean("continuousTranslation", false) ?: false,
            onUtterance = { item -> acceptUtterance(sessionStore, epoch, item, false) })
        try {
            remote!!.connect(settings.endpoint, settings.token, BuildConfig.DEBUG)
            remote!!.finish()
        } catch (error: Exception) {
            _state.update { it.copy(status = "補送設定錯誤：${error.message}") }
        }
    }

    fun closeInference() {
        remote?.close(); remote = null
        visionReviewer?.close(); visionReviewer = null
        speaker?.stop()
    }
}
