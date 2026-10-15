package com.beefsoup.ai.glass

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class HighlightStorageTest {
    @Test fun selectedPhotoSurvivesReloadOnlyConfirmedTextIsEvidenceAndExclusionDeletesPhoto() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MeetingStore.create(context)
        try {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
            bitmap.recycle()
            val frame = VisualFrame(2_000_000, "Raw OCR with bottle label", bytes.toByteArray())
            val review = VisualReview(true, "Deadline Friday", "whiteboard", "會議白板", listOf(0.0, 0.0, 0.8, 1.0), "test")
            val item = store.saveHighlight(0, frame, review)
            assertTrue(File(item.imagePath).length() > 0)
            assertEquals(item, store.highlights().single())
            assertTrue(store.confirmedHighlightObservations().isEmpty())
            store.updateHighlight(item.id, "Deadline Thursday", true, false)
            assertEquals("Deadline Thursday", store.confirmedHighlightObservations().single().second.text)
            assertEquals(frame.text, store.highlights().single().rawText)
            store.updateHighlight(item.id, "Deadline Thursday", false, true)
            assertTrue(store.confirmedHighlightObservations().isEmpty())
            assertFalse(File(item.imagePath).exists())
            assertTrue(store.highlights().single().excluded)
            assertTrue(File(store.directory, "images").listFiles().orEmpty().none { it.name.endsWith(".partial") })
        } finally { store.delete(context) }
    }
}
