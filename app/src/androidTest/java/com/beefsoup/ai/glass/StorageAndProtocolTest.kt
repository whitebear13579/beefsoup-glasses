package com.beefsoup.ai.glass

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageAndProtocolTest {
    @Test fun pendingAudioSurvivesReadAndOnlyAcceptedResultDeletesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MeetingStore.create(context)
        val chunk = PcmChunk(7, 5_000_000, byteArrayOf(1, 0, 2, 0))
        store.savePending(chunk)
        assertTrue(store.hasPending(7, 5_000_000))
        assertArrayEquals(chunk.pcm16, store.pendingChunks().single().pcm16)
        assertFalse(store.hasPending(7, 5_000_001))
        store.event("health", JSONObject().put("reason", "test gap"))
        assertTrue(store.exportText().contains("test gap"))
        store.acknowledge(7)
        assertTrue(store.pendingChunks().isEmpty())
    }

    @Test fun inferenceResponseRejectsCrossSessionAndInvalidEndpoint() {
        val id = "71fe118f-2830-4d23-9268-1c66d6de3c93"
        val valid = JSONObject().put("type", "result").put("sessionId", id).put("epoch", 2)
            .put("sequence", 0).put("startUs", 100).put("transcript", "hello")
            .put("translation", "你好")
        assertEquals("hello", InferenceProtocol.parseResult(valid.toString(), id, 2)?.transcript)
        assertNull(InferenceProtocol.parseResult(valid.toString(), id, 3))
        assertNull(InferenceProtocol.parseResult(valid.toString(), "other", 2))
        valid.put("startUs", -1)
        assertThrows(IllegalArgumentException::class.java) {
            InferenceProtocol.parseResult(valid.toString(), id, 2)
        }
        assertEquals("ws://10.0.2.2:8765/ws",
            InferenceProtocol.validateEndpoint("ws://10.0.2.2:8765/ws", true))
        assertThrows(IllegalArgumentException::class.java) {
            InferenceProtocol.validateEndpoint("ws://10.0.2.2:8765/ws", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InferenceProtocol.validateEndpoint("ws://192.168.1.2:8765/ws", true)
        }
    }

    @Test fun deleteRemovesAudioAndPendingPrivateData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MeetingStore.create(context)
        store.savePending(PcmChunk(0, 0, byteArrayOf(1, 0)))
        val folder = store.directory
        assertTrue(folder.exists())
        store.delete(context)
        assertFalse(folder.exists())
    }

    @Test fun persistedSessionCanBeReopenedWithEpochAndPendingAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val created = MeetingStore.create(context)
        try {
            created.event("start", JSONObject().put("epoch", 5).put("mode", "AUDIO"))
            created.savePending(PcmChunk(2, 7_000_000, byteArrayOf(2, 0)))
            val restored = MeetingStore.latest(context)
            assertNotNull(restored)
            assertEquals(created.id, restored!!.id)
            assertEquals(5L, restored.startEpoch())
            assertEquals(1, restored.pendingCount())
            assertFalse(restored.stoppedCleanly())
        } finally {
            created.delete(context)
        }
    }
}
