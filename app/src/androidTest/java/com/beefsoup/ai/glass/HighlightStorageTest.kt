package com.beefsoup.ai.glass

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class HighlightStorageTest {
    @Test fun largerDetailedPhotoIsPreservedWithoutRemovingTheSizeLimit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = MeetingStore.create(context)
        try {
            val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
            val random = java.util.Random(42)
            val pixels = IntArray(1024 * 1024) { random.nextInt() or -0x1000000 }
            bitmap.setPixels(pixels, 0, 1024, 0, 0, 1024, 1024)
            val output = ByteArrayOutputStream()
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            bitmap.recycle()
            val jpeg = output.toByteArray()
            assertTrue("Expected JPEG between 512 kB and 2 MB: ${jpeg.size}", jpeg.size in 512_001..2_000_000)
            val review = VisualReview(true, "", "document", "manual", listOf(0.0, 0.0, 1.0, 1.0), "pp-ocrv5-mobile")
            val item = store.saveHighlight(0, VisualFrame(0, "", jpeg, manual = true), review)
            assertArrayEquals(jpeg, File(item.imagePath).readBytes())
            assertThrows(IllegalArgumentException::class.java) {
                store.saveHighlight(1, VisualFrame(1, "", ByteArray(2_000_001), manual = true), review)
            }
        } finally { store.delete(context) }
    }

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
