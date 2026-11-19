package dev.tokenharbor.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.flush
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Foreground service that owns the real Ktor HTTP listener and the upstream
 * HTTP client. The listener binds to 0.0.0.0 so other devices on the same Wi-Fi
 * can reach the gateway at the LAN address shown in the app.
 */
class GatewayService : Service() {

    companion object {
        const val ACTION_START = "dev.tokenharbor.hub.action.START"
        const val ACTION_STOP = "dev.tokenharbor.hub.action.STOP"
        private const val CHANNEL_ID = "token_harbor_gateway"
        private const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: EmbeddedServer<*, *>? = null
    private var httpClient: HttpClient? = null
    private var startJob: Job? = null
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        HubSettings.init(applicationContext)
        GatewayController.init(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notificationFor(GatewayController.status.value))
        notificationJob = scope.launch {
            GatewayController.status.collect { status -> updateNotification(status) }
        }
        GatewayLog.add("Gateway service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopGateway()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // START_STICKY can restart us with a null intent. Honor the user's last choice.
                if (intent == null && !HubSettings.desiredRunning()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startGateway()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startGateway() {
        if (server != null || startJob?.isActive == true) return
        startJob = scope.launch {
            val settings = HubSettings.config()
            GatewayController.report(
                GatewayController.State.STARTING,
                "Binding HTTP listener to 0.0.0.0:${settings.port}..."
            )
            if (settings.masterKey.isBlank()) {
                GatewayController.report(
                    GatewayController.State.ERROR,
                    "Gateway cannot start without a master API key",
                    "Set a strong master API key on the Server tab."
                )
                GatewayLog.add("Startup aborted: master API key is empty")
                stopSelf()
                return@launch
            }

            try {
                val client = HttpClient(OkHttp) {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 10_000L
                        socketTimeoutMillis = 120_000L
                        requestTimeoutMillis = null
                    }
                }
                httpClient = client
                val engine = GatewayEngine(
                    accounts = { HubSettings.loadAccounts() },
                    config = { HubSettings.config() },
                    http = client,
                    log = { GatewayLog.add(it) }
                )

                val listener = embeddedServer(CIO, port = settings.port, host = "0.0.0.0") {
                    routing {
                        get("/health") {
                            val status = GatewayController.status.value
                            val body = buildJsonObject {
                                put("status", if (status.state == GatewayController.State.RUNNING) "ok" else status.state.name.lowercase())
                                put("port", status.port)
                                put("lanBaseUrl", status.lanBaseUrl)
                            }
                            call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                        }

                        get("/v1/models") {
                            if (!authorized(call)) {
                                rejectUnauthorized(call)
                                return@get
                            }
                            GatewayController.noteRequest()
                            val started = System.currentTimeMillis()
                            val body = engine.listModels()
                            val count = (body["data"] as? JsonArray)?.size ?: 0
                            GatewayLog.add("GET /v1/models -> 200 ($count models, ${System.currentTimeMillis() - started}ms)")
                            call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                        }

                        post("/v1/chat/completions") {
                            if (!authorized(call)) {
                                rejectUnauthorized(call)
                                return@post
                            }
                            GatewayController.noteRequest()
                            val raw = runCatching { call.receiveText() }.getOrDefault("")
                            engine.chat(raw, ServerChatSink(call))
                        }

                        options("/{...}") {
                            call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                            call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
                            call.response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type, X-Request-ID")
                            call.respondText("", status = HttpStatusCode.NoContent)
                        }

                        get("/{...}") {
                            call.respondText(
                                openAiError("Unknown endpoint ${call.request.path()}", "invalid_request_error", "not_found").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.NotFound
                            )
                        }
                        post("/{...}") {
                            call.respondText(
                                openAiError("Unknown endpoint ${call.request.path()}", "invalid_request_error", "not_found").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.NotFound
                            )
                        }
                    }
                }

                server = listener
                listener.start(wait = false)
                GatewayController.report(GatewayController.State.RUNNING, "Listening on port ${settings.port}")
                GatewayLog.add("Gateway listening on http://0.0.0.0:${settings.port}/v1")
            } catch (error: Throwable) {
                val message = error.message?.let { redact(it) } ?: error::class.simpleName.orEmpty()
                GatewayController.report(
                    GatewayController.State.ERROR,
                    "Could not bind port ${settings.port}",
                    message
                )
                GatewayLog.add("Startup failed on port ${settings.port}: $message")
                stopGateway()
                stopSelf()
            }
        }
    }

    /** Adapts the engine's transport-agnostic output to Ktor responses. */
    private class ServerChatSink(private val call: ApplicationCall) : ChatSink {
        override suspend fun json(status: HttpStatusCode, body: JsonObject) {
            call.response.header("X-Request-ID", call.request.header("X-Request-ID") ?: "")
            call.respondText(body.toString(), ContentType.Application.Json, status)
        }

        override suspend fun stream(status: HttpStatusCode, frames: Flow<String>, requestId: String) {
            call.response.header(HttpHeaders.CacheControl, "no-cache, no-transform")
            call.response.header("X-Accel-Buffering", "no")
            call.response.header("X-Request-ID", requestId)
            call.respondBytesWriter(contentType = ContentType("text", "event-stream"), status = status) {
                frames.collect { frame ->
                    writeStringUtf8(frame)
                    flush()
                }
            }
        }
    }

    private fun authorized(call: ApplicationCall): Boolean {
        val expected = HubSettings.config().masterKey
        if (expected.isBlank()) return false
        val header = call.request.header(HttpHeaders.Authorization) ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val provided = header.substring(7).trim()
        return provided.isNotEmpty() && constantTimeEquals(provided, expected)
    }

    private suspend fun rejectUnauthorized(call: ApplicationCall) {
        GatewayLog.add("Rejected unauthenticated request to ${call.request.path()}")
        call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"Token Harbor Hub\"")
        call.respondText(
            openAiError(
                "Invalid or missing API key. Send Authorization: Bearer <master API key>.",
                "authentication_error",
                "invalid_api_key"
            ).toString(),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized
        )
    }

    private fun stopGateway() {
        startJob?.cancel()
        startJob = null
        runCatching { server?.stop(500L, 2_000L) }
        server = null
        runCatching { httpClient?.close() }
        httpClient = null
        GatewayController.report(GatewayController.State.STOPPED, "Gateway stopped")
        GatewayLog.add("Gateway listener stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Token Harbor Gateway", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows whether the local OpenAI-compatible gateway is running"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notificationFor(status: GatewayController.Status): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val (title, text) = when (status.state) {
            GatewayController.State.RUNNING ->
                "Gateway running" to "http://${status.lanIp}:${status.port}/v1"
            GatewayController.State.STARTING ->
                "Gateway starting" to "Binding port ${status.port}..."
            GatewayController.State.ERROR ->
                "Gateway error" to (status.lastError ?: status.message)
            GatewayController.State.STOPPED ->
                "Gateway stopped" to "Local API gateway is not listening"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(status: GatewayController.Status) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notificationFor(status))
        }
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        stopGateway()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        scope.cancel()
        super.onDestroy()
    }
}
