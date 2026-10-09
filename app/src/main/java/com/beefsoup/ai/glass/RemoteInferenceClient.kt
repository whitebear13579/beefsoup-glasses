package com.beefsoup.ai.glass

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One session per socket; accepted results alone delete persisted inference inputs. */
class RemoteInferenceClient(
    private val store: MeetingStore,
    private val epoch: Long,
    private val onResult: (InferenceResult) -> Unit,
    private val onSummary: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val retries = Executors.newSingleThreadScheduledExecutor()
    private val callbacks = Any()
    @Volatile private var socket: WebSocket? = null
    private var endpoint = ""
    private var token = ""
    @Volatile private var authenticated = false
    @Volatile private var closed = false
    @Volatile private var finishRequested = false
    private var finalizeSent = false
    private var reconnectDelay = 2L
    private val sent = mutableSetOf<Long>()

    fun connect(url: String, token: String, debug: Boolean) {
        endpoint = InferenceProtocol.validateEndpoint(url, debug)
        require(token.isNotBlank()) { "請輸入推論服務權杖" }
        this.token = token
        closed = false
        open()
    }

    private fun open() {
        synchronized(callbacks) {
        if (closed) return
        authenticated = false
        finalizeSent = false
        synchronized(sent) { sent.clear() }
        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed) return
                webSocket.send(JSONObject().put("type", "hello").put("token", token)
                    .put("sessionId", store.id).put("epoch", epoch).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(callbacks) {
                if (closed || webSocket != socket) return
                try {
                    if (text.length > 262_144) throw IllegalArgumentException("回應過長")
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "ready" -> if (json.optString("sessionId") == store.id &&
                            json.optLong("epoch", -1) == epoch) {
                            authenticated = true
                            reconnectDelay = 2
                            onStatus("電腦推論已連線")
                            store.transcriptResults().forEach { sendEvidence(it) }
                            store.ocrObservations().forEach { (sequence, observation) -> sendOcr(sequence, observation) }
                            pumpPending()
                            maybeFinalize()
                        }
                        "result" -> {
                            val result = InferenceProtocol.parseResult(text, store.id, epoch) ?: return
                            if (store.hasPending(result.sequence, result.startUs)) {
                                onResult(result)
                                store.acknowledge(result.sequence)
                                synchronized(sent) { sent.remove(result.sequence) }
                                pumpPending()
                                maybeFinalize()
                            }
                        }
                        "summary" -> if (json.optString("sessionId") == store.id &&
                            json.optLong("epoch", -1) == epoch) {
                            onSummary(json.getString("text"))
                            close()
                        }
                        "error" -> onStatus("推論服務錯誤：${json.optString("code")}")
                    }
                } catch (_: Exception) {
                    onStatus("已忽略無效的推論回應")
                }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed || webSocket != socket) return
                authenticated = false
                onStatus("推論連線中斷，音訊已保存在手機待處理")
                reconnect(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed || webSocket != socket) return
                authenticated = false
                if (!closed && code != 1008) reconnect(webSocket)
                else if (code == 1008) {
                    onStatus("推論權杖或連線授權遭拒")
                    close()
                }
            }
        })
        }
    }

    private fun reconnect(old: WebSocket) {
        if (closed || old != socket) return
        try {
            retries.schedule({ if (!closed) open() }, reconnectDelay, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) { return }
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30)
    }

    fun onPendingChunk() {
        if (!authenticated) return
        pumpPending()
    }

    private fun pumpPending() {
        if (!authenticated) return
        synchronized(sent) {
            val room = 4 - sent.size
            if (room <= 0) return
            store.pendingChunks(room, sent).forEach { chunk ->
                if (socket?.send(InferenceProtocol.audioMessage(store.id, epoch, chunk)) == true)
                    sent.add(chunk.sequence)
            }
        }
    }

    fun sendOcr(sequence: Long, observation: OcrObservation) {
        if (!authenticated) return
        socket?.send(JSONObject().put("type", "ocr").put("sessionId", store.id)
            .put("epoch", epoch).put("sequence", sequence)
            .put("ptsUs", observation.ptsUs).put("text", observation.text).toString())
    }

    private fun sendEvidence(result: InferenceResult) {
        if (!authenticated) return
        socket?.send(JSONObject().put("type", "transcript_evidence")
            .put("sessionId", store.id).put("epoch", epoch)
            .put("sequence", result.sequence).put("startUs", result.startUs)
            .put("text", result.transcript).toString())
    }

    fun finish() {
        finishRequested = true
        maybeFinalize()
    }

    private fun maybeFinalize() {
        if (finishRequested && authenticated && !finalizeSent && store.pendingCount() == 0) {
            finalizeSent = socket?.send(JSONObject().put("type", "finalize")
                .put("sessionId", store.id).put("epoch", epoch).toString()) == true
        }
    }

    fun close() {
        synchronized(callbacks) {
            closed = true
            socket?.close(1000, "session closed")
            socket = null
        }
        retries.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }
}
