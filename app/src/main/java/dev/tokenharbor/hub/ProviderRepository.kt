package dev.tokenharbor.hub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent, encrypted provider store. Implemented by HubSettings on Android
 * and by an in-memory fixture in JVM tests so the same code path is exercised.
 */
interface ProviderStore {
    fun load(): List<ApiAccount>
    fun save(accounts: List<ApiAccount>)
    /** True when a decrypted credential is actually available for [accountId]. */
    fun hasKey(accountId: String): Boolean
}

/**
 * The single source of truth for provider configuration.
 *
 * Every read goes through the persistent store, so the APIs screen, the
 * foreground service, GET /v1/models and POST /v1/chat/completions can never
 * disagree. The StateFlow is only an observable mirror for the UI; routing
 * decisions always use a fresh read from the persistent store.
 */
object ProviderRepository {

    private val _providers = MutableStateFlow<List<ApiAccount>>(emptyList())
    val providers: StateFlow<List<ApiAccount>> = _providers.asStateFlow()

    @Volatile
    private var store: ProviderStore? = null

    /** Installs the backing store and immediately verifies what is on disk. */
    @Synchronized
    fun install(store: ProviderStore): List<ApiAccount> {
        this.store = store
        return refresh()
    }

    private fun store(): ProviderStore =
        store ?: error("ProviderRepository is not initialized; install a ProviderStore first")

    /**
     * Reads the encrypted persistent store right now. Never returns a cached
     * list, so a service that was started before a provider was added still
     * sees the provider on its next request.
     */
    @Synchronized
    fun load(): List<ApiAccount> {
        val result = runCatching { store().load() }
            .onFailure { error ->
                GatewayLog.add(
                    "Provider store read failed: ${error::class.simpleName}: " +
                        (error.message ?: "no message")
                )
            }
            .getOrElse { emptyList() }
        _providers.value = result
        return result
    }

    /** Forces a fresh read after service startup, process recreation or an update. */
    fun refresh(): List<ApiAccount> = load()

    /**
     * Persists the full provider list, then reloads from disk so the observable
     * state and the routing engine see exactly what was stored.
     */
    @Synchronized
    fun save(accounts: List<ApiAccount>): List<ApiAccount> {
        store().save(accounts)
        return refresh()
    }

    /** Providers that are enabled, ordered by their configured priority. */
    fun enabled(): List<ApiAccount> = load().filter { it.enabled }.sortedBy { it.priority }

    /** Models that can genuinely be routed: enabled provider, decrypted key, configured ID. */
    fun routableModels(): List<String> = enabled()
        .filter { hasKey(it.id) }
        .flatMap { it.models }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

    /** Credential presence check only; never returns or logs the key itself. */
    fun hasKey(accountId: String): Boolean = runCatching { store().hasKey(accountId) }
        .getOrDefault(false)

    /**
     * Redacted snapshot for diagnostics. Contains counts and IDs only, never
     * API keys or key material.
     */
    fun diagnosticSummary(): String {
        val all = load()
        val enabled = all.filter { it.enabled }
        return "providers=total:${all.size} enabled:${enabled.size} " +
            "withKey:${enabled.count { hasKey(it.id) }} " +
            "models:${enabled.sumOf { it.models.size }} " +
            "[" + enabled.joinToString(", ") { p ->
                "${p.id}:enabled=${p.enabled}:keys=${hasKey(p.id)}:models=${p.models.size}"
            } + "]"
    }
}
