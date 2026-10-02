package edu.ncku.aiglass

enum class StreamMode { AUDIO, AUDIO_VIDEO }

enum class SessionPhase { IDLE, STARTING, RECORDING, STOPPING, READY, ERROR }

data class SessionSnapshot(
    val phase: SessionPhase,
    val mode: StreamMode?,
    val epoch: Long,
    val error: String? = null,
)

/** Serializes SDK stream callbacks and rejects callbacks belonging to an older stream. */
class SessionMachine {
    private var current = SessionSnapshot(SessionPhase.IDLE, null, 0)

    @Synchronized fun snapshot(): SessionSnapshot = current

    @Synchronized fun begin(mode: StreamMode): Long? {
        if (current.phase !in setOf(SessionPhase.IDLE, SessionPhase.READY, SessionPhase.ERROR)) return null
        current = SessionSnapshot(SessionPhase.STARTING, mode, current.epoch + 1)
        return current.epoch
    }

    @Synchronized fun started(epoch: Long): Boolean {
        if (epoch != current.epoch || current.phase != SessionPhase.STARTING) return false
        current = current.copy(phase = SessionPhase.RECORDING)
        return true
    }

    @Synchronized fun stopping(): Long? {
        if (current.phase !in setOf(SessionPhase.STARTING, SessionPhase.RECORDING)) return null
        current = current.copy(phase = SessionPhase.STOPPING)
        return current.epoch
    }

    @Synchronized fun stopped(epoch: Long): Boolean {
        if (epoch != current.epoch || current.phase != SessionPhase.STOPPING) return false
        current = current.copy(phase = SessionPhase.READY, mode = null)
        return true
    }

    @Synchronized fun fail(epoch: Long, reason: String): Boolean {
        if (epoch != current.epoch || current.phase in setOf(SessionPhase.IDLE, SessionPhase.READY)) return false
        current = current.copy(phase = SessionPhase.ERROR, mode = null, error = reason)
        return true
    }
}

