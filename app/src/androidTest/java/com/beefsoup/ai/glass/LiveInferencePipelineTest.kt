package com.beefsoup.ai.glass

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit real-model gate; provision private test-gateway.json before running it. */
class LiveInferencePipelineTest {
    @Test fun phoneSendsKnownPcmToRealWhisperAndLlmAndKeepsCommittedTranslations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = File(context.filesDir, "test-gateway.json")
        assumeTrue("Real gateway not provisioned; see product validation instructions", config.isFile)
        val settings = JSONObject(config.readText())
        val pcm = instrumentation.context.assets.open("known_english.pcm").use { it.readBytes() }
        val store = MeetingStore.create(context)
        val chunks = mutableListOf<PcmChunk>()
        PcmChunker({ chunks.add(it) }).apply { append(pcm, 0); finish() }
        chunks.forEach { store.savePending(it) }
        val finished = CountDownLatch(1)
        val utterances = mutableListOf<TranslatedUtterance>()
        val results = mutableListOf<InferenceResult>()
        val statuses = mutableListOf<String>()
        val client = RemoteInferenceClient(store, 1,
            onResult = { results.add(it) }, onSummary = { finished.countDown() },
            onStatus = { statuses.add(it) }, targetLanguage = "zh-TW", continuousTranslation = true,
            onUtterance = { utterances.add(it) })
        try {
            client.connect(settings.getString("endpoint"), settings.getString("token"), true)
            client.finish()
            assertTrue("Live gateway did not complete; statuses=$statuses", finished.await(120, TimeUnit.SECONDS))
            assertEquals(chunks.size, results.size)
            assertEquals(0, store.pendingCount())
            assertTrue(utterances.isNotEmpty())
            assertEquals(utterances.size, utterances.map { it.id }.distinct().size)
            val source = utterances.joinToString(" ") { it.source }
            val translation = utterances.joinToString(" ") { it.translation }
            assertTrue("Unexpected source: $source", source.contains("project", ignoreCase = true))
            assertTrue("Translation lost deadline/action: $translation", translation.contains("明天") && translation.contains("完成"))
            File(context.cacheDir, "live-gateway-translation.json").writeText(JSONObject()
                .put("source", source).put("translation", translation).put("language", "zh-TW")
                .put("model", results.first().model).put("chunks", chunks.size)
                .put("utterances", utterances.size).put("transport", "phone WebSocket to real local gateway").toString())
        } finally {
            client.close()
            store.delete(context)
        }
    }
}
