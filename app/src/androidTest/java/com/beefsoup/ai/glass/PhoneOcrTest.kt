package com.beefsoup.ai.glass

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.util.Base64
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneOcrTest {
    @Test fun optionalCapturedFrameUsesBundledModels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val image = java.io.File(context.getExternalFilesDir(null), "ppocr-reference.jpg")
        assumeTrue("No optional reference image", image.isFile)
        val ocr = PhoneOcr(context)
        try {
            val text = ocr.recognize(image.readBytes())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("ocr_text_base64", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            })
            assertTrue("No text detected", text.isNotBlank())
        } finally { ocr.close() }
    }

    @Test fun bundledModelsRecognizeWhiteboardWithoutServer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val jpeg = instrumentation.context.assets.open("whiteboard_fixture.jpg").use { it.readBytes() }
        val ocr = PhoneOcr(instrumentation.targetContext)
        try {
            val text = ocr.recognize(jpeg)
            for (expected in listOf("PROJECT REVIEW", "Deadline: Friday", "Owner: Alice", "Action: review the design")) {
                assertTrue("Missing $expected in $text", text.contains(expected, ignoreCase = true))
            }
        } finally { ocr.close() }
    }
}
