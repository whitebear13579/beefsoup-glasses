package edu.ncku.aiglass

import org.junit.Assert.*
import org.junit.Test

class SessionMachineTest {
    @Test fun startStopAndLateCallbacks() {
        val machine = SessionMachine()
        val first = machine.begin(StreamMode.AUDIO)!!
        assertNull(machine.begin(StreamMode.AUDIO_VIDEO))
        assertFalse(machine.started(first - 1))
        assertTrue(machine.started(first))
        assertEquals(SessionPhase.RECORDING, machine.snapshot().phase)
        assertEquals(first, machine.stopping())
        assertFalse(machine.started(first))
        assertTrue(machine.stopped(first))
        assertEquals(SessionPhase.READY, machine.snapshot().phase)
        val second = machine.begin(StreamMode.AUDIO_VIDEO)!!
        assertFalse(machine.fail(first, "late error"))
        assertEquals(second, machine.snapshot().epoch)
        assertEquals(StreamMode.AUDIO_VIDEO, machine.snapshot().mode)
    }

    @Test fun errorCanRestartWithNewEpoch() {
        val machine = SessionMachine()
        val epoch = machine.begin(StreamMode.AUDIO)!!
        assertTrue(machine.fail(epoch, "permission revoked"))
        assertEquals("permission revoked", machine.snapshot().error)
        assertNull(machine.stopping())
        assertEquals(epoch + 1, machine.begin(StreamMode.AUDIO))
        assertNull(machine.snapshot().error)
    }
}
