package com.beefsoup.ai.glass

data class TranslatedUtterance(val id: String, val source: String, val translation: String,
    val language: String, val startUs: Long, val endUs: Long)

/** Deduplicates transport retries and bounds how far spoken translation can lag. */
class TranslationQueue(private val capacity: Int = 3) {
    private val pending = ArrayDeque<TranslatedUtterance>()
    private val seen = mutableSetOf<String>()
    init { require(capacity > 0) }

    @Synchronized fun offer(item: TranslatedUtterance): Boolean {
        if (item.translation.isBlank() || !seen.add(item.id)) return false
        if (pending.size >= capacity) return false
        pending.addLast(item)
        return true
    }
    @Synchronized fun next(): TranslatedUtterance? = pending.removeFirstOrNull()
    @Synchronized fun clear() { pending.clear() }
    @Synchronized fun reset() { pending.clear(); seen.clear() }
}
