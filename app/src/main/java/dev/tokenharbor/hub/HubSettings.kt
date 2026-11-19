package dev.tokenharbor.hub

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Keystore-backed storage for gateway settings and upstream credentials.
 * Provider metadata is stored as JSON; API keys live in separate encrypted
 * entries so they are never written to the metadata blob.
 */
object HubSettings : ProviderStore {
    private const val PREFS_NAME = "token_harbor_secure_prefs"
    private const val KEY_PROVIDERS = "providers_json"
    private const val KEY_PORT = "gateway_port"
    private const val KEY_MASTER_KEY = "gateway_master_key"
    private const val KEY_MODEL = "default_model"
    private const val KEY_RUNNING = "desired_running"
    private const val KEY_PREFIX = "pk_"

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) prefs = create(context.applicationContext)
        }
    }

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            open(context, masterKey)
        } catch (error: Exception) {
            // A lost/rotated Android Keystore key makes the preferences file unreadable.
            context.deleteSharedPreferences(PREFS_NAME)
            open(context, masterKey)
        }
    }

    private fun open(context: Context, masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private fun store(): SharedPreferences =
        prefs ?: error("Secure storage is not initialized. Call HubSettings.init(context) first.")

    fun config(): GatewayConfig {
        val p = store()
        if (!p.contains(KEY_MASTER_KEY)) {
            p.edit().putString(KEY_MASTER_KEY, "thub-change-this-key").apply()
        }
        return GatewayConfig(
            port = (p.getString(KEY_PORT, null)?.toIntOrNull() ?: 8080).coerceIn(1, 65_535),
            masterKey = p.getString(KEY_MASTER_KEY, "") ?: "",
            defaultModel = p.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
                ?: "deepseek-v4.1-flash:free"
        )
    }

    fun saveConfig(
        port: String? = null,
        masterKey: String? = null,
        defaultModel: String? = null,
        desiredRunning: Boolean? = null
    ) {
        store().edit().apply {
            port?.let { putString(KEY_PORT, it.trim()) }
            masterKey?.let { putString(KEY_MASTER_KEY, it) }
            defaultModel?.let { putString(KEY_MODEL, it.trim()) }
            desiredRunning?.let { putBoolean(KEY_RUNNING, it) }
            apply()
        }
    }

    fun desiredRunning(): Boolean = store().getBoolean(KEY_RUNNING, false)

    /** True when a decrypted upstream credential is present. Never exposes the value. */
    override fun hasKey(accountId: String): Boolean = runCatching {
        val value = store().getString(KEY_PREFIX + accountId, null)
        !value.isNullOrBlank()
    }.getOrDefault(false)

    override fun load(): List<ApiAccount> = loadAccounts()

    override fun save(accounts: List<ApiAccount>) = saveAccounts(accounts)

    fun loadAccounts(): List<ApiAccount> {
        val p = store()
        val raw = p.getString(KEY_PROVIDERS, null) ?: return emptyList()
        val array = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (obj[key] as? JsonPrimitive)?.content.orEmpty()
            val id = text("id").ifBlank { UUID.randomUUID().toString() }
            // Decrypt the credential from the Keystore-backed store. A per-provider
            // failure must not hide other providers, and the value is never logged.
            val decryptedKey = runCatching { p.getString(KEY_PREFIX + id, null) }
                .onFailure { error ->
                    GatewayLog.add(
                        "Credential read failed for provider id=$id: " +
                            "${error::class.simpleName} (stored key may be unavailable)"
                    )
                }
                .getOrNull()
            ApiAccount(
                name = text("name").ifBlank { "Provider" },
                key = decryptedKey ?: text("legacyKey"),
                baseUrl = text("baseUrl").ifBlank { "https://tokenharbor.ai/v1" },
                enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                id = id,
                models = text("models").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                format = runCatching { ProviderFormat.valueOf(text("format")) }
                    .getOrDefault(ProviderFormat.OPENAI),
                apiVersion = text("apiVersion"),
                priority = text("priority").toIntOrNull() ?: 0
            )
        }
    }

    fun saveAccounts(accounts: List<ApiAccount>) {
        val p = store()
        val normalized = accounts.map { account ->
            account.copy(id = account.id.ifBlank { UUID.randomUUID().toString() })
        }
        val ids = normalized.map { it.id }.toSet()

        val payload: JsonArray = buildJsonArray {
            normalized.forEach { account ->
                add(buildJsonObject {
                    put("id", account.id)
                    put("name", account.name)
                    put("baseUrl", account.baseUrl)
                    put("enabled", account.enabled)
                    put("models", account.models.joinToString(","))
                    put("format", account.format.name)
                    put("apiVersion", account.apiVersion)
                    put("priority", account.priority)
                })
            }
        }

        p.edit().apply {
            putString(KEY_PROVIDERS, payload.toString())
            normalized.forEach { putString(KEY_PREFIX + it.id, it.key) }
            p.all.keys
                .filter { it.startsWith(KEY_PREFIX) && it.removePrefix(KEY_PREFIX) !in ids }
                .forEach { remove(it) }
            apply()
        }
    }
}
