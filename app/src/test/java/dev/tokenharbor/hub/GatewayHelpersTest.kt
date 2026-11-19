package dev.tokenharbor.hub

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayHelpersTest {

    private val account = ApiAccount(
        name = "Token Harbor",
        key = "up-key",
        baseUrl = "https://tokenharbor.ai/v1",
        models = listOf("deepseek-v4.1-flash:free")
    )

    @Test
    fun urlsAreBuiltFromRealProviderConfiguration() {
        assertEquals("https://tokenharbor.ai/v1", normalizeBaseUrl("  https://tokenharbor.ai/v1/  "))
        assertEquals("https://tokenharbor.ai/v1/chat/completions", buildChatUrl(account, "test"))
        assertEquals("https://tokenharbor.ai/v1/models", buildModelsUrl(account))

        val azure = account.copy(format = ProviderFormat.AZURE, apiVersion = "2024-06-01")
        assertEquals(
            "https://tokenharbor.ai/openai/deployments/test/chat/completions?api-version=2024-06-01",
            buildChatUrl(azure, "test")
        )
        assertEquals("", buildModelsUrl(azure))
    }

    @Test
    fun chatRequestsAreValidated() {
        val valid = buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "hi") })
            })
            put("stream", false)
        }
        assertEquals(null, validateChatRequest(valid))

        val noModel = buildJsonObject { put("messages", buildJsonArray { }) }
        assertTrue(validateChatRequest(noModel)!!.contains("model"))

        val emptyMessages = buildJsonObject {
            put("model", "m")
            put("messages", buildJsonArray { })
        }
        assertTrue(validateChatRequest(emptyMessages)!!.contains("messages"))

        val badStream = buildJsonObject {
            put("model", "m")
            put("stream", "yes")
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", "hi") })
            })
        }
        assertTrue(validateChatRequest(badStream)!!.contains("stream"))
    }

    @Test
    fun sseDataLinesAreParsed() {
        assertEquals("{\"a\":1}", sseData("data: {\"a\":1}"))
        assertEquals("[DONE]", sseData("data: [DONE]"))
        assertEquals(null, sseData("event: message"))
    }

    @Test
    fun secretsAreRedactedFromLogs() {
        val redacted = redact("Bearer sk-secrettoken123 {\"api_key\":\"topsecret\", \"model\":\"m\"}")
        assertFalse(redacted.contains("topsecret"))
        assertFalse(redacted.contains("sk-secrettoken123"))
        assertTrue(redacted.contains("[REDACTED]"))
        assertTrue(redacted.contains("\"model\":\"m\""))
    }

    @Test
    fun apiKeyComparisonIsExact() {
        assertTrue(constantTimeEquals("strong-key", "strong-key"))
        assertFalse(constantTimeEquals("strong-key", "strong-keys"))
        assertFalse(constantTimeEquals("a", "b"))
    }

    @Test
    fun errorEnvelopeHasOpenAiShape() {
        val error = openAiError("Something failed", "server_error", "upstream_failure")
        val envelope = error["error"] as? kotlinx.serialization.json.JsonObject
        assertNotNull(envelope)
        assertEquals("Something failed", envelope!!["message"]?.toString()?.trim('"'))
        assertEquals("server_error", envelope["type"]?.toString()?.trim('"'))
        assertEquals("upstream_failure", envelope["code"]?.toString()?.trim('"'))
    }
}
