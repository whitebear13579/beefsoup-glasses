package edu.ncku.aiglass

import org.junit.Assert.*
import org.junit.Test

class TranscriptTimelineTest {
    @Test fun lateRepliesAreOrderedAndDuplicateSequenceIgnored() {
        val timeline = TranscriptTimeline()
        assertTrue(timeline.accept(InferenceResult(2, 7_000_000, "明天交件即可", "submit tomorrow", "fake")))
        assertTrue(timeline.accept(InferenceResult(1, 3_500_000, "會議決定明天交件", "meeting decided", "fake")))
        assertFalse(timeline.accept(InferenceResult(1, 3_500_000, "wrong", "wrong", "fake")))
        assertEquals(listOf("會議決定明天交件\nmeeting decided", "即可\nsubmit tomorrow"), timeline.display())
    }

    @Test fun shortRepeatedPhraseIsNotRemovedAsSpeechMayReallyRepeat() {
        val timeline = TranscriptTimeline()
        timeline.accept(InferenceResult(0, 0, "好", "", "fake"))
        timeline.accept(InferenceResult(1, 3_500_000, "好", "", "fake"))
        assertEquals(listOf("好", "好"), timeline.display())
    }
}
