package com.beefsoup.ai.glass

import android.app.Activity
import android.media.MediaExtractor
import androidx.test.platform.app.InstrumentationRegistry
import com.htc.viveglass.sdk.*
import com.htc.viveglass.sdk.client.ViveGlassClientCallback
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SdkConsoleTest {
    private class FakeSdk : ViveGlass() {
        lateinit var callback: ViveGlassClientCallback
        var capturedQuality: ImageQuality? = null
        var speakCalls = 0
        var transcriptionCalls = 0
        override fun connect(callback: ViveGlassClientCallback) { this.callback = callback; callback.onConnectionStateChanged(ConnectionState.CONNECTED) }
        override fun checkPermission(permission: Permission) = PermissionResult.GRANT
        override fun isConnected() = true
        override fun isAudioStreaming() = false
        override fun isVideoStreaming() = false
        override fun getUserPreferredLocale() = Locale.JAPAN
        override fun captureImage(quality: ImageQuality): Int { capturedQuality = quality; return 0 }
        override fun speakText(text: String, locale: Locale): Int { speakCalls++; return 0 }
        override fun startTranscription(continuous: Boolean): Int { transcriptionCalls++; return 0 }
        override fun stopTranscription() = Unit
    }

    @Test fun adapterExposesMetadataEventsAndSerializesStillCapture() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sdk = FakeSdk()
        val link = HtcGlassLink(context, sdk)
        link.connect(); link.refreshDiagnostics()
        assertEquals("ja-JP", link.diagnostics.value.preferredLocale)
        assertEquals("GRANT", link.diagnostics.value.cameraPermission)
        val first = async { link.capturePhoto() }
        yield()
        assertEquals(ImageQuality.DEFAULT, sdk.capturedQuality)
        try { link.capturePhoto(); fail("overlapping capture accepted") } catch (_: IllegalStateException) { }
        sdk.callback.onImageCaptured(CaptureEvent.SUCCESS, ImagePayload(3, byteArrayOf(1, 2, 3, 4)))
        assertArrayEquals(byteArrayOf(1, 2, 3), first.await())
        assertTrue(link.transcribe(true))
        sdk.callback.onSpeechTranscribed(TranscribedEvent.SUCCESS, "SDK fixture")
        assertTrue(link.diagnostics.value.transcription.contains("SDK fixture"))
        link.stopTranscription()
        sdk.callback.onSpeechTranscribed(TranscribedEvent.SUCCESS, "late text")
        assertFalse(link.diagnostics.value.transcription.contains("late text"))
        assertTrue(link.speak("Test", Locale.US))
        assertTrue(link.diagnostics.value.speaking)
        sdk.callback.onTextSpoken(SynthesisEvent.SUCCESS)
        assertFalse(link.diagnostics.value.speaking)
        repeat(50) { sdk.callback.onKeyEvent(KeyEvent.AIBUTTON) }
        assertEquals(40, link.diagnostics.value.events.size)
        link.clearDiagnostics()
        assertTrue(link.diagnostics.value.events.isEmpty())
    }

    @Test fun toolsReserveOperationsAndStopTranscriptionOnExit() = runBlocking {
        val sdk = FakeSdk()
        val link = HtcGlassLink(InstrumentationRegistry.getInstrumentation().targetContext, sdk)
        link.connect()
        val tools = DebugSdkTools(InstrumentationRegistry.getInstrumentation().targetContext, { link }, { true })
        withContext(Dispatchers.Main) {
            tools.startTranscription(Activity())
            assertTrue(tools.state.value.busy)
            tools.speak("blocked", "en-US")
            assertEquals(0, sdk.speakCalls)
            tools.stop()
            assertFalse(link.diagnostics.value.transcribing)
            assertFalse(tools.state.value.busy)
            tools.speak("allowed", "en-US")
            assertEquals(1, sdk.speakCalls)
            sdk.callback.onTextSpoken(SynthesisEvent.SUCCESS)
        }
    }

    @Test fun audioDiagnosticSavesPlayableSyntheticAudioOutsideMeetings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val link = DemoGlassLink().apply { connect() }
        val tools = DebugSdkTools(context, { link }, { true })
        try {
            withContext(Dispatchers.Main) { tools.startAudio(Activity()) }
            withTimeout(10_000) { while (tools.state.value.audioPackets < 10) delay(100) }
            withContext(Dispatchers.Main) { tools.stop() }
            withTimeout(5_000) { while (tools.state.value.busy) delay(50) }
            assertTrue(tools.state.value.files.isNotEmpty())
            val file = tools.state.value.files.first()
            assertTrue(file.contains("/debug-audio/"))
            val extractor = MediaExtractor()
            try { extractor.setDataSource(file); assertTrue(extractor.trackCount > 0) }
            finally { extractor.release() }
        } finally {
            withContext(Dispatchers.Main) { tools.stop() }
            withTimeout(5_000) { while (tools.state.value.busy) delay(50) }
            withContext(Dispatchers.Main) { tools.deleteAudio() }
            link.disconnect()
        }
    }
}
