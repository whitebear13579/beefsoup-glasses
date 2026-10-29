package com.beefsoup.ai.glass

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MeetingScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: MeetingUiState, onStart: (StreamMode) -> Unit = {}) {
        compose.setContent {
            MeetingTheme {
                MeetingScreen(state, false, {}, false, {}, "", {}, "", {}, {}, onStart, {}, {}, {}, {})
            }
        }
    }

    private fun captureForReview(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { image.recycle() }
    }

    @Test fun navigationAndRecordingActionsProvideDistinctHaptics() {
        val pulses = mutableListOf<androidx.compose.ui.hapticfeedback.HapticFeedbackType>()
        val state = mutableStateOf(MeetingUiState(connected = true))
        var stopped = false
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalHapticFeedback provides object : androidx.compose.ui.hapticfeedback.HapticFeedback {
                    override fun performHapticFeedback(type: androidx.compose.ui.hapticfeedback.HapticFeedbackType) { pulses.add(type) }
                }
            ) { MeetingTheme {
                MeetingScreen(state.value, false, {}, false, {}, "", {}, "", {}, {}, {
                    state.value = state.value.copy(session = SessionSnapshot(SessionPhase.RECORDING, StreamMode.AUDIO, 1))
                }, { stopped = true }, {}, {}, {})
            } }
        }
        compose.onNodeWithText("翻譯").performClick()
        assertEquals(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove, pulses.last())
        val count = pulses.size
        compose.onNodeWithText("開始連續翻譯").assertIsNotEnabled().performClick()
        assertEquals(count, pulses.size)
        compose.onNodeWithText("會議").performClick()
        compose.onNodeWithText("開始會議").performClick()
        assertEquals(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress, pulses.last())
        compose.onNodeWithText("停止並保存").performClick()
        assertEquals(true, stopped)
        assertEquals(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress, pulses.last())
    }

    @Test fun captureRedesignedDestinationsInBothThemes() {
        val dark = mutableStateOf(false)
        val largeText = mutableStateOf(false)
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, if (largeText.value) 1.5f else density.fontScale)) {
                MeetingTheme(dark = dark.value) {
                    MeetingScreen(MeetingUiState(), false, {}, false, {}, "", {}, "", {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        listOf(false, true).forEach { night ->
            compose.runOnIdle { dark.value = night }
            listOf("會議" to "meeting", "翻譯" to "translation", "重點" to "highlights", "設定" to "settings").forEach { (tab, name) ->
                compose.onNodeWithText(tab).performClick()
                captureForReview("redesign-${if (night) "dark" else "light"}-$name.png")
            }
        }
        compose.runOnIdle { largeText.value = true }
        listOf("會議" to "meeting", "翻譯" to "translation", "重點" to "highlights", "設定" to "settings").forEach { (tab, name) ->
            compose.onNodeWithText(tab).performClick()
            captureForReview("redesign-large-$name.png")
        }
    }

    @Test fun debugCameraTabGatesCaptureAndStopsWhenLeaving() {
        val frame = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val camera = mutableStateOf(DebugCameraState())
        var captures = 0
        var stops = 0
        compose.setContent { MeetingTheme {
            MeetingScreen(MeetingUiState(connected = true), false, {}, false, {}, "", {}, "", {}, {}, {}, {}, {}, {}, {},
                debugCameraState = camera.value,
                onDebugCameraStart = { camera.value = DebugCameraState(active = true) },
                onDebugCameraStop = { stops++; camera.value = DebugCameraState() },
                onDebugCameraCapture = { captures++ })
        } }
        compose.onNodeWithText("測試").performClick()
        compose.onNodeWithText("擷取畫面").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("開始預覽").performScrollTo().performClick()
        compose.onNodeWithText("擷取畫面").assertIsNotEnabled()
        compose.runOnIdle { camera.value = DebugCameraState(active = true, frame = frame) }
        compose.onNodeWithText("擷取畫面").performScrollTo().performClick()
        assertEquals(1, captures)
        captureForReview("debug-camera-fixture.png")
        compose.onNodeWithText("會議").performClick()
        compose.waitForIdle()
        assertEquals(1, stops)
    }

    @Test fun cameraDropdownsSelectQualityAndFpsAndLockDuringPreview() {
        val camera = mutableStateOf(DebugCameraState())
        compose.setContent { MeetingTheme {
            MeetingScreen(MeetingUiState(connected = true), false, {}, false, {}, "", {}, "", {}, {}, {}, {}, {}, {}, {},
                debugCameraState = camera.value,
                onDebugCameraStart = { camera.value = camera.value.copy(active = true) },
                onDebugCameraStop = { camera.value = camera.value.copy(active = false) },
                onDebugCameraQuality = { camera.value = camera.value.copy(quality = it) },
                onDebugCameraFps = { camera.value = camera.value.copy(previewFps = it) })
        } }
        compose.onNodeWithText("測試").performClick()
        compose.onNodeWithTag("debug-camera-quality").performScrollTo().performClick()
        captureForReview("debug-camera-quality-menu.png")
        val selected = com.htc.viveglass.sdk.VideoStreamingFormat.VideoQuality.FULL_HD
        compose.onNodeWithText(selected.cameraLabel).performClick()
        assertEquals(selected, camera.value.quality)
        compose.onNodeWithTag("debug-camera-fps").performClick()
        compose.onNodeWithText("10 fps").performClick()
        assertEquals(10, camera.value.previewFps)
        compose.onNodeWithText("開始預覽").performScrollTo().performClick()
        compose.onNodeWithTag("debug-camera-quality").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("debug-camera-fps").assertIsNotEnabled()
        compose.onNodeWithText("停止預覽").performScrollTo().performClick()
        compose.onNodeWithTag("debug-camera-quality").performScrollTo().assertIsEnabled()
    }

    @Test fun sdkConsoleExposesMetadataSpeechAndStillCaptureControls() {
        var refreshed = false
        var permissionRequested = false
        var stillCaptured = false
        var photoSaved = false
        val stillFrame = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        var spoken = ""
        val tools = mutableStateOf(DebugToolsState())
        val camera = mutableStateOf(DebugCameraState())
        compose.setContent { MeetingTheme {
            MeetingScreen(MeetingUiState(connected = true), false, {}, false, {}, "", {}, "", {}, {}, {}, {}, {}, {}, {},
                debugCameraState = camera.value, debugToolsState = tools.value,
                sdkDiagnostics = SdkDiagnostics(connection = "CONNECTED", preferredLocale = "ja-JP"),
                onDebugRefresh = { refreshed = true }, onDebugPermission = { permissionRequested = it },
                onDebugPhotoQuality = { camera.value = camera.value.copy(photoQuality = it) },
                onDebugStillCapture = { stillCaptured = true; camera.value = camera.value.copy(photoPreview = stillFrame) },
                onDebugStillSave = { photoSaved = true; camera.value = camera.value.copy(photoSaved = true) },
                onDebugTranscribe = { tools.value = tools.value.copy(busy = true, transcribing = true) },
                onDebugToolsStop = { tools.value = DebugToolsState() },
                onDebugSpeak = { text, _ -> spoken = text })
        } }
        compose.onNodeWithText("測試").performClick()
        compose.onNodeWithTag("debug-photo-quality").performScrollTo().performClick()
        compose.onNodeWithText(PhotoSaveQuality.SMALL.label).performClick()
        assertEquals(PhotoSaveQuality.SMALL, camera.value.photoQuality)
        compose.onNodeWithText("拍照預覽").performScrollTo().performClick()
        assertEquals(true, stillCaptured)
        assertEquals(false, photoSaved)
        compose.onNodeWithContentDescription("拍攝照片預覽").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("儲存照片").performScrollTo().performClick()
        assertEquals(true, photoSaved)
        captureForReview("sdk-still-controls.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("連線與 SDK 資訊"))
        compose.onNodeWithText("重新讀取 SDK 狀態").performScrollTo().performClick()
        assertEquals(true, refreshed)
        compose.onNodeWithText("要求相機權限").performScrollTo().performClick()
        assertEquals(true, permissionRequested)
        captureForReview("sdk-metadata-controls.png")
        compose.onNodeWithText("開始 SDK 轉錄").performScrollTo().performClick()
        compose.onNodeWithText("透過 SDK 播報").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("停止 SDK 轉錄").performScrollTo().performClick()
        compose.onNodeWithText("透過 SDK 播報").performScrollTo().performClick()
        assertEquals("這是眼鏡語音測試。", spoken)
        captureForReview("sdk-speech-controls.png")
    }

    @Test fun disconnectedSourceCannotBeginRecording() {
        show(MeetingUiState())
        compose.onNodeWithText("開始會議").assertIsNotEnabled()
        compose.onNodeWithText("眼鏡尚未連線").assertIsDisplayed()
    }

    @Test fun connectedSourceCanSelectAudioAndRecordingExposesStop() {
        var selected: StreamMode? = null
        show(MeetingUiState(connected = true), { selected = it })
        compose.onNodeWithText("開始會議").assertIsEnabled().performClick()
        assertEquals(StreamMode.AUDIO, selected)
    }

    @Test fun activeRecordingShowsStatusAndStopControl() {
        show(MeetingUiState(
            session = SessionSnapshot(SessionPhase.RECORDING, StreamMode.AUDIO, 3),
            connected = true, demo = true, status = "模擬錄製中：合成音訊",
            recordingSinceMs = System.currentTimeMillis() - 65_000))
        compose.onNodeWithText("停止並保存").assertIsEnabled()
        compose.onNodeWithText("記錄中", substring = true).assertIsDisplayed()
        captureForReview("review-recording.png")
    }

    @Test fun homeHidesEngineeringControlsAndTranslationRequiresConfiguredService() {
        show(MeetingUiState(connected = true))
        compose.onNodeWithText("使用模擬音訊").assertDoesNotExist()
        compose.onNodeWithText("電腦服務位址").assertDoesNotExist()
        compose.onNodeWithText("翻譯").performClick()
        compose.onNodeWithText("開始連續翻譯").assertIsNotEnabled()
        compose.onNodeWithText("設定翻譯服務").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("使用本機電腦").assertIsDisplayed()
    }

    @Test fun recordingAMeetingDoesNotOfferAnInactiveAutomaticSpeechSwitch() {
        show(MeetingUiState(session = SessionSnapshot(SessionPhase.RECORDING, StreamMode.AUDIO, 4)))
        compose.onNodeWithText("翻譯").performClick()
        compose.onNodeWithText("自動播報").assertDoesNotExist()
        compose.onNodeWithText("目前正在記錄會議。停止並保存後，可開始連續翻譯與播報。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("停止並保存").assertIsEnabled()
    }

    @Test fun failuresShowRecoveryActionsWithoutOpeningDeveloperDiagnostics() {
        val state = mutableStateOf(MeetingUiState(userIssue = "尚未允許附近裝置權限。", needsSystemSettings = true))
        var settingsOpened = false
        var retry = false
        compose.setContent { MeetingTheme {
            MeetingScreen(state.value, false, {}, false, {}, "", {}, "", {}, { retry = true }, {}, {}, {}, {}, {},
                onOpenSystemSettings = { settingsOpened = true },
                onDismissIssue = { state.value = state.value.copy(userIssue = null) })
        } }
        compose.onNodeWithText("尚未允許附近裝置權限。").assertIsDisplayed()
        compose.onNodeWithText("開啟系統設定").performClick()
        assertEquals(true, settingsOpened)
        captureForReview("review-permission-fixture.png")
        state.value = state.value.copy(userIssue = "連接眼鏡逾時。請確認配戴後重試。", needsSystemSettings = false)
        compose.onNodeWithText("重試連線").performClick()
        assertEquals(true, retry)
        state.value = state.value.copy(connected = true, userIssue = "眼鏡麥克風／相機尚未授權。請在 VIVE Connect 允許。")
        compose.onNodeWithText("開啟 VIVE Connect").assertIsEnabled()
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("眼鏡麥克風／相機尚未授權。", substring = true).assertDoesNotExist()
    }

    @Test fun earlierTranscriptAndTranslationRemainReachableAndReplayTheCorrectSentence() {
        val entries = (0..40).map { TranslatedUtterance("u:$it", "QA source $it", "QA 譯文 $it", "zh-TW", it * 1_000_000L, (it + 1) * 1_000_000L) }
        var replayed: String? = null
        compose.setContent { MeetingTheme {
            MeetingScreen(MeetingUiState(transcript = (0..30).map { "QA 逐字稿 $it" }, utterances = entries),
                false, {}, false, {}, "", {}, "", {}, {}, {}, {}, {}, {}, {}, onReplay = { replayed = it.id })
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("QA 逐字稿 0"))
        compose.onNodeWithText("QA 逐字稿 0").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("QA 逐字稿 30"))
        compose.onNodeWithText("QA 逐字稿 30").assertIsDisplayed()
        compose.onNodeWithText("翻譯").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("QA 譯文 0"))
        compose.onAllNodesWithText("重播")[0].performScrollTo().performClick()
        assertEquals("u:0", replayed)
        captureForReview("review-history-fixture.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("QA 譯文 40"))
        compose.onNodeWithText("QA 譯文 40").assertIsDisplayed()
    }

    @Test fun selectedPhotoShowsTextAndConfirmationAndExclusionUpdateTheCorrectItem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "highlight-ui-test.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("whiteboard_fixture.jpg").use { input ->
            photo.outputStream().use { input.copyTo(it) }
        }
        val item = MeetingHighlight("test-highlight", 3, 5_000_000, photo.absolutePath,
            "Deadline Friday", "OCR", "whiteboard", "會議白板", listOf(0.0, 0.0, 1.0, 1.0), "test")
        val state = mutableStateOf(MeetingUiState(highlights = listOf(item)))
        try {
            compose.setContent {
              MeetingTheme {
                MeetingScreen(state.value, false, {}, false, {}, "", {}, "", {}, {}, {}, {}, {}, {}, {},
                    onHighlightUpdate = { selected, text, confirmed, excluded ->
                        assertEquals(item.id, selected.id)
                        state.value = state.value.copy(highlights = if (excluded) emptyList()
                            else listOf(selected.copy(text = text, confirmed = confirmed)))
                    })
              }
            }
            compose.onNodeWithText("重點").performClick()
            compose.onNodeWithText("Deadline Friday").performScrollTo().assertIsDisplayed()
            captureForReview("review-highlight-fixture.png")
            compose.onNodeWithText("修改文字").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Deadline: Friday; Owner: Alice")
            compose.onNodeWithContentDescription("修正時的來源照片，點選放大").performScrollTo().assertIsDisplayed()
            captureForReview("review-edit-fixture.png")
            compose.onNodeWithContentDescription("修正時的來源照片，點選放大").performClick()
            compose.onNodeWithText("關閉照片").performClick()
            compose.onNodeWithText("取消").performClick()
            assertEquals("Deadline Friday", state.value.highlights.single().text)
            compose.onNodeWithText("修改文字").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Deadline: Friday; Owner: Alice")
            compose.onNodeWithText("儲存並確認").performClick()
            assertEquals(true, state.value.highlights.single().confirmed)
            assertEquals("Deadline: Friday; Owner: Alice", state.value.highlights.single().text)
            compose.onNodeWithText("已確認").assertExists()
            compose.onNodeWithText("排除").performScrollTo().performClick()
            compose.onNodeWithText("排除並刪除照片").performClick()
            compose.onNodeWithText("目前沒有重點照片").assertIsDisplayed()
        } finally { photo.delete() }
    }
}
