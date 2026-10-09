package com.beefsoup.ai.glass

import java.util.TreeMap

/** Orders asynchronous inference replies and trims exact speech from adjacent overlap windows. */
class TranscriptTimeline {
    private val results = TreeMap<Long, InferenceResult>()

    fun accept(result: InferenceResult): Boolean {
        if (results.containsKey(result.sequence)) return false
        results[result.sequence] = result
        return true
    }

    fun display(): List<String> {
        var prior = ""
        return results.values.map { result ->
            val source = trimOverlap(prior, result.transcript.trim())
            prior += source
            listOf(source, result.translation.trim()).filter { it.isNotBlank() }.joinToString("\n")
        }.filter { it.isNotBlank() }
    }

    private fun trimOverlap(previous: String, current: String): String {
        val max = minOf(previous.length, current.length, 100)
        for (length in max downTo 4) {
            if (previous.takeLast(length).equals(current.take(length), ignoreCase = true)) {
                return current.drop(length).trimStart()
            }
        }
        return current
    }
}
