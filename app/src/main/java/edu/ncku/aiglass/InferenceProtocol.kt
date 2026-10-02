package edu.ncku.aiglass

import android.util.Base64
import org.json.JSONObject

data class InferenceResult(
    val sequence: Long,
    val startUs: Long,
    val transcript: String,
    val translation: String,
    val model: String,
)

object InferenceProtocol {
    fun validateEndpoint(url: String, debug: Boolean): String {
        val normalized = url.trim()
        val uri = java.net.URI(normalized)
        require(uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null) {
            "請輸入有效的推論主機網址"
        }
        require(uri.scheme == "wss" || (debug && uri.scheme == "ws" &&
            uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) {
            "區域網路請使用 wss://；模擬器本機測試可用 ws://10.0.2.2"
        }
        require(uri.path == "/ws" && uri.port in -1..65535) { "路徑須為 /ws" }
        return normalized
    }

    fun audioMessage(id: String, epoch: Long, chunk: PcmChunk): String = JSONObject()
        .put("type", "audio_chunk")
        .put("sessionId", id)
        .put("epoch", epoch)
        .put("sequence", chunk.sequence)
        .put("startUs", chunk.startUs)
        .put("sampleRate", 16000)
        .put("pcm16", Base64.encodeToString(chunk.pcm16, Base64.NO_WRAP))
        .toString()

    fun parseResult(message: String, id: String, epoch: Long): InferenceResult? {
        require(message.length <= 262_144) { "推論回應超過長度限制" }
        val json = JSONObject(message)
        if (json.optString("type") != "result" || json.optString("sessionId") != id ||
            json.optLong("epoch", -1) != epoch) return null
        val sequence = json.getLong("sequence")
        val startUs = json.getLong("startUs")
        require(sequence >= 0 && startUs >= 0) { "無效的推論時間戳" }
        val transcript = json.getString("transcript")
        val translation = json.getString("translation")
        require(transcript.length <= 20_000 && translation.length <= 20_000) { "推論文字過長" }
        return InferenceResult(sequence, startUs, transcript, translation, json.optString("model"))
    }
}
