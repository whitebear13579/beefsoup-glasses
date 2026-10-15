package com.beefsoup.ai.glass

/** Readability/stability gate only. Semantic importance is decided by the vision model. */
class VisualCandidateGate {
    private var previous = ""
    private var lastPtsUs = -1L
    private var lastSentUs = -10_000_000L
    private var lastSentText = ""
    private var previousLuma: ByteArray? = null
    private var lastSentLuma: ByteArray? = null

    fun offer(ptsUs: Long, text: String, luma: ByteArray? = null): Boolean {
        if (ptsUs < 0 || ptsUs <= lastPtsUs) return false
        lastPtsUs = ptsUs
        val normalized = text.filter { it.isLetterOrDigit() }.lowercase().take(4000)
        val stableText = normalized.length >= 4 && similarity(previous, normalized) >= 0.65
        val stableScene = sameScene(previousLuma, luma)
        previous = normalized
        previousLuma = luma?.copyOf()
        val duplicate = if (stableText) similarity(lastSentText, normalized) >= 0.9 else sameScene(lastSentLuma, luma)
        if ((!stableText && !stableScene) || ptsUs - lastSentUs < 8_000_000L || duplicate) return false
        lastSentUs = ptsUs
        lastSentText = normalized
        lastSentLuma = luma?.copyOf()
        return true
    }

    /** Keep throttling, but allow a failed/dropped request to retry the unchanged scene. */
    fun retry() { lastSentText = ""; lastSentLuma = null }

    private fun sameScene(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null || a.size != 256 || b.size != 256) return false
        if (b.maxOf { it.toInt() and 255 } - b.minOf { it.toInt() and 255 } < 30) return false
        val brightness = a.indices.sumOf { (a[it].toInt() and 255) - (b[it].toInt() and 255) } / 256.0
        return a.indices.sumOf { kotlin.math.abs((a[it].toInt() and 255) - (b[it].toInt() and 255) - brightness) } / 256 <= 12
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val left = a.windowed(2).toSet()
        val right = b.windowed(2).toSet()
        return left.intersect(right).size.toDouble() / left.union(right).size.coerceAtLeast(1)
    }
}
