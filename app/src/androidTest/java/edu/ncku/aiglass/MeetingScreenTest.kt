package edu.ncku.aiglass

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MeetingScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: MeetingUiState, onStart: (StreamMode) -> Unit = {}) {
        compose.setContent {
            MeetingScreen(state, false, {}, false, {}, "", {}, "", {}, {}, onStart, {}, {}, {}, {})
        }
    }

    @Test fun disconnectedSourceCannotBeginRecording() {
        show(MeetingUiState())
        compose.onNodeWithText("開始音訊會議").assertIsNotEnabled()
        compose.onNodeWithText("來源尚未連線").assertIsDisplayed()
    }

    @Test fun connectedSourceCanSelectAudioAndRecordingExposesStop() {
        var selected: StreamMode? = null
        show(MeetingUiState(connected = true), { selected = it })
        compose.onNodeWithText("開始音訊會議").assertIsEnabled().performClick()
        assertEquals(StreamMode.AUDIO, selected)
    }

    @Test fun activeRecordingShowsStatusAndStopControl() {
        show(MeetingUiState(
            session = SessionSnapshot(SessionPhase.RECORDING, StreamMode.AUDIO, 3),
            connected = true, status = "模擬錄製中：合成音訊"))
        compose.onNodeWithText("停止並保存會議").assertIsEnabled()
        compose.onNodeWithText("模擬錄製中：合成音訊").assertIsDisplayed()
    }
}
