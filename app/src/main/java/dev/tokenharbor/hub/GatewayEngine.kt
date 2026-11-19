package dev.tokenharbor.hub

/**
 * Gateway routing core scaffold.
 *
 * Production implementation must:
 * - Authenticate the local master key.
 * - Parse the requested model and request body.
 * - Attempt enabled provider credentials in priority order.
 * - Fail over on 429, transient 5xx, connect failures and timeouts.
 * - Never retry after streaming bytes have been sent to the client.
 * - Preserve the upstream model ID and SSE stream.
 *
 * This file intentionally does not guess Token Harbor's upstream API contract.
 */
class GatewayEngine(
    private val accounts: () -> List<ApiAccount>
) {
    fun availableAccounts(): List<ApiAccount> = accounts().filter { it.enabled }
}
