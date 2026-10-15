package com.beefsoup.ai.glass

import android.util.Base64
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class VisualFrame(val ptsUs: Long, val text: String, val jpeg: ByteArray, val manual: Boolean = false)
data class VisualReview(val keep: Boolean, val text: String, val category: String,
    val reason: String, val roi: List<Double>, val model: String)

/** One frame in flight, no persistent queue of unrelated camera images. */
class VisionReviewer(private val settings: OffloadSettings, private val sessionId: String,
    private val epoch: Long, private val onReview: (VisualFrame, VisualReview) -> Unit,
    private val onIssue: (Long, String) -> Unit) {
    private val client = OkHttpClient.Builder().callTimeout(55, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var call: Call? = null

    fun offer(frame: VisualFrame, context: String): Boolean {
        if (closed || !busy.compareAndSet(false, true)) return false
        try { worker.execute {
            try {
                val endpoint = InferenceProtocol.validateEndpoint(settings.endpoint, BuildConfig.DEBUG)
                    .replaceFirst("ws", "http").removeSuffix("/ws") + "/vision/review"
                val body = JSONObject().put("sessionId", sessionId).put("epoch", epoch)
                    .put("ptsUs", frame.ptsUs).put("ocrText", frame.text.take(4000))
                    .put("context", context.takeLast(4000))
                    .put("jpegBase64", Base64.encodeToString(frame.jpeg, Base64.NO_WRAP))
                val request = Request.Builder().url(endpoint).header("Authorization", "Bearer ${settings.token}")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                call = client.newCall(request)
                call!!.execute().use { response ->
                    check(response.isSuccessful) { "視覺服務暫時無法處理（${response.code}）" }
                    val source = response.body?.source() ?: error("視覺回應空白")
                    check(!source.request(32_769)) { "視覺回應過長" }
                    val json = JSONObject(source.readUtf8())
                    require(json.getString("sessionId") == sessionId && json.getLong("epoch") == epoch &&
                        json.getLong("ptsUs") == frame.ptsUs) { "視覺回應不屬於目前片段" }
                    val roi = json.getJSONArray("roi").let { a ->
                        require(a.length() == 4)
                        (0..3).map { a.getDouble(it) }
                    }
                    require(roi.all { it.isFinite() && it in 0.0..1.0 } && roi[0] < roi[2] && roi[1] < roi[3])
                    val category = json.getString("category")
                    val text = json.getString("text")
                    require(text.length <= 4000)
                    val keep = json.getBoolean("keep") && !json.getBoolean("uncertain") &&
                        category in setOf("whiteboard", "slide", "document") && text.isNotBlank()
                    if (!closed) onReview(frame, VisualReview(keep, text, category,
                        json.getString("reason").take(500), roi, json.optString("model")))
                }
            } catch (error: Exception) {
                if (!closed) onIssue(frame.ptsUs, error.message ?: "重點辨識暫時無法使用；音訊仍持續保存")
            } finally {
                call = null
                busy.set(false)
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { busy.set(false); return false }
        return true
    }

    fun close() {
        closed = true
        call?.cancel()
        worker.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
