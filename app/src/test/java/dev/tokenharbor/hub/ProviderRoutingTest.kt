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
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression tests for the "UI shows provider but gateway says none configured" bug.
 *
 * The store is a plain in-memory fixture (no Android Keystore in JVM tests) but it
 * exercises the same persistent-repository contract: data survives a gateway
 * "recreation" (a fresh engine instance reading from the same store), credentials
 * are retrieved from the store, only routable models are listed, and routing never
 * fabricates responses.
 */
class ProviderRoutingTest {

    private val screenshotError =
        "No AI provider is configured. Add a provider with an API key and at least one model ID in the app."

    /** Simple persistent store fixture; keys are kept separately like HubSettings. */
    private class MemoryStore(
        private val backing: MutableMap<String, ApiAccount> = linkedMapOf()
    ) : ProviderStore {
        override fun load(): List<ApiAccount> = backing.values.map { it.copy(models = it.models.toList()) }
        override fun save(accounts: List<ApiAccount>) {
            backing.clear()
            accounts.forEach { backing[it.id] = it.copy(models = it.models.toList()) }
        }
        override fun hasKey(accountId: String): Boolean = !backing[accountId]?.key.isNullOrBlank()

        fun snapshot(): MutableMap<String, ApiAccount> = LinkedHashMap(backing)
    }

    private class CaptureSink : ChatSink {
        var status: HttpStatusCode? = null
        var body: JsonObject? = null
        val frames = mutableListOf<String>()
        var streamRequestId: String? = null
        override suspend fun json(status: HttpStatusCode, body: JsonObject) {
            this.status = status
            this.body = body
        }
        override suspend fun stream(status: HttpStatusCode, frames: Flow<String>, requestId: String) {
            this.status = status
            this.streamRequestId = requestId
            frames.collect { this.frames.add(it) }
        }
    }

    @Test
    fun persistedProviderSurvivesGatewayRecreationAndRoutesChat() = runBlocking {
        val upstreamBody = AtomicReference("")
        val upstreamAuth = AtomicReference("")
        val upstream = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/v1/chat/completions") {
                    upstreamBody.set(call.receiveText())
                    upstreamAuth.set(call.request.header(HttpHeaders.Authorization).orEmpty())
                    val req = parseJsonObject(call.receiveText()) ?: JsonObject(emptyMap())
                    if ((req["stream"] as? JsonPrimitive)?.booleanOrNull == true) {
                        call.respondBytesWriter(contentType = ContentType("text", "event-stream")) {
                            writeStringUtf8("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n")
                            flush()
                            writeStringUtf8("data: [DONE]\n\n")
                            flush()
                        }
                    } else {
                        call.respondText(
                            """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"hello from upstream"},"finish_reason":"stop"}]}""",
                            ContentType.Application.Json
                        )
                    }
                }
            }
        }.start(wait = false)

        try {
            val port = upstream.engine.resolvedConnectors().first().port
            val store = MemoryStore()

            // 1) Provider is added and persisted (as the APIs screen does).
            val provider = ApiAccount(
                id = "provider-tokenharbor",
                name = "TokenHarbor",
                key = "th-universal-key",
                baseUrl = "http://127.0.0.1:$port/v1",
                enabled = true,
                models = listOf("deepseek-v4-flash:free"),
                format = ProviderFormat.OPENAI_COMPAT
            )
            ProviderRepository.install(store)
            ProviderRepository.save(listOf(provider))

            // 2) "Restart/recreate" the gateway service: a brand-new engine reads
            //    from the same persistent store (no stale in-memory list).
            val client = HttpClient(OkHttp)
            try {
                val engine = GatewayEngine(
                    accounts = { ProviderRepository.load() },
                    config = { GatewayConfig(maxRetries = 0) },
                    http = client,
                    log = { }
                )

                // 3) Provider and its decrypted credential are retrievable.
                val loaded = ProviderRepository.load()
                assertEquals(1, loaded.size)
                assertEquals("provider-tokenharbor", loaded[0].id)
                assertEquals("th-universal-key", loaded[0].key)
                assertTrue(ProviderRepository.hasKey("provider-tokenharbor"))

                // 4) Models endpoint lists the routable configured model.
                val modelsBody = engine.listModels().toString()
                assertTrue(modelsBody.contains("\"object\":\"list\""))
                assertTrue(modelsBody.contains("deepseek-v4-flash:free"))

                // 5) Chat request is routed to the real upstream with the decrypted key.
                val nonStream = CaptureSink()
                engine.chat(
                    """{"model":"deepseek-v4-flash:free","messages":[{"role":"user","content":"hi"}]}""",
                    nonStream
                )
                assertEquals(HttpStatusCode.OK, nonStream.status)
                assertTrue(nonStream.body.toString().contains("hello from upstream"))
                assertEquals("Bearer th-universal-key", upstreamAuth.get())
                assertTrue(upstreamBody.get().contains("\"model\":\"deepseek-v4-flash:free\""))
                assertFalse(nonStream.body.toString().contains(screenshotError))

                // 6) Streaming is preserved and routed.
                val stream = CaptureSink()
                engine.chat(
                    """{"model":"deepseek-v4-flash:free","messages":[{"role":"user","content":"hi"}],"stream":true}""",
                    stream
                )
                assertEquals(HttpStatusCode.OK, stream.status)
                assertTrue(stream.frames.any { it.contains("\"content\":\"ok\"") })
                assertEquals("data: [DONE]\n\n", stream.frames.last())

                // 7) The screenshot error cannot occur when a valid enabled provider exists.
                assertFalse(nonStream.body.toString().contains(screenshotError))
                assertFalse(stream.frames.joinToString("").contains(screenshotError))
            } finally {
                client.close()
            }
        } finally {
            upstream.stop(500L, 2_000L)
        }
    }

    @Test
    fun errorCasesAreDistinguishedAndOnlyRoutableModelsListed() = runBlocking {
        val client = HttpClient(OkHttp)
        try {
            fun engineFor(store: MemoryStore) = GatewayEngine(
                accounts = { ProviderRepository.load() },
                config = { GatewayConfig(maxRetries = 0) },
                http = client,
                log = { }
            )

            // No providers at all.
            val emptyStore = MemoryStore()
            ProviderRepository.install(emptyStore)
            val e0 = engineFor(emptyStore)
            val s0 = CaptureSink()
            e0.chat("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""", s0)
            assertEquals(HttpStatusCode.NotFound, s0.status)
            assertTrue(s0.body.toString().contains("no_providers_configured"))
            assertEquals(0, ((e0.listModels()["data"] as JsonArray).size))

            // All providers disabled.
            val disabledStore = MemoryStore()
            ProviderRepository.install(disabledStore)
            ProviderRepository.save(listOf(ApiAccount(id = "p1", name = "P", key = "k", baseUrl = "http://127.0.0.1:1/v1", enabled = false, models = listOf("m"))))
            val e1 = engineFor(disabledStore)
            val s1 = CaptureSink()
            e1.chat("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""", s1)
            assertEquals(HttpStatusCode.NotFound, s1.status)
            assertTrue(s1.body.toString().contains("providers_disabled"))
            assertEquals(0, ((e1.listModels()["data"] as JsonArray).size))

            // Missing credentials for an enabled provider with the model.
            val nokeyStore = MemoryStore()
            ProviderRepository.install(nokeyStore)
            ProviderRepository.save(listOf(ApiAccount(id = "p2", name = "P", key = "", baseUrl = "http://127.0.0.1:1/v1", enabled = true, models = listOf("m"))))
            val e2 = engineFor(nokeyStore)
            val s2 = CaptureSink()
            e2.chat("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""", s2)
            assertEquals(HttpStatusCode.BadGateway, s2.status)
            assertTrue(s2.body.toString().contains("missing_credentials"))
            assertEquals(0, ((e2.listModels()["data"] as JsonArray).size))

            // Unknown model when a valid provider exists (must not emit screenshot error).
            val okStore = MemoryStore()
            ProviderRepository.install(okStore)
            ProviderRepository.save(listOf(ApiAccount(id = "p3", name = "TokenHarbor", key = "k", baseUrl = "http://127.0.0.1:1/v1", enabled = true, models = listOf("deepseek-v4-flash:free"))))
            val e3 = engineFor(okStore)
            val s3 = CaptureSink()
            e3.chat("""{"model":"other-model","messages":[{"role":"user","content":"hi"}]}""", s3)
            assertEquals(HttpStatusCode.NotFound, s3.status)
            val msg = s3.body.toString()
            assertTrue(msg.contains("model_not_found"))
            assertFalse(msg.contains(screenshotError))
            val models = e3.listModels()
            val ids = (models["data"] as JsonArray).mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content }
            assertEquals(listOf("deepseek-v4-flash:free"), ids)
        } finally {
            client.close()
        }
    }
}
