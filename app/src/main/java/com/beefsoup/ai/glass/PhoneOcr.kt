package com.beefsoup.ai.glass

import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.runBlocking

/** Used only by the video OCR worker; model creation and release stay off the decoder thread. */
internal class PhoneOcr(private val context: Context) {
    private var model: PaddleOCR? = null

    fun recognize(jpeg: ByteArray): String = runBlocking {
        val current = model ?: run {
            check(OpenCVUtils.init(context)) { "無法載入影像處理程式" }
            PaddleOCR.create(context, PaddleOCRConfig(), EngineConfig(numThreads = 2)).also { model = it }
        }
        current.recognize(jpeg).results.joinToString("\n") { it.text }.trim().take(4000)
    }

    fun close() = runBlocking {
        model?.release()
        model = null
    }
}
