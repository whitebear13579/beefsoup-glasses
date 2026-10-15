package com.beefsoup.ai.glass

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
        compose.onNodeWithText("設定翻譯服務").assertIsDisplayed().performClick()
        compose.onNodeWithText("使用本機電腦").assertIsDisplayed()
    }

    @Test fun recordingAMeetingDoesNotOfferAnInactiveAutomaticSpeechSwitch() {
        show(MeetingUiState(session = SessionSnapshot(SessionPhase.RECORDING, StreamMode.AUDIO, 4)))
        compose.onNodeWithText("翻譯").performClick()
        compose.onNodeWithText("自動播報").assertDoesNotExist()
        compose.onNodeWithText("目前正在記錄會議。停止並保存後，可開始連續翻譯與播報。").assertIsDisplayed()
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
        compose.onAllNodesWithText("重播")[0].performClick()
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
