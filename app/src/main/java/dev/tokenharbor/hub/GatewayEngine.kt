package dev.tokenharbor.hub

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/** How a request is shaped when it is forwarded to the upstream provider. */
enum class ProviderFormat {
    /** Standard OpenAI request: Bearer token, full pass-through of the JSON body. */
    OPENAI,

    /** OpenAI request with optional fields removed for stricter compatible servers. */
    OPENAI_COMPAT,

    /** Azure OpenAI: api-key header and deployment-based URL. */
    AZURE
}

/** Runtime configuration used by the routing engine. */
data class GatewayConfig(
    val port: Int = 8080,
    val masterKey: String = "",
    val defaultModel: String = "",
    val connectTimeoutMs: Long = 10_000L,
    val socketTimeoutMs: Long = 120_000L,
    val maxRetries: Int = 2
)

/**
 * The transport boundary between the routing engine and the Ktor server.
 * Keeping this as an interface lets the engine be tested without Android.
 */
interface ChatSink {
    suspend fun json(status: HttpStatusCode, body: JsonObject)
    suspend fun stream(status: HttpStatusCode, frames: Flow<String>, requestId: String)
}

/** Normalizes a user supplied base URL and removes trailing slashes. */
fun normalizeBaseUrl(input: String): String = input.trim().trimEnd('/')

/** Builds the real upstream chat endpoint for a provider. */
fun buildChatUrl(account: ApiAccount, model: String): String {
    val base = normalizeBaseUrl(account.baseUrl)
    return when (account.format) {
        ProviderFormat.AZURE -> {
            val root = if (base.endsWith("/openai", ignoreCase = true)) {
                base.dropLast("/openai".length)
            } else {
                base
            }
            val version = account.apiVersion.ifBlank { "2024-02-15-preview" }
            val deployment = URLEncoder.encode(model, "UTF-8")
            "$root/openai/deployments/$deployment/chat/completions?api-version=$version"
        }
        else -> when {
            base.endsWith("/chat/completions", ignoreCase = true) -> base
            base.endsWith("/v1", ignoreCase = true) -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
    }
}

/** Builds the upstream models endpoint, or an empty string for formats that do not expose one. */
fun buildModelsUrl(account: ApiAccount): String {
    if (account.format == ProviderFormat.AZURE) return ""
    val base = normalizeBaseUrl(account.baseUrl)
    return when {
        base.endsWith("/models", ignoreCase = true) -> base
        base.endsWith("/v1", ignoreCase = true) -> "$base/models"
        else -> "$base/v1/models"
    }
}

/** Validates the OpenAI-compatible chat request. Returns a human readable error, or null when valid. */
fun validateChatRequest(obj: JsonObject): String? {
    val model = (obj["model"] as? JsonPrimitive)?.content?.trim()
    if (model.isNullOrEmpty()) return "Missing or empty required field 'model'."

    val messages = obj["messages"] as? JsonArray
    if (messages == null || messages.isEmpty()) return "Field 'messages' must be a non-empty array."

    for ((index, element) in messages.withIndex()) {
        if (element !is JsonObject) return "messages[$index] must be a JSON object."
        val role = (element["role"] as? JsonPrimitive)?.content
        if (role.isNullOrBlank()) return "messages[$index] is missing 'role'."
        if (!element.containsKey("content") && !element.containsKey("tool_calls")) {
            return "messages[$index] must contain 'content' or 'tool_calls'."
        }
    }

    val stream = obj["stream"]
    if (stream != null) {
        if (stream !is JsonPrimitive || stream.booleanOrNull == null) {
            return "Field 'stream' must be a boolean."
        }
    }
    return null
}

/** Returns the payload of an SSE data line, or null when this is not a data line. */
fun sseData(line: String): String? =
    if (line.startsWith("data:", ignoreCase = true)) line.substring(5).trimStart() else null

/** Parses a JSON object, returning null for malformed or non-object input. */
fun parseJsonObject(text: String): JsonObject? = runCatching {
    when (val element = Json.parseToJsonElement(text.trim())) {
        is JsonObject -> element
        else -> null
    }
}.getOrNull()

/** Builds a standard OpenAI error envelope. */
fun openAiError(
    message: String,
    type: String = "server_error",
    code: String? = null,
    param: String? = null
): JsonObject = buildJsonObject {
    put("error", buildJsonObject {
        put("message", redact(message).take(1_000))
        put("type", type)
        if (code == null) put("code", kotlinx.serialization.json.JsonNull) else put("code", code)
        if (param == null) put("param", kotlinx.serialization.json.JsonNull) else put("param", param)
    })
}

/** Replaces credential-like values so logs and error messages never leak secrets. */
fun redact(input: String): String {
    var output = input
    val patterns = listOf(
        "\"(?i:api[_-]?key|apikey|authorization|token|secret|password|key)\"\\s*:\\s*\"[^\"]*\"",
        "(?i:Bearer|api-key)\\s+[A-Za-z0-9._\\-]+",
        "sk-[A-Za-z0-9._\\-]{6,}"
    )
    for (pattern in patterns) {
        output = Regex(pattern).replace(output) { match ->
            val value = match.value
            when {
                value.startsWith("Bearer", ignoreCase = true) -> "Bearer [REDACTED]"
                value.startsWith("api-key", ignoreCase = true) -> "api-key: [REDACTED]"
                value.startsWith("sk-", ignoreCase = true) -> "sk-[REDACTED]"
                else -> value.substringBefore(':') + ":\"[REDACTED]\""
            }
        }
    }
    return output.take(4_000)
}

/** Constant-time comparison for the gateway API key. */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

private fun newRequestId(): String = "thub-" + UUID.randomUUID().toString().take(8)

private fun classifyFailure(error: Throwable): String {
    if (error is CancellationException) return "cancelled"
    val name = error::class.simpleName.orEmpty()
    return when {
        name.contains("ConnectTimeout", ignoreCase = true) -> "connection timed out"
        name.contains("SocketTimeout", ignoreCase = true) -> "read timed out"
        name.contains("Timeout", ignoreCase = true) -> "request timed out"
        name.contains("Connect", ignoreCase = true) -> "connection failed"
        error is IOException -> "connection failed"
        else -> error.message?.let { redact(it).take(200) } ?: name.ifBlank { "unknown error" }
    }
}

private fun hostOf(baseUrl: String): String = runCatching {
    java.net.URL(normalizeBaseUrl(baseUrl)).host
}.getOrDefault(baseUrl).ifBlank { baseUrl }

private fun mapUpstreamStatus(status: HttpStatusCode): HttpStatusCode = when (status.value) {
    HttpStatusCode.RequestTimeout.value -> HttpStatusCode.GatewayTimeout
    HttpStatusCode.TooManyRequests.value -> HttpStatusCode.TooManyRequests
    408 -> HttpStatusCode.GatewayTimeout
    in 500..599 -> HttpStatusCode.BadGateway
    401, 403 -> HttpStatusCode.BadGateway
    else -> HttpStatusCode.BadRequest
}

private fun errorType(status: HttpStatusCode): String = when (status.value) {
    400, 404, 422 -> "invalid_request_error"
    401, 403 -> "authentication_error"
    429 -> "rate_limit_error"
    in 500..599 -> "server_error"
    else -> "server_error"
}

private fun retryableStatus(status: HttpStatusCode): Boolean =
    status.value == 429 || status.value == 408 || status.value in 500..599

private fun describeUpstreamError(account: ApiAccount, status: HttpStatusCode, body: String): String {
    val upstreamMessage = ((parseJsonObject(body)?.get("error") as? JsonObject)
        ?.get("message") as? JsonPrimitive)?.content
    val detail = redact(upstreamMessage?.take(240) ?: body).take(240)
    return "Provider '${account.name}' (${hostOf(account.baseUrl)}) returned HTTP ${status.value}" +
        if (detail.isNotBlank()) ": $detail" else ""
}

/** Turns an SSE text body (for providers that ignore the stream flag) into one JSON object. */
private fun coerceSseToJson(text: String): JsonObject? {
    var last: JsonObject? = null
    text.lineSequence().forEach { line ->
        val data = sseData(line) ?: return@forEach
        if (data == "[DONE]") return@forEach
        parseJsonObject(data)?.let { last = it }
    }
    return last
}

private data class ModelCache(val signature: String, val at: Long, val value: JsonObject)

/**
 * Real gateway routing core. Reads the configured providers on every request,
 * authenticates nothing itself (the service does that), maps models to providers,
 * forwards the untouched OpenAI payload and converts upstream results/SSE frames.
 */
class GatewayEngine(
    private val accounts: () -> List<ApiAccount>,
    private val config: () -> GatewayConfig = { GatewayConfig() },
    private val http: HttpClient,
    private val log: (String) -> Unit = {}
) {

    @Volatile
    private var modelsCache: ModelCache? = null

    /** Enabled providers, in priority order. */
    fun availableAccounts(): List<ApiAccount> = accounts().filter { it.enabled }.sortedBy { it.priority }

    /**
     * Builds a genuine OpenAI model list from configured model IDs and, when the
     * provider supports it, its upstream GET /models response. Results are cached
     * briefly so OpenCode's discovery calls stay fast.
     */
    suspend fun listModels(): JsonObject {
        val providers = availableAccounts().filter { it.key.isNotBlank() }
        val signature = providers.joinToString("|") { p -> "${p.id}:${p.name}:${p.models.joinToString(",")}:${p.format}" }
        val now = System.currentTimeMillis()
        val cached = modelsCache
        if (cached != null && cached.signature == signature && now - cached.at < 60_000L) return cached.value

        val owners = LinkedHashMap<String, String>()
        providers.forEach { provider ->
            provider.models.forEach { model ->
                if (model.isNotBlank()) owners.putIfAbsent(model.trim(), provider.name)
            }
        }

        coroutineScope {
            providers.filter { it.format != ProviderFormat.AZURE }.map { provider ->
                async { discoverModels(provider) }
            }.awaitAll().forEach { discovered ->
                discovered.forEach { (id, owner) -> owners.putIfAbsent(id, owner) }
            }
        }

        val data = buildJsonArray {
            owners.forEach { (id, owner) ->
                add(buildJsonObject {
                    put("id", id)
                    put("object", "model")
                    put("created", now / 1_000L)
                    put("owned_by", owner)
                })
            }
        }
        val result = buildJsonObject {
            put("object", "list")
            put("data", data)
        }
        modelsCache = ModelCache(signature, now, result)
        return result
    }

    private suspend fun discoverModels(provider: ApiAccount): List<Pair<String, String>> {
        val url = buildModelsUrl(provider)
        if (url.isBlank()) return emptyList()
        return runCatching {
            withTimeout(8_000L) {
                val response = http.request {
                    url(url)
                    method = HttpMethod.Get
                    header(HttpHeaders.Authorization, "Bearer ${provider.key}")
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                }
                if (!response.status.isSuccess()) {
                    log("Model discovery: ${provider.name} returned HTTP ${response.status.value}")
                    return@withTimeout emptyList()
                }
                val body = parseJsonObject(response.bodyAsText())
                val array = body?.get("data") as? JsonArray ?: return@withTimeout emptyList()
                array.mapNotNull { element ->
                    val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.trim()
                    if (id.isNullOrEmpty()) null else id to provider.name
                }
            }
        }.getOrElse {
            log("Model discovery failed for ${provider.name}: ${classifyFailure(it)}")
            emptyList()
        }
    }

    /** Handles one chat completion request and delivers the result through [sink]. */
    suspend fun chat(rawBody: String, sink: ChatSink, requestId: String = newRequestId()) {
        val body = parseJsonObject(rawBody)
        if (body == null) {
            sink.json(
                HttpStatusCode.BadRequest,
                openAiError("Request body must be a JSON object.", "invalid_request_error", "invalid_json")
            )
            return
        }
        val validationError = validateChatRequest(body)
        if (validationError != null) {
            sink.json(HttpStatusCode.BadRequest, openAiError(validationError, "invalid_request_error", "invalid_request"))
            return
        }

        val model = body["model"]!!.jsonPrimitive.content.trim()
        val stream = (body["stream"] as? JsonPrimitive)?.booleanOrNull == true
        val candidates = availableAccounts().filter { account ->
            account.key.isNotBlank() && account.models.any { it.trim() == model }
        }
        if (candidates.isEmpty()) {
            val available = availableAccounts().flatMap { it.models }.map { it.trim() }.distinct().sorted()
            val message = if (available.isEmpty()) {
                "No AI provider is configured. Add a provider with an API key and at least one model ID in the app."
            } else {
                "Model '$model' is not configured. Available models: ${available.take(25).joinToString(", ")}" +
                    if (available.size > 25) " (and ${available.size - 25} more)" else ""
            }
            sink.json(HttpStatusCode.NotFound, openAiError(message, "invalid_request_error", "model_not_found"))
            return
        }

        val maxRetries = config().maxRetries.coerceIn(0, 5)
        var lastStatus = HttpStatusCode.BadGateway
        var lastMessage = "All configured providers failed."
        log("Chat request $requestId model=$model stream=$stream providers=${candidates.size}")

        for (account in candidates) {
            val url = buildChatUrl(account, model)
            val payload = buildUpstreamBody(body, account, model).toString()

            for (attempt in 0..maxRetries) {
                when (val outcome = requestOnce(account, url, payload, stream, requestId)) {
                    is Outcome.Ready -> {
                        when {
                            outcome.streamResponse != null -> {
                                log("Streaming $requestId via ${account.name} (${hostOf(account.baseUrl)})")
                                sink.stream(
                                    HttpStatusCode.OK,
                                    sseFrames(outcome.streamResponse, account, requestId),
                                    requestId
                                )
                                return
                            }
                            outcome.streamText != null -> {
                                sink.stream(
                                    HttpStatusCode.OK,
                                    flow {
                                        emit("data: ${outcome.streamText}\n\n")
                                        emit("data: [DONE]\n\n")
                                    },
                                    requestId
                                )
                                return
                            }
                            outcome.json != null -> {
                                log("Completed $requestId via ${account.name} in attempt ${attempt + 1}")
                                sink.json(HttpStatusCode.OK, outcome.json)
                                return
                            }
                        }
                    }
                    is Outcome.HttpError -> {
                        lastStatus = mapUpstreamStatus(outcome.status)
                        lastMessage = describeUpstreamError(account, outcome.status, outcome.body)
                        log("$requestId ${account.name}: HTTP ${outcome.status.value} (attempt ${attempt + 1}) - ${redact(lastMessage)}")
                        if (!retryableStatus(outcome.status) || attempt == maxRetries) break
                        delay(250L * (attempt + 1))
                    }
                    is Outcome.Failure -> {
                        lastStatus = if (outcome.timeout) HttpStatusCode.GatewayTimeout else HttpStatusCode.BadGateway
                        lastMessage = "Provider '${account.name}' (${hostOf(account.baseUrl)}): ${outcome.message}"
                        log("$requestId failed on ${account.name}: ${outcome.message} (attempt ${attempt + 1})")
                        if (!outcome.retryable || attempt == maxRetries) break
                        delay(250L * (attempt + 1))
                    }
                }
            }
        }

        sink.json(lastStatus, openAiError(lastMessage, errorType(lastStatus), "upstream_failure"))
    }

    private fun buildUpstreamBody(original: JsonObject, account: ApiAccount, model: String): JsonObject {
        val compatDrop = setOf("stream_options", "logprobs", "top_logprobs", "service_tier")
        return buildJsonObject {
            original.forEach { (key, value) ->
                when {
                    account.format == ProviderFormat.AZURE && key == "model" -> Unit
                    key == "model" -> put(key, model)
                    account.format == ProviderFormat.OPENAI_COMPAT && key in compatDrop -> Unit
                    else -> put(key, value)
                }
            }
        }
    }

    private suspend fun requestOnce(
        account: ApiAccount,
        url: String,
        payload: String,
        stream: Boolean,
        requestId: String
    ): Outcome {
        return try {
            val settings = config()
        val response = http.request {
            url(url)
            method = HttpMethod.Post
            if (account.format == ProviderFormat.AZURE) {
                header("api-key", account.key)
            } else {
                header(HttpHeaders.Authorization, "Bearer ${account.key}")
            }
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Accept, if (stream) "text/event-stream" else ContentType.Application.Json.toString())
            header("X-Request-ID", requestId)
            setBody(payload)
            timeout {
                connectTimeoutMillis = settings.connectTimeoutMs
                socketTimeoutMillis = settings.socketTimeoutMs
                requestTimeoutMillis = null
            }
        }
        if (!response.status.isSuccess()) {
            val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
            return Outcome.HttpError(response.status, errorBody)
        }

        val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
        if (stream) {
            if (contentType.contains("text/event-stream", ignoreCase = true)) {
                Outcome.Ready(streamResponse = response)
            } else {
                val text = response.bodyAsText()
                val obj = parseJsonObject(text) ?: coerceSseToJson(text)
                if (obj == null) {
                    Outcome.Failure("returned an unreadable stream response", retryable = false, timeout = false)
                } else {
                    Outcome.Ready(streamText = obj.toString())
                }
            }
        } else {
            val text = response.bodyAsText()
            val obj = parseJsonObject(text) ?: coerceSseToJson(text)
            when {
                obj == null -> Outcome.Failure("returned a non-JSON response", retryable = true, timeout = false)
                obj.containsKey("error") -> Outcome.HttpError(HttpStatusCode.BadGateway, text)
                else -> Outcome.Ready(json = obj)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        val message = classifyFailure(error)
            Outcome.Failure(message, retryable = true, timeout = message.contains("timed out", ignoreCase = true))
        }
    }

    private fun sseFrames(response: HttpResponse, account: ApiAccount, requestId: String): Flow<String> = flow {
        val channel = response.bodyAsChannel()
        var done = false
        var sawData = false
        try {
            while (!channel.isClosedForRead && !done) {
                val line = channel.readUTF8Line() ?: break
                when {
                    line.isBlank() -> Unit
                    line.startsWith(":") -> emit("$line\n\n")
                    line.startsWith("data:", ignoreCase = true) -> {
                        val data = sseData(line).orEmpty()
                        if (data == "[DONE]") {
                            emit("data: [DONE]\n\n")
                            sawData = true
                            done = true
                        } else {
                            val frame = parseJsonObject(data)
                            if (frame != null) {
                                sawData = true
                                emit("data: $frame\n\n")
                            } else {
                                log("Skipped malformed SSE frame from ${account.name} for $requestId")
                            }
                        }
                    }
                    else -> emit("$line\n")
                }
            }
            if (!done) emit("data: [DONE]\n\n")
            if (!sawData) log("Upstream ${account.name} sent no data frames for $requestId")
            log("Stream finished $requestId via ${account.name}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = classifyFailure(error)
            log("Stream error $requestId via ${account.name}: $message")
            emit("data: ${openAiError("Upstream stream failed: $message", "server_error", "stream_error")}\n\n")
            emit("data: [DONE]\n\n")
        }
    }

    private sealed class Outcome {
        data class Ready(
            val json: JsonObject? = null,
            val streamResponse: HttpResponse? = null,
            val streamText: String? = null
        ) : Outcome()

        data class HttpError(val status: HttpStatusCode, val body: String) : Outcome()
        data class Failure(val message: String, val retryable: Boolean, val timeout: Boolean) : Outcome()
    }
}
