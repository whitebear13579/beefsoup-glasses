package edu.ncku.aiglass

/** Marks discontinuous AAC timing instead of silently presenting a complete recording. */
class AacPtsGuard {
    private var previousUs: Long? = null

    fun accept(ptsUs: Long, sampleRate: Int) {
        require(ptsUs >= 0 && sampleRate > 0)
        val previous = previousUs
        if (previous != null) {
            val delta = ptsUs - previous
            val frameUs = 1024L * 1_000_000 / sampleRate
            require(delta > 0 && delta <= frameUs * 3) {
                "AAC 時間戳不連續：相鄰封包差 ${delta}µs"
            }
        }
        previousUs = ptsUs
    }
}
