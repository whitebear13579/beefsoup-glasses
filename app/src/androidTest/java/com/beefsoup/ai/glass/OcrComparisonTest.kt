package com.beefsoup.ai.glass

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OcrComparisonTest {
    @Test fun whiteboardFixture() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = context.assets.open("whiteboard_fixture.jpg").use { BitmapFactory.decodeStream(it) }
        val text = recognize("whiteboard", bitmap)
        for (expected in listOf("PROJECT REVIEW", "Deadline", "Friday", "Owner", "Alice", "Action")) {
            assertTrue("Missing $expected in $text", text.contains(expected, ignoreCase = true))
        }
    }

    @Test fun optionalUserImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "ocr-evaluation.png")
        assumeTrue("Optional user image not installed", file.isFile)
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.absolutePath))
        assertTrue(recognize("user_image", bitmap).isNotBlank())
    }

    private fun recognize(name: String, bitmap: android.graphics.Bitmap): String {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val start = SystemClock.elapsedRealtime()
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("ocr_case", name)
                putString("ocr_elapsed_ms", (SystemClock.elapsedRealtime() - start).toString())
                putString("ocr_text_base64", Base64.encodeToString(result.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            })
            return result.text
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}
