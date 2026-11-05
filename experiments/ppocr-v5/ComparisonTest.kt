package com.paddle.ocr.experiment

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComparisonTest {
    @Test fun recognizeImages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("OpenCV did not initialize", OpenCVUtils.init(context))
        val initStart = SystemClock.elapsedRealtime()
        val ocr = PaddleOCR.create(context, PaddleOCRConfig(), EngineConfig(numThreads = 2))
        val initMs = SystemClock.elapsedRealtime() - initStart
        try {
            for (name in listOf("user-crop.jpg", "whiteboard.jpg", "user-full.png", "whiteboard-full.jpg")) {
                val bytes = instrumentation.context.assets.open(name).use { it.readBytes() }
                val start = SystemClock.elapsedRealtime()
                val result = ocr.recognize(bytes)
                val wallMs = SystemClock.elapsedRealtime() - start
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                val report = JSONObject().apply {
                    put("image", name)
                    put("initMs", initMs)
                    put("coldLoadMs", ocr.coldLoadTimeMs)
                    put("wallMs", wallMs)
                    put("pipelineMs", result.totalTimeMs)
                    put("detectionMs", result.detectionTimeMs)
                    put("recognitionMs", result.recognitionTimeMs)
                    put("pssKb", memory.totalPss)
                    put("text", result.results.joinToString("\n") { it.text })
                }.toString()
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("ocr_report_base64", Base64.encodeToString(report.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                })
                assertTrue("No text detected in $name", result.results.isNotEmpty())
            }
        } finally {
            ocr.release()
        }
    }
}
