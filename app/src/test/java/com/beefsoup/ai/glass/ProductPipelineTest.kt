package com.beefsoup.ai.glass

import org.junit.Assert.*
import org.junit.Test

class ProductPipelineTest {
    @Test fun visualCandidatesNeedStabilityAndDoNotRepeatedlySendUnchangedText() {
        val gate = VisualCandidateGate()
        assertFalse(gate.offer(0, "Deadline Friday Owner Alice"))
        assertTrue(gate.offer(1_000_000, "Deadline Friday Owner Alice"))
        assertFalse(gate.offer(9_000_000, "Deadline Friday Owner Alice"))
        assertFalse(gate.offer(10_000_000, "預算審查：費用與人力配置"))
        assertTrue(gate.offer(11_000_000, "預算審查：費用與人力配置"))
        assertFalse(gate.offer(11_000_000, "預算審查：費用與人力配置"))
        assertFalse(gate.offer(-1, "其他內容"))
    }

    @Test fun speechQueueBoundsLagDeduplicatesRetryAndMuteDoesNotReplayOldItems() {
        val queue = TranslationQueue(2)
        fun item(id: String) = TranslatedUtterance(id, "Source", "譯文", "zh-TW", 0, 1_000_000)
        assertTrue(queue.offer(item("u:0")))
        assertFalse(queue.offer(item("u:0")))
        assertTrue(queue.offer(item("u:1")))
        assertFalse(queue.offer(item("u:2")))
        assertEquals("u:0", queue.next()?.id)
        queue.clear()
        assertNull(queue.next())
        assertFalse(queue.offer(item("u:1")))
        queue.reset()
        assertTrue(queue.offer(item("u:0")))
    }

    @Test fun aStableSceneCanReachVisionWithoutSuccessfulPhoneOcrAndFailuresCanRetry() {
        val gate = VisualCandidateGate()
        val scene = ByteArray(256) { if (it % 3 == 0) 35 else 190.toByte() }
        assertFalse(gate.offer(0, "", scene))
        assertTrue(gate.offer(1_000_000, "", scene))
        assertFalse(gate.offer(9_000_000, "", scene))
        gate.retry()
        assertTrue(gate.offer(10_000_000, "", scene))
        gate.retry()
        assertFalse(gate.offer(11_000_000, "", scene))
        val moving = ByteArray(256) { if (it % 3 == 0) 190.toByte() else 35 }
        assertFalse(gate.offer(19_000_000, "", moving))
        assertTrue(gate.offer(20_000_000, "", moving))
    }
}
