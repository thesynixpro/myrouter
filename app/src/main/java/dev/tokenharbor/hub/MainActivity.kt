package dev.tokenharbor.hub

import android.app.*
import android.content.*
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ApiAccount(val name: String, val key: String, val baseUrl: String, val enabled: Boolean = true)
data class UiState(
    val accounts: List<ApiAccount> = emptyList(),
    val serverRunning: Boolean = false,
    val masterKey: String = "thub-change-this-key",
    val port: String = "8080",
    val model: String = "deepseek-v4.1-flash:free",
    val log: String = "Ready. Add a Token Harbor API key to begin."
)

class HubViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    fun add(name: String, key: String, url: String) {
        if (key.isBlank()) return
        _state.value = _state.value.copy(
            accounts = _state.value.accounts + ApiAccount(name.ifBlank { "Token Harbor ${_state.value.accounts.size + 1}" }, key.trim(), url.trim().ifBlank { "https://tokenharbor.ai/v1" }),
            log = "Added API connection."
        )
    }
    fun remove(index: Int) {
        _state.value = _state.value.copy(accounts = _state.value.accounts.filterIndexed { i, _ -> i != index })
    }
    fun toggle() {
        val next = !_state.value.serverRunning
        _state.value = _state.value.copy(serverRunning = next, log = if (next) "Starting local gateway..." else "Stopping local gateway...")
    }
    fun update(transform: (UiState) -> UiState) { _state.value = transform(_state.value) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { HubScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(vm: HubViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("https://tokenharbor.ai/v1") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Token Harbor Hub") }) },
        bottomBar = {
            NavigationBar {
                listOf("Home", "APIs", "Server", "Settings").forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(listOf(Icons.Default.Home, Icons.Default.Key, Icons.Default.Dns, Icons.Default.Settings)[i], null) },
                        label = { Text(label) })
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                0 -> {
                    item { Text("Your private AI gateway", style = MaterialTheme.typography.headlineSmall) }
                    item { StatCard("API connections", s.accounts.size.toString()) }
                    item { StatCard("Gateway", if (s.serverRunning) "Running" else "Stopped") }
                    item { Text("OpenCode Base URL: http://127.0.0.1:${s.port}/v1") }
                    item { Text("Model: ${s.model}") }
                }
                1 -> {
                    item { Text("Add Token Harbor API", style = MaterialTheme.typography.headlineSmall) }
                    item { OutlinedTextField(name, { name = it }, label = { Text("Connection name") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(key, { key = it }, label = { Text("Token Harbor API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(base, { base = it }, label = { Text("Provider Base URL") }, modifier = Modifier.fillMaxWidth()) }
                    item { Button(onClick = { vm.add(name, key, base); name = ""; key = "" }, modifier = Modifier.fillMaxWidth()) { Text("Add API") } }
                    items(s.accounts) { account ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                                    Text(account.baseUrl, style = MaterialTheme.typography.bodySmall)
                                    Text("Key saved in current session only", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { vm.remove(s.accounts.indexOf(account)) }) { Text("Remove") }
                            }
                        }
                    }
                }
                2 -> {
                    item { Text("Local Gateway Server", style = MaterialTheme.typography.headlineSmall) }
                    item { StatCard("Status", if (s.serverRunning) "Running" else "Stopped") }
                    item { OutlinedTextField(s.port, { vm.update { it.copy(port = it) } }, label = { Text("Port") }) }
                    item { OutlinedTextField(s.masterKey, { vm.update { it.copy(masterKey = it) } }, label = { Text("Master API key") }) }
                    item { Button(onClick = vm::toggle, modifier = Modifier.fillMaxWidth()) { Text(if (s.serverRunning) "Stop Server" else "Start Server") } }
                    item { Text("Endpoint: http://127.0.0.1:${s.port}/v1") }
                }
                3 -> {
                    item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
                    item { OutlinedTextField(s.model, { vm.update { it.copy(model = it) } }, label = { Text("Default model ID") }, modifier = Modifier.fillMaxWidth()) }
                    item { Text("Routing: Priority / first available") }
                    item { Text("Security: use a strong Master API key before enabling a real server.") }
                }
            }
            item { HorizontalDivider(); Text("Activity", style = MaterialTheme.typography.titleMedium); Text(s.log) }
        }
    }
}

@Composable
fun StatCard(title: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}
