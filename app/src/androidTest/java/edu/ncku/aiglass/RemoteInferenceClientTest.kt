package edu.ncku.aiglass

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RemoteInferenceClientTest {
    @Test fun websocketResendsDurableChunkAndAcknowledgesOnlyMatchingResult() {
        val store = MeetingStore.create(InstrumentationRegistry.getInstrumentation().targetContext)
        store.savePending(PcmChunk(4, 8_000_000, ByteArray(32000)))
        val resultReceived = CountDownLatch(1)
        val serverClosed = CountDownLatch(1)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                when (message.getString("type")) {
                    "hello" -> {
                        assertEquals("test-token", message.getString("token"))
                        webSocket.send(JSONObject().put("type", "ready").put("sessionId", store.id)
                            .put("epoch", 3).toString())
                    }
                    "audio_chunk" -> {
                        assertEquals(4, message.getLong("sequence"))
                        webSocket.send(JSONObject().put("type", "result")
                            .put("sessionId", "wrong").put("epoch", 3)
                            .put("sequence", 4).put("startUs", 8_000_000)
                            .put("transcript", "discard").put("translation", "discard").toString())
                        webSocket.send(JSONObject().put("type", "result")
                            .put("sessionId", store.id).put("epoch", 3)
                            .put("sequence", 4).put("startUs", 8_000_000)
                            .put("transcript", "accepted").put("translation", "已接受").toString())
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                serverClosed.countDown()
            }
        }))
        server.start()
        val client = RemoteInferenceClient(store, 3,
            onResult = {
                assertEquals("accepted", it.transcript)
                resultReceived.countDown()
            }, onSummary = {}, onStatus = {})
        try {
            client.connect("ws://localhost:${server.port}/ws", "test-token", true)
            assertTrue("server result not received", resultReceived.await(10, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(store.hasPending(4, 8_000_000))
        } finally {
            client.close()
            assertTrue("WebSocket close handshake did not complete", serverClosed.await(5, TimeUnit.SECONDS))
            server.shutdown()
        }
    }

    @Test fun reconnectResendsUnacknowledgedChunkAfterServerRestart() {
        val store = MeetingStore.create(InstrumentationRegistry.getInstrumentation().targetContext)
        store.savePending(PcmChunk(1, 2_000_000, ByteArray(32000)))
        val firstAudio = CountDownLatch(1)
        val resultReceived = CountDownLatch(1)
        val serverClosed = CountDownLatch(1)
        val statuses = mutableListOf<String>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (JSONObject(text).getString("type")) {
                    "hello" -> webSocket.send(JSONObject().put("type", "ready")
                        .put("sessionId", store.id).put("epoch", 2).toString())
                    "audio_chunk" -> {
                        firstAudio.countDown()
                        webSocket.cancel()
                    }
                }
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (JSONObject(text).getString("type")) {
                    "hello" -> webSocket.send(JSONObject().put("type", "ready")
                        .put("sessionId", store.id).put("epoch", 2).toString())
                    "audio_chunk" -> webSocket.send(JSONObject().put("type", "result")
                        .put("sessionId", store.id).put("epoch", 2).put("sequence", 1)
                        .put("startUs", 2_000_000).put("transcript", "resumed")
                        .put("translation", "已恢復").toString())
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                serverClosed.countDown()
            }
        }))
        server.start()
        val client = RemoteInferenceClient(store, 2, { resultReceived.countDown() }, {},
            { synchronized(statuses) { statuses.add(it) } })
        try {
            client.connect("ws://localhost:${server.port}/ws", "test-token", true)
            assertTrue(firstAudio.await(5, TimeUnit.SECONDS))
            assertTrue("reconnect did not deliver durable chunk; statuses=$statuses",
                resultReceived.await(12, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(store.hasPending(1, 2_000_000))
        } finally {
            client.close()
            serverClosed.await(5, TimeUnit.SECONDS)
            server.shutdown()
        }
    }
}
