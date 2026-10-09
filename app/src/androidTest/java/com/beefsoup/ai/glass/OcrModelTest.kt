package com.beefsoup.ai.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OcrModelTest {
    @Test fun bundledRecognizerReadsLargeLatinMeetingText() {
        val bitmap = Bitmap.createBitmap(900, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("MEETING 123", 30f, 170f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 110f
            typeface = Typeface.DEFAULT_BOLD
        })
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                30, TimeUnit.SECONDS)
            assertTrue("OCR returned: ${result.text}", result.text.contains("MEETING"))
            assertTrue("OCR returned: ${result.text}", result.text.contains("123"))
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}
