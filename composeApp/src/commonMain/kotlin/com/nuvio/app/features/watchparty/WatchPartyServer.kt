package com.nuvio.app.features.watchparty

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.httpRequestRaw
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Small platform hooks for the WatchParty extras (reports, error reports, TV approval). */
internal expect object WatchPartyPlatform {
    /** "pc", "ipad", "iphone" or "android". */
    val name: String
    val deviceName: String
    fun getString(key: String): String?
    fun putString(key: String, value: String?)

    /** Calls [onCrash] on the crashing thread before the app dies; it may only store small values. */
    fun installCrashCapture(onCrash: (Throwable) -> Unit)
}

internal class WatchPartyServerException(val code: String) : Exception(code)

/**
 * The WatchParty server's own endpoints (/v1/...) next to the Nuvio sync it emulates. Signed-in calls
 * use the sync session's access token, so every device that syncs can use them.
 */
internal object WatchPartyServer {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val baseUrl: String
        get() = ServerConfigurationRepository.active.value.backendUrl.trim().trimEnd('/')

    private suspend fun accessToken(): String? =
        runCatching { SupabaseProvider.client.auth.currentAccessTokenOrNull() }.getOrNull()

    suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        authorized: Boolean = true,
        maxBytes: Int = 256 * 1024,
    ): String {
        var refreshed = false
        while (true) {
            val headers = buildMap {
                put("Content-Type", "application/json")
                put("Accept", "application/json")
                if (authorized) {
                    val token = accessToken() ?: throw WatchPartyServerException("signed_out")
                    put("Authorization", "Bearer $token")
                }
            }
            val response = httpRequestRaw(
                method = method,
                url = baseUrl + path,
                headers = headers,
                body = body?.toString().orEmpty(),
                maxResponseBodyBytes = maxBytes,
            )
            if (response.status == 401 && authorized && !refreshed) {
                refreshed = true
                runCatching { SupabaseProvider.client.auth.refreshCurrentSession() }
                continue
            }
            if (response.status !in 200..299) {
                val code = runCatching {
                    json.parseToJsonElement(response.body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw WatchPartyServerException(code ?: "http_${response.status}")
            }
            return response.body
        }
    }
}
