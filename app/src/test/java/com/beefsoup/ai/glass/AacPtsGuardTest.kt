package com.beefsoup.ai.glass

import org.junit.Assert.assertThrows
import org.junit.Test

class AacPtsGuardTest {
    @Test fun acceptsRegularAacFramesButRejectsMissingOrReversedTime() {
        val guard = AacPtsGuard()
        guard.accept(0, 16000)
        guard.accept(64_000, 16000)
        guard.accept(128_100, 16000)
        assertThrows(IllegalArgumentException::class.java) { guard.accept(500_000, 16000) }
        assertThrows(IllegalArgumentException::class.java) { guard.accept(120_000, 16000) }
    }
}
