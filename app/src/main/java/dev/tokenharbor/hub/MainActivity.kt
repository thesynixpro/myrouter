
package dev.tokenharbor.hub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ApiAccount(
    val name: String,
    val key: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val id: String = UUID.randomUUID().toString(),
    val models: List<String> = emptyList(),
    val format: ProviderFormat = ProviderFormat.OPENAI,
    val apiVersion: String = "",
    val priority: Int = 0
)

data class UiState(
    val accounts: List<ApiAccount> = emptyList(),
    val serverRunning: Boolean = false,
    val masterKey: String = "thub-change-this-key",
    val port: String = "8080",
    val model: String = "deepseek-v4.1-flash:free",
    val log: String = "Ready. Add a Token Harbor API key to begin.",
    val statusState: GatewayController.State = GatewayController.State.STOPPED,
    val statusMessage: String = "Gateway stopped",
    val lastError: String? = null,
    val lanIp: String = "127.0.0.1",
    val requestCount: Int = 0
)

class HubViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        val config = HubSettings.config()
        ProviderRepository.install(HubSettings)
        _state.value = _state.value.copy(
            accounts = ProviderRepository.refresh(),
            port = config.port.toString(),
            masterKey = config.masterKey,
            model = config.defaultModel
        )

        // The repository is the single source of truth; keep the UI in sync
        // with whatever the gateway service loaded from persistent storage.
        viewModelScope.launch {
            ProviderRepository.providers.collect { list ->
                if (_state.value.accounts != list) {
                    _state.value = _state.value.copy(accounts = list)
                }
            }
        }

        viewModelScope.launch {
            GatewayController.status.collect { status ->
                _state.value = _state.value.copy(
                    serverRunning = status.state == GatewayController.State.RUNNING,
                    statusState = status.state,
                    statusMessage = status.message,
                    lastError = status.lastError,
                    lanIp = status.lanIp,
                    port = status.port.toString(),
                    requestCount = status.requestCount
                )
            }
        }
        viewModelScope.launch {
            GatewayLog.lines.collect { lines ->
                _state.value = _state.value.copy(
                    log = lines.take(8).joinToString("\n").ifBlank {
                        "Ready. Add a provider with an API key and at least one model ID."
                    }
                )
            }
        }
    }

    fun add(
        name: String,
        key: String,
        url: String,
        models: String = "",
        format: ProviderFormat = ProviderFormat.OPENAI,
        apiVersion: String = ""
    ) {
        if (key.isBlank()) {
            GatewayLog.add("Provider was not added: API key is empty")
            return
        }
        val modelIds = models.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val account = ApiAccount(
            name = name.ifBlank { "Token Harbor ${_state.value.accounts.size + 1}" },
            key = key.trim(),
            baseUrl = normalizeBaseUrl(url).ifBlank { "https://tokenharbor.ai/v1" },
            enabled = true,
            models = modelIds,
            format = format,
            apiVersion = apiVersion.trim(),
            priority = _state.value.accounts.size
        )
        val next = _state.value.accounts + account
        _state.value = _state.value.copy(accounts = next)
        ProviderRepository.save(next)
        GatewayLog.add("Added provider '${account.name}' with ${modelIds.size} model ID(s), format=${format.name}")
    }

    fun remove(index: Int) {
        val next = _state.value.accounts.filterIndexed { i, _ -> i != index }
        _state.value = _state.value.copy(accounts = next)
        ProviderRepository.save(next)
        GatewayLog.add("Removed provider #${index + 1}")
    }

    fun setEnabled(index: Int, enabled: Boolean) {
        val next = _state.value.accounts.mapIndexed { i, account ->
            if (i == index) account.copy(enabled = enabled) else account
        }
        _state.value = _state.value.copy(accounts = next)
        ProviderRepository.save(next)
        GatewayLog.add("Provider '${next[index].name}' ${if (enabled) "enabled" else "disabled"}")
    }

    fun toggle(context: Context) {
        val start = !_state.value.serverRunning
        val port = _state.value.port.trim().toIntOrNull()
        if (start && (port == null || port !in 1..65_535)) {
            GatewayLog.add("Invalid port '${_state.value.port}'. Use a number from 1 to 65535.")
            return
        }
        if (start && _state.value.masterKey.isBlank()) {
            GatewayLog.add("Set a master API key before starting the gateway")
            return
        }

        HubSettings.saveConfig(
            port = _state.value.port,
            masterKey = _state.value.masterKey,
            defaultModel = _state.value.model
        )
        if (start) GatewayController.start(context) else GatewayController.stop(context)
    }

    fun update(transform: (UiState) -> UiState) {
        val next = transform(_state.value)
        _state.value = next
        HubSettings.saveConfig(port = next.port, masterKey = next.masterKey, defaultModel = next.model)
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HubSettings.init(applicationContext)
        ProviderRepository.install(HubSettings)
        GatewayController.init(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                GatewayLog.add(
                    if (granted) "Notification permission granted"
                    else "Notification permission denied; the foreground notification may be hidden"
                )
            }.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HubScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GatewayController.refreshNetwork()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(vm: HubViewModel = viewModel()) {

    val s by vm.state.collectAsState()
    val context = LocalContext.current

    var tab by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("https://tokenharbor.ai/v1") }
    var models by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(ProviderFormat.OPENAI) }
    var apiVersion by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Token Harbor Hub") })
        },
        bottomBar = {
            NavigationBar {
                listOf("Home", "APIs", "Server", "Settings").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                listOf(
                                    Icons.Default.Home,
                                    Icons.Default.Key,
                                    Icons.Default.Dns,
                                    Icons.Default.Settings
                                )[i],
                                contentDescription = label
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tab) {

                // HOME
                0 -> {
                    item {
                        Text("Your private AI gateway", style = MaterialTheme.typography.headlineSmall)
                    }
                    item { StatCard("API connections", s.accounts.count { it.enabled }.toString()) }
                    item { StatCard("Gateway", if (s.serverRunning) "Running" else "Stopped") }
                    item { Text("On-device Base URL: http://127.0.0.1:${s.port}/v1") }
                    item { Text("LAN Base URL: http://${s.lanIp}:${s.port}/v1") }
                    item { Text("Chat endpoint: http://${s.lanIp}:${s.port}/v1/chat/completions") }
                    item { Text("Model: ${s.model}") }
                    item {
                        Text(
                            "OpenCode: use the LAN Base URL as apiBaseURL and the master API key as apiKey.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // API MANAGER
                1 -> {
                    item { Text("Add AI Provider", style = MaterialTheme.typography.headlineSmall) }
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Connection name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            label = { Text("Provider API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = base,
                            onValueChange = { base = it },
                            label = { Text("Provider Base URL (e.g. https://tokenharbor.ai/v1)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = models,
                            onValueChange = { models = it },
                            label = { Text("Model IDs (comma-separated)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text("Request format", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderFormat.entries.forEach { option ->
                                FilterChip(
                                    selected = format == option,
                                    onClick = { format = option },
                                    label = { Text(option.name.replace("_", "-")) }
                                )
                            }
                        }
                    }
                    if (format == ProviderFormat.AZURE) {
                        item {
                            OutlinedTextField(
                                value = apiVersion,
                                onValueChange = { apiVersion = it },
                                label = { Text("Azure api-version") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                vm.add(name, key, base, models, format, apiVersion)
                                name = ""
                                key = ""
                                models = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add API") }
                    }

                    items(s.accounts) { account ->
                        val index = s.accounts.indexOf(account)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(account.name, style = MaterialTheme.typography.titleMedium)
                                Text(account.baseUrl, style = MaterialTheme.typography.bodySmall)
                                Text("Models: ${account.models.joinToString(", ").ifBlank { "none" }}", style = MaterialTheme.typography.bodySmall)
                                Text("Format: ${account.format.name}", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "API key stored encrypted in Android Keystore",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Row {
                                    TextButton(onClick = { vm.setEnabled(index, !account.enabled) }) {
                                        Text(if (account.enabled) "Disable" else "Enable")
                                    }
                                    TextButton(onClick = { vm.remove(index) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }

                // SERVER
                2 -> {
                    item { Text("Local Gateway Server", style = MaterialTheme.typography.headlineSmall) }
                    item { StatCard("Status", s.statusMessage) }
                    item { Text("Listener: ${s.statusState.name}   Requests: ${s.requestCount}") }
                    item { Text("LAN IP: ${s.lanIp}") }
                    item {
                        OutlinedTextField(
                            value = s.port,
                            onValueChange = { value -> vm.update { state -> state.copy(port = value) } },
                            label = { Text("Port") }
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = s.masterKey,
                            onValueChange = { value -> vm.update { state -> state.copy(masterKey = value) } },
                            label = { Text("Master API key (send as Bearer token)") }
                        )
                    }
                    item {
                        Button(onClick = { vm.toggle(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (s.serverRunning) "Stop Server" else "Start Server")
                        }
                    }
                    item { Text("On-device Base URL: http://127.0.0.1:${s.port}/v1") }
                    item { Text("LAN Base URL: http://${s.lanIp}:${s.port}/v1") }
                    item { Text("Models endpoint: http://${s.lanIp}:${s.port}/v1/models") }
                    item { Text("Chat endpoint: http://${s.lanIp}:${s.port}/v1/chat/completions") }
                    item {
                        Text(
                            "From OpenCode on another device use apiBaseURL = http://${s.lanIp}:${s.port}/v1 " +
                                "and apiKey = your master API key. Devices must be on the same Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (s.lastError != null) {
                        item { Text("Last error: ${s.lastError}", color = MaterialTheme.colorScheme.error) }
                    }
                }

                // SETTINGS
                3 -> {
                    item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
                    item {
                        OutlinedTextField(
                            value = s.model,
                            onValueChange = { value -> vm.update { state -> state.copy(model = value) } },
                            label = { Text("Default model ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item { Text("Routing: priority order, with retry and failover on 429 / 5xx / timeouts") }
                    item {
                        Text(
                            "Security: use a strong Master API key. Provider keys never leave the encrypted store."
                        )
                    }
                    item {
                        Button(
                            onClick = { requestBatteryExemption(context) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Ignore battery optimizations (recommended)") }
                    }
                    item {
                        Text(
                            "Android may still stop background services under memory pressure. The ongoing " +
                                "notification and START_STICKY improve reliability.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    item {
                        TextButton(onClick = { GatewayLog.clear() }) { Text("Clear activity log") }
                    }
                }
            }

            item {
                HorizontalDivider()
                Text("Activity", style = MaterialTheme.typography.titleMedium)
                Text(s.log)
            }
        }
    }
}

private fun requestBatteryExemption(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
        GatewayLog.add("Battery optimizations are already ignored")
        return
    }
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { error ->
        GatewayLog.add("Battery optimization request unavailable: ${error.message}")
    }
}

@Composable
fun StatCard(title: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}
