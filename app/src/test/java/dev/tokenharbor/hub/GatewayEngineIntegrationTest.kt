package dev.tokenharbor.hub

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.resolvedConnectors
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.flush
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the real routing engine against a real (local) OpenAI-compatible
 * HTTP server. The upstream is a test fixture; the gateway itself always
 * performs genuine network requests and never fabricates completions.
 */
class GatewayEngineIntegrationTest {

    @Test
    fun modelsNonStreamingStreamingAndErrors() = runBlocking {
        val upstreamBody = AtomicReference("")
        val upstreamAuth = AtomicReference("")
        val upstream = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/v1/models") {
                    call.respondText(
                        """{"object":"list","data":[{"id":"upstream-model","object":"model"}]}""",
                        ContentType.Application.Json
                    )
                }
                post("/v1/chat/completions") {
                    val text = call.receiveText()
                    upstreamBody.set(text)
                    upstreamAuth.set(call.request.header(HttpHeaders.Authorization).orEmpty())
                    val request = parseJsonObject(text) ?: JsonObject(emptyMap())
                    val streaming = (request["stream"] as? JsonPrimitive)?.booleanOrNull == true
                    if (streaming) {
                        call.response.header(HttpHeaders.ContentType, "text/event-stream")
                        call.respondBytesWriter(contentType = ContentType("text", "event-stream")) {
                            writeStringUtf8("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"he\"}}]}\n\n")
                            flush()
                            writeStringUtf8("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"llo\"},\"finish_reason\":\"stop\"}]}\n\n")
                            flush()
                            writeStringUtf8("data: [DONE]\n\n")
                            flush()
                        }
                    } else {
                        call.respondText(
                            """{"id":"c1","object":"chat.completion","model":"test-model","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]}""",
                            ContentType.Application.Json
                        )
                    }
                }
            }
        }.start(wait = false)

        try {
            val port = upstream.engine.resolvedConnectors().first().port
            val client = HttpClient(OkHttp)
            try {
                val engine = GatewayEngine(
                    accounts = {
                        listOf(
                            ApiAccount(
                                name = "fixture",
                                key = "up-key",
                                baseUrl = "http://127.0.0.1:$port/v1",
                                models = listOf("test-model")
                            )
                        )
                    },
                    config = { GatewayConfig(maxRetries = 0) },
                    http = client,
                    log = { }
                )

                // 1. Models are built from configured IDs plus the upstream /models response.
                val models = engine.listModels().toString()
                assertTrue(models.contains("test-model"))
                assertTrue(models.contains("upstream-model"))
                assertTrue(models.contains("\"object\":\"list\""))

                // 2. Non-streaming, OpenCode-shaped request with tools is forwarded unchanged.
                var jsonStatus: HttpStatusCode? = null
                var jsonBody: JsonObject? = null
                val jsonSink = object : ChatSink {
                    override suspend fun json(status: HttpStatusCode, body: JsonObject) {
                        jsonStatus = status
                        jsonBody = body
                    }
                    override suspend fun stream(status: HttpStatusCode, frames: kotlinx.coroutines.flow.Flow<String>, requestId: String) {
                        throw AssertionError("Unexpected stream response")
                    }
                }
                engine.chat(
                    """{"model":"test-model","messages":[{"role":"user","content":"hi"}],"tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}],"temperature":0.2}""",
                    jsonSink
                )
                assertEquals(HttpStatusCode.OK, jsonStatus)
                assertTrue(jsonBody.toString().contains("hello"))
                assertTrue(upstreamBody.get().contains("\"tools\""))
                assertTrue(upstreamBody.get().contains("\"temperature\":0.2"))
                assertEquals("Bearer up-key", upstreamAuth.get())

                // 3. Streaming returns real SSE frames followed by [DONE].
                val frames = mutableListOf<String>()
                val streamSink = object : ChatSink {
                    override suspend fun json(status: HttpStatusCode, body: JsonObject) {
                        throw AssertionError("Expected SSE response, got JSON: $body")
                    }
                    override suspend fun stream(status: HttpStatusCode, framesFlow: kotlinx.coroutines.flow.Flow<String>, requestId: String) {
                        assertEquals(HttpStatusCode.OK, status)
                        assertTrue(requestId.startsWith("thub-"))
                        framesFlow.collect { frames.add(it) }
                    }
                }
                engine.chat(
                    """{"model":"test-model","messages":[{"role":"user","content":"hi"}],"stream":true}""",
                    streamSink
                )
                assertTrue(frames.any { it.contains("\"content\":\"he\"") })
                assertTrue(frames.any { it.contains("\"content\":\"llo\"") })
                assertEquals("data: [DONE]\n\n", frames.last())

                // 4. Invalid model never reaches an upstream provider.
                var invalidStatus: HttpStatusCode? = null
                var invalidBody = ""
                engine.chat("""{"model":"does-not-exist","messages":[{"role":"user","content":"hi"}]}""",
                    object : ChatSink {
                        override suspend fun json(status: HttpStatusCode, body: JsonObject) {
                            invalidStatus = status
                            invalidBody = body.toString()
                        }
                        override suspend fun stream(status: HttpStatusCode, frames: kotlinx.coroutines.flow.Flow<String>, requestId: String) = Unit
                    })
                assertEquals(HttpStatusCode.NotFound, invalidStatus)
                assertTrue(invalidBody.contains("model_not_found"))

                // 5. Malformed JSON is rejected with a 400.
                var badStatus: HttpStatusCode? = null
                engine.chat("not json", object : ChatSink {
                    override suspend fun json(status: HttpStatusCode, body: JsonObject) { badStatus = status }
                    override suspend fun stream(status: HttpStatusCode, frames: kotlinx.coroutines.flow.Flow<String>, requestId: String) = Unit
                })
                assertEquals(HttpStatusCode.BadRequest, badStatus)

                // 6. Connection failures produce a diagnostic OpenAI error, never a fake success.
                val offline = GatewayEngine(
                    accounts = {
                        listOf(
                            ApiAccount(
                                name = "offline",
                                key = "k",
                                baseUrl = "http://127.0.0.1:1/v1",
                                models = listOf("test-model")
                            )
                        )
                    },
                    config = { GatewayConfig(maxRetries = 0, connectTimeoutMs = 1_000L) },
                    http = client
                )
                var failureStatus: HttpStatusCode? = null
                var failureBody = ""
                offline.chat("""{"model":"test-model","messages":[{"role":"user","content":"hi"}]}""",
                    object : ChatSink {
                        override suspend fun json(status: HttpStatusCode, body: JsonObject) {
                            failureStatus = status
                            failureBody = body.toString()
                        }
                        override suspend fun stream(status: HttpStatusCode, frames: kotlinx.coroutines.flow.Flow<String>, requestId: String) = Unit
                    })
                assertEquals(HttpStatusCode.BadGateway, failureStatus)
                assertTrue(failureBody.contains("offline"))
                assertFalse(failureBody.contains("\"choices\""))
            } finally {
                client.close()
            }
        } finally {
            upstream.stop(500L, 2_000L)
        }
    }
}
