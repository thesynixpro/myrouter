package dev.tokenharbor.hub

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared, observable state for the HTTP listener. */
object GatewayController {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    data class Status(
        val state: State = State.STOPPED,
        val port: Int = 8080,
        val lanIp: String = "127.0.0.1",
        val message: String = "Gateway stopped",
        val requestCount: Int = 0,
        val lastError: String? = null
    ) {
        val onDeviceBaseUrl: String get() = "http://127.0.0.1:$port/v1"
        val lanBaseUrl: String get() = "http://$lanIp:$port/v1"
        val chatEndpoint: String get() = "$lanBaseUrl/chat/completions"
        val modelsEndpoint: String get() = "$lanBaseUrl/models"
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshNetwork()
    }

    fun refreshNetwork() {
        _status.update { it.copy(port = HubSettings.config().port, lanIp = detectLanIp()) }
    }

    fun start(context: Context) {
        val config = HubSettings.config()
        HubSettings.saveConfig(desiredRunning = true)
        _status.update {
            it.copy(
                state = State.STARTING,
                port = config.port,
                lanIp = detectLanIp(),
                message = "Binding HTTP listener to port ${config.port}...",
                lastError = null
            )
        }
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, GatewayService::class.java).setAction(GatewayService.ACTION_START)
        )
    }

    fun stop(context: Context) {
        HubSettings.saveConfig(desiredRunning = false)
        _status.update { it.copy(state = State.STOPPED, message = "Gateway stopped", lastError = null) }
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, GatewayService::class.java).setAction(GatewayService.ACTION_STOP)
            )
        }.onFailure { error -> GatewayLog.add("Could not signal gateway service: ${error.message}") }
    }

    fun report(state: State, message: String, error: String? = null) {
        _status.update {
            it.copy(
                state = state,
                port = HubSettings.config().port,
                lanIp = if (it.lanIp.isBlank()) detectLanIp() else it.lanIp,
                message = message,
                lastError = error
            )
        }
    }

    fun noteRequest() {
        _status.update { it.copy(requestCount = it.requestCount + 1) }
    }

    /** LAN address of the active Wi-Fi/Ethernet interface, without needing location permission. */
    fun detectLanIp(): String {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrDefault(emptyList())
        val candidates = interfaces
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress } }
            .filter { (_, address) -> !address.isNullOrBlank() && !address.startsWith("169.254.") }
        return candidates.firstOrNull { (name, _) -> name.contains("wlan", ignoreCase = true) }?.second
            ?: candidates.firstOrNull { (name, _) -> name.contains("eth", ignoreCase = true) }?.second
            ?: candidates.firstOrNull()?.second
            ?: "127.0.0.1"
    }
}

/** Redacted, bounded in-memory activity log shown in the app. */
object GatewayLog {
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()

    fun add(message: String) {
        val line = "${formatter.format(Date())}  ${redact(message)}"
        _lines.update { (listOf(line) + it).take(60) }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
