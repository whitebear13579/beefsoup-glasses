package com.beefsoup.ai.glass

import android.media.MediaExtractor
import android.media.MediaFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechOutputTest {
    @Test fun actualWhisperAndLlmTranslationSynthesizesToPlayablePhoneAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = if (InstrumentationRegistry.getArguments().getString("liveResult") == "true")
            JSONObject(File(context.cacheDir, "live-gateway-translation.json").readText())
        else JSONObject(instrumentation.context.assets.open("translation_fixture.json").bufferedReader().use { it.readText() })
        val initialized = CountDownLatch(1)
        val done = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        var synthesisError = false
        lateinit var tts: TextToSpeech
        var engineCreated = false
        val output = File(context.cacheDir, "verified-translation.wav")
        try {
            instrumentation.runOnMainSync {
                tts = TextToSpeech(context) { status -> initStatus = status; initialized.countDown() }
                engineCreated = true
            }
            assertTrue("TTS initialization timed out", initialized.await(20, TimeUnit.SECONDS))
            assertEquals("TTS engine unavailable", TextToSpeech.SUCCESS, initStatus)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("Legacy callback") override fun onError(id: String?) { synthesisError = true; done.countDown() }
                override fun onError(id: String?, code: Int) { synthesisError = true; done.countDown() }
            })
            val locale = Locale.forLanguageTag(fixture.getString("language"))
            val offline = tts.voices.filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
                .sortedByDescending { it.locale.country == locale.country }.firstOrNull()
            assertNotNull("No installed offline voice for ${locale.toLanguageTag()}", offline)
            tts.voice = offline
            assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(fixture.getString("translation"), null, output, "verified"))
            assertTrue("TTS synthesis timed out", done.await(30, TimeUnit.SECONDS))
            assertFalse("TTS reported an error", synthesisError)
            assertTrue("No synthesized waveform", output.length() > 1000)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                val format = extractor.getTrackFormat(0)
                assertTrue(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) > 0)
                extractor.selectTrack(0)
                val buffer = ByteBuffer.allocate(262144)
                var total = 0
                var nonZero = false
                while (true) {
                    buffer.clear()
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) break
                    total += read
                    for (i in 0 until read) if (buffer.get(i) != 0.toByte()) { nonZero = true; break }
                    extractor.advance()
                }
                assertTrue("Synthesized audio has no samples", total > 1000)
                assertTrue("Synthesized audio is entirely silent", nonZero)
            } finally { extractor.release() }
            File(context.cacheDir, "tts-verification.json").writeText(JSONObject()
                .put("voice", offline!!.name).put("language", offline.locale.toLanguageTag())
                .put("bytes", output.length()).put("source", fixture.getString("source"))
                .put("translation", fixture.getString("translation")).toString())
        } finally {
            if (engineCreated) tts.shutdown()
        }
    }
}
