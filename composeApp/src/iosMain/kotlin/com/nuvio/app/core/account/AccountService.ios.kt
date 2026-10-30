@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.core.account

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.ContentType
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIDevice
import platform.posix.exit

/**
 * Account + sync for the iPad app.
 *
 * All app data lives in the standard UserDefaults domain. It is split into 16 buckets by
 * key and uploaded as sections `ipad.d0` … `ipad.d15`; the iPad never reads or writes the
 * `pc.*` (desktop) or `tv.*` (Apple TV) sections. Data fetched from the server is staged
 * in a separate suite and applied on the next launch, before any store reads it.
 */
actual object AccountService {
    actual val supported: Boolean = true

    private const val DefaultServerUrl = "https://sync.mudworld.space"
    private const val SectionPrefix = "ipad.d"
    private const val BucketCount = 16
    private const val PushIntervalMs = 600_000L
    private const val RequestDebounceMs = 5_000L
    private const val DesktopPrefix = "pc."
    private const val ImportKey = "import.pc"

    /** Device-local state, caches and system keys that must not follow the account. */
    private val excludedPrefixes = listOf(
        "NS", "Apple", "AK", "PK", "com.apple.", "WebKit", "INNext", "MSV",
        "sync_client", "server_configuration", "server_credentials", "member_access",
        "sentry", "app_icon", "pending_external_playback", "downloads",
        "stream_link_cache", "binge_group_cache", "continue_watching_enrichment",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(Darwin) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }
    private val defaults get() = NSUserDefaults.standardUserDefaults
    private val accountStore = NSUserDefaults(suiteName = "space.mudworld.watchparty.account")
    private val stagingStore = NSUserDefaults(suiteName = "space.mudworld.watchparty.staging")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pushMutex = Mutex()

    private var prepared = false
    private var pendingRequest: Job? = null

    private val _session = MutableStateFlow<AccountSession?>(null)
    actual val session: StateFlow<AccountSession?> = _session.asStateFlow()

    private val _syncStatus = MutableStateFlow(AccountSyncStatus())
    actual val syncStatus: StateFlow<AccountSyncStatus> = _syncStatus.asStateFlow()

    actual val serverUrl: String
        get() = accountStore.stringForKey("server_url")?.takeIf { it.isNotBlank() } ?: DefaultServerUrl

    private val token: String?
        get() = accountStore.stringForKey("token")?.takeIf { it.isNotBlank() }

    private val domainName: String
        get() = NSBundle.mainBundle.bundleIdentifier ?: "space.mudworld.watchparty.ipad"

    // ---- start-up --------------------------------------------------------

    /** Runs before the first Compose screen: applies staged data, then starts the timers. */
    fun prepareOnLaunch() {
        if (prepared) return
        prepared = true
        applyStagedBuckets()
        loadSession()
        startPushLoop()
    }

    private fun loadSession() {
        val userId = accountStore.stringForKey("user_id")
        val username = accountStore.stringForKey("username")
        _session.value = if (token != null && userId != null && username != null) {
            AccountSession(userId, username, accountStore.boolForKey("is_admin"))
        } else {
            null
        }
    }

    // ---- sign-in -----------------------------------------------------------

    actual suspend fun signIn(username: String, password: String): Result<AccountSignInOutcome> =
        authenticate("/v1/login", mapOf("username" to username, "password" to password))

    actual suspend fun register(username: String, password: String, invite: String): Result<AccountSignInOutcome> =
        authenticate("/v1/register", mapOf("username" to username, "password" to password, "invite" to invite))

    private suspend fun authenticate(path: String, fields: Map<String, String>): Result<AccountSignInOutcome> =
        runCatching {
            val body = JsonObject((fields + ("device" to deviceName())).mapValues { JsonPrimitive(it.value) })
            val sessionJson = json.parseToJsonElement(request(HttpMethod.Post, path, body.toString(), null)).jsonObject
            accountStore.setObject(sessionJson.string("token"), forKey = "token")
            accountStore.setObject(sessionJson.string("userId"), forKey = "user_id")
            accountStore.setObject(sessionJson.string("username"), forKey = "username")
            accountStore.setBool(sessionJson["isAdmin"]?.jsonPrimitive?.booleanOrNull == true, forKey = "is_admin")
            accountStore.removeObjectForKey("versions")
            accountStore.removeObjectForKey("hashes")
            loadSession()

            val remote = listRemoteSections().filterKeys { it.startsWith(SectionPrefix) }
            if (remote.isEmpty() && runCatching { stageDesktopImport() }.getOrDefault(0) > 0) {
                // First iPad of this account: start from a copy of the PC's data (applied after the restart).
                if (AuthRepository.state.value !is AuthState.Authenticated) {
                    AuthRepository.signInAnonymously()
                }
                AccountSignInOutcome.RestartRequired
            } else if (remote.isEmpty()) {
                pushChangedBuckets(force = true)
                if (AuthRepository.state.value !is AuthState.Authenticated) {
                    AuthRepository.signInAnonymously()
                }
                AccountSignInOutcome.UploadedLocalData
            } else {
                pullNewerSections(forceAll = true)
                AccountSignInOutcome.RestartRequired
            }
        }.onFailure(::recordError)

    actual suspend fun signOut() {
        if (token != null) {
            runCatching { pushChangedBuckets(force = false) }
            runCatching { request(HttpMethod.Post, "/v1/logout", "{}", token) }
        }
        defaults.removePersistentDomainForName(domainName)
        defaults.synchronize()
        clearSuite(accountStore)
        clearSuite(stagingStore)
        restartApp()
    }

    actual suspend fun createInvite(): Result<String> = runCatching {
        json.parseToJsonElement(request(HttpMethod.Post, "/v1/invites", "{}", requireToken())).jsonObject.string("code")
    }

    actual suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> = runCatching {
        val body = JsonObject(mapOf("oldPassword" to JsonPrimitive(oldPassword), "newPassword" to JsonPrimitive(newPassword)))
        request(HttpMethod.Post, "/v1/password", body.toString(), requireToken())
        Unit
    }

    // ---- one-time copy of the PC's data ----------------------------------

    actual val canImportFromDesktop: Boolean = true

    actual suspend fun importFromDesktop(): Result<Int> = runCatching { stageDesktopImport() }

    /**
     * Downloads the PC's sections (`pc.*`, java .properties files), translates the keys the iPad
     * stores differently and stages the result; it is written into UserDefaults on the next launch,
     * before any repository reads it. The PC sections are only read, never changed.
     */
    private suspend fun stageDesktopImport(): Int {
        val authToken = requireToken()
        val values = LinkedHashMap<String, String>()
        for (section in listRemoteSections().keys.sorted()) {
            if (!section.startsWith(DesktopPrefix)) continue
            val rule = desktopImportRules[section.removePrefix(DesktopPrefix)] ?: continue
            val content = json.parseToJsonElement(request(HttpMethod.Get, "/v1/data/$section", null, authToken))
                .jsonObject["data"]?.jsonPrimitive?.contentOrNull ?: continue
            for ((key, value) in parseProperties(content)) {
                rule(key, value)?.let { (iosKey, iosValue) -> values[iosKey] = iosValue }
            }
        }
        if (values.isEmpty()) throw AccountException("nothing_to_import")
        stagingStore.setObject(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString(), forKey = ImportKey)
        stagingStore.synchronize()
        _syncStatus.value = _syncStatus.value.copy(pendingRestart = true)
        return values.size
    }

    private fun sameKey(key: String, value: String): Pair<String, String> = key to value

    /** `<from>` or `<from>_<profile>` on the PC is `<to>` / `<to>_<profile>` on the iPad. */
    private fun renamed(from: String, to: String): (String, String) -> Pair<String, String>? = { key, value ->
        val suffix = key.removePrefix("${from}_")
        when {
            key == from -> to to value
            key.startsWith("${from}_") && suffix.isNotEmpty() && suffix.all { it.isDigit() } -> "${to}_$suffix" to value
            else -> null
        }
    }

    /** PC store → iPad key/value. Stores not listed (caches, device state, auth, plugins) are not copied. */
    private val desktopImportRules: Map<String, (String, String) -> Pair<String, String>?> by lazy {
        mapOf(
            "nuvio_addons" to { key, value -> convertDesktopAddons(key, value) },
            "nuvio_tmdb_settings" to { key, value -> sameKey(key, value) },
            "nuvio_debrid_settings" to { key, value -> sameKey(key, value) },
            "nuvio_theme_settings" to { key, value -> sameKey(key, value) },
            "nuvio_stream_badge_settings" to { key, value -> sameKey(key, value) },
            "nuvio_mdblist_settings" to { key, value -> sameKey(key, value) },
            "nuvio_library_display_settings" to { key, value -> sameKey(key, value) },
            "nuvio_card_depth_style" to { key, value -> sameKey(key, value) },
            "nuvio_player_track_preferences" to { key, value -> sameKey(key, value) },
            "torrent_settings" to { key, value -> sameKey(key, value) },
            "episode_shuffle" to { key, value -> sameKey(key, value) },
            "nuvio_player_settings" to { key, value ->
                (key to value).takeIf {
                    key.startsWith("preferred_") || key.startsWith("secondary_preferred_") || key.startsWith("subtitle_")
                }
            },
            "nuvio_profiles" to renamed("profiles", "profile_payload"),
            "nuvio_avatars" to renamed("avatars", "avatar_catalog_payload"),
            "nuvio_collections" to renamed("collections", "collections_payload"),
            "nuvio_collection_mobile_settings" to renamed("collection_mobile_settings", "collection_mobile_settings_payload"),
            "nuvio_home_catalog_settings" to renamed("home_catalog_settings", "catalog_settings_payload"),
            "nuvio_meta_screen_settings" to renamed("meta_screen_settings", "meta_screen_settings_payload"),
            "nuvio_continue_watching_preferences" to
                renamed("continue_watching_preferences", "continue_watching_preferences_payload"),
            "nuvio_poster_card_style" to renamed("poster_card_style", "poster_card_style_payload"),
            "nuvio_episode_release_notifications" to
                renamed("episode_release_notifications", "episode_release_notifications_payload"),
            "nuvio_library" to renamed("library", "library_payload"),
            "nuvio_watched" to renamed("watched", "watched_payload"),
            "nuvio_watch_progress" to renamed("watch_progress", "watch_progress_payload"),
            "nuvio_search_history" to { key, value ->
                renamed("search_history", "search_history_payload")(key, value)
                    ?: (key to value).takeIf { key.startsWith("recent_searches_enabled") }
            },
            // Live TV: only the playlists; channel ids (favorites, recents) are computed per device.
            "nuvio_livetv" to { key, value -> ("livetv_playlists" to value).takeIf { key == "playlists" } },
        )
    }

    /** PC: JSON list / JSON map; iPad: one URL per line / `url<TAB>enabled` per line. */
    private fun convertDesktopAddons(key: String, value: String): Pair<String, String>? {
        val urlsPrefix = "installed_addon_urls_"
        val statesPrefix = "addon_enabled_states_"
        return when {
            key.startsWith(urlsPrefix) -> runCatching {
                val urls = json.parseToJsonElement(value).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                "installed_manifest_urls_${key.removePrefix(urlsPrefix)}" to urls.joinToString("\n")
            }.getOrNull()
            key.startsWith(statesPrefix) -> runCatching {
                val states = json.parseToJsonElement(value).jsonObject.mapNotNull { (url, enabled) ->
                    enabled.jsonPrimitive.booleanOrNull?.let { "$url\t$it" }
                }
                "installed_manifest_enabled_states_${key.removePrefix(statesPrefix)}" to states.joinToString("\n")
            }.getOrNull()
            else -> null
        }
    }

    /** Minimal java.util.Properties reader (comments, `=`/`:`/space separators, escapes, continuations). */
    private fun parseProperties(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val lines = text.split('\n')
        val blanks = charArrayOf(' ', '\t', '\u000c')
        var index = 0
        while (index < lines.size) {
            var line = lines[index++].trimEnd('\r').trimStart(*blanks)
            if (line.isEmpty() || line[0] == '#' || line[0] == '!') continue
            while (line.takeLastWhile { it == '\\' }.length % 2 == 1 && index < lines.size) {
                line = line.dropLast(1) + lines[index++].trimEnd('\r').trimStart(*blanks)
            }
            var position = 0
            val key = StringBuilder()
            while (position < line.length) {
                val char = line[position]
                if (char == '\\' && position + 1 < line.length) {
                    key.append(char).append(line[position + 1])
                    position += 2
                    continue
                }
                if (char == '=' || char == ':' || char in blanks) break
                key.append(char)
                position++
            }
            while (position < line.length && line[position] in blanks) position++
            if (position < line.length && (line[position] == '=' || line[position] == ':')) position++
            while (position < line.length && line[position] in blanks) position++
            result[unescapeProperty(key.toString())] = unescapeProperty(line.substring(position))
        }
        return result
    }

    private fun unescapeProperty(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var position = 0
        while (position < text.length) {
            val char = text[position++]
            if (char != '\\' || position >= text.length) {
                out.append(char)
                continue
            }
            when (val next = text[position++]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                'f' -> out.append('\u000c')
                'u' -> {
                    val hex = text.substring(position, minOf(position + 4, text.length))
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 4 && code != null) {
                        out.append(code.toChar())
                        position += 4
                    } else {
                        out.append('u')
                    }
                }
                else -> out.append(next)
            }
        }
        return out.toString()
    }

    actual suspend fun syncNow(): Result<Unit> =
        runCatching { pushChangedBuckets(force = false) }.onFailure(::recordError)

    actual fun requestSync() {
        if (token == null) return
        pendingRequest?.cancel()
        pendingRequest = scope.launch {
            delay(RequestDebounceMs)
            runCatching { pushChangedBuckets(force = false) }.onFailure(::recordError)
        }
    }

    /** iOS apps cannot relaunch themselves: close the app, the user opens it again. */
    actual fun restartApp() {
        defaults.synchronize()
        exit(0)
    }

    // ---- sync engine -----------------------------------------------------

    private fun startPushLoop() {
        scope.launch {
            while (true) {
                delay(PushIntervalMs)
                if (token != null) {
                    runCatching {
                        pushChangedBuckets(force = false)
                        pullNewerSections()
                    }.onFailure(::recordError)
                }
            }
        }
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> pushInBackground() }
    }

    /** Uploads while iOS still gives the app a few seconds after leaving the foreground. */
    private fun pushInBackground() {
        if (token == null) return
        val application = UIApplication.sharedApplication
        var taskId = UIBackgroundTaskInvalid
        taskId = application.beginBackgroundTaskWithExpirationHandler {
            application.endBackgroundTask(taskId)
            taskId = UIBackgroundTaskInvalid
        }
        scope.launch {
            runCatching { pushChangedBuckets(force = false) }.onFailure(::recordError)
            if (taskId != UIBackgroundTaskInvalid) application.endBackgroundTask(taskId)
        }
    }

    private fun bucketOf(key: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (char in key) {
            hash = (hash xor char.code) * 0x01000193
        }
        return (hash.toUInt() % BucketCount.toUInt()).toInt()
    }

    private fun isSyncable(key: String): Boolean = excludedPrefixes.none { key.startsWith(it) }

    /** Current UserDefaults content, grouped per bucket as sorted JSON objects. */
    private fun snapshotBuckets(): Map<Int, String> {
        val domain = defaults.persistentDomainForName(domainName) ?: emptyMap<Any?, Any?>()
        val buckets = HashMap<Int, MutableMap<String, JsonElement>>()
        for ((rawKey, value) in domain) {
            val key = rawKey as? String ?: continue
            if (!isSyncable(key)) continue
            val element = toJson(value) ?: continue
            buckets.getOrPut(bucketOf(key)) { HashMap() }[key] = element
        }
        return (0 until BucketCount).associateWith { bucket ->
            JsonObject((buckets[bucket] ?: emptyMap()).entries.sortedBy { it.key }.associate { it.key to it.value }).toString()
        }
    }

    private suspend fun pushChangedBuckets(force: Boolean) = pushMutex.withLock {
        val authToken = token ?: return@withLock
        _syncStatus.value = _syncStatus.value.copy(syncing = true)
        try {
            val hashes = decodeMap(accountStore.stringForKey("hashes"))
            val versions = decodeMap(accountStore.stringForKey("versions"))
            val staged = stagingStore.dictionaryRepresentation().keys
            for ((bucket, content) in snapshotBuckets()) {
                val name = bucket.toString()
                if ("$SectionPrefix$name" in staged) continue
                val hash = fnv64(content)
                if (!force && hashes[name] == hash) continue
                val body = JsonObject(mapOf("data" to JsonPrimitive(content))).toString()
                val response = json.parseToJsonElement(
                    request(HttpMethod.Put, "/v1/data/$SectionPrefix$name", body, authToken),
                )
                hashes[name] = hash
                versions[name] = response.jsonObject["updatedAt"]?.jsonPrimitive?.longOrNull?.toString() ?: "0"
            }
            accountStore.setObject(encodeMap(hashes), forKey = "hashes")
            accountStore.setObject(encodeMap(versions), forKey = "versions")
            _syncStatus.value = AccountSyncStatus(lastSyncEpochMs = nowMs(), pendingRestart = hasStagedBuckets())
        } finally {
            _syncStatus.value = _syncStatus.value.copy(syncing = false)
        }
    }

    /** Downloads newer `ipad.*` sections into the staging suite; applied on the next launch. */
    private suspend fun pullNewerSections(forceAll: Boolean = false) {
        val authToken = token ?: return
        val remote = listRemoteSections()
        val versions = decodeMap(accountStore.stringForKey("versions"))
        val hashes = decodeMap(accountStore.stringForKey("hashes"))
        for ((section, updatedAt) in remote) {
            if (!section.startsWith(SectionPrefix)) continue
            val name = section.removePrefix(SectionPrefix)
            val bucket = name.toIntOrNull() ?: continue
            if (bucket !in 0 until BucketCount) continue
            val known = versions[name]?.toLongOrNull() ?: 0L
            if (!forceAll && updatedAt <= known) continue
            val sectionJson = json.parseToJsonElement(request(HttpMethod.Get, "/v1/data/$section", null, authToken)).jsonObject
            val content = sectionJson["data"]?.jsonPrimitive?.contentOrNull ?: continue
            stagingStore.setObject(content, forKey = section)
            versions[name] = updatedAt.toString()
            hashes[name] = fnv64(content)
        }
        stagingStore.synchronize()
        accountStore.setObject(encodeMap(versions), forKey = "versions")
        accountStore.setObject(encodeMap(hashes), forKey = "hashes")
        _syncStatus.value = AccountSyncStatus(lastSyncEpochMs = nowMs(), pendingRestart = hasStagedBuckets())
    }

    private fun hasStagedBuckets(): Boolean =
        stagingStore.stringForKey(ImportKey) != null ||
            stagingStore.dictionaryRepresentation().keys.any { (it as? String)?.startsWith(SectionPrefix) == true }

    /** Replaces each staged bucket's keys in UserDefaults (keys missing remotely are removed). */
    private fun applyStagedBuckets() {
        stagingStore.stringForKey(ImportKey)?.let { raw ->
            runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()?.forEach { (key, element) ->
                val value = element.jsonPrimitive.contentOrNull ?: return@forEach
                if (isSyncable(key)) defaults.setObject(value, forKey = key)
            }
            stagingStore.removeObjectForKey(ImportKey)
            defaults.synchronize()
            stagingStore.synchronize()
        }
        val staged = stagingStore.dictionaryRepresentation()
        if (staged.keys.none { (it as? String)?.startsWith(SectionPrefix) == true }) return
        val current = defaults.persistentDomainForName(domainName)?.keys?.mapNotNull { it as? String }.orEmpty()
        for ((rawSection, rawContent) in staged) {
            val section = rawSection as? String ?: continue
            val bucket = section.removePrefix(SectionPrefix).toIntOrNull() ?: continue
            val content = rawContent as? String ?: continue
            val values = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull() ?: continue
            current.filter { isSyncable(it) && bucketOf(it) == bucket && it !in values }
                .forEach { defaults.removeObjectForKey(it) }
            for ((key, element) in values) {
                if (!isSyncable(key) || bucketOf(key) != bucket) continue
                val value = fromJson(element)
                if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
            }
            stagingStore.removeObjectForKey(section)
        }
        defaults.synchronize()
        stagingStore.synchronize()
    }

    private suspend fun listRemoteSections(): Map<String, Long> {
        val response = json.parseToJsonElement(request(HttpMethod.Get, "/v1/data", null, requireToken())).jsonObject
        val sections = response["sections"]?.jsonObject ?: return emptyMap()
        return sections.mapValues { (_, value) -> value.jsonPrimitive.longOrNull ?: 0L }
    }

    // ---- value conversion ------------------------------------------------

    private fun toJson(value: Any?): JsonElement? = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is NSNumber -> when (value.objCType?.toKString()) {
            "c", "B" -> JsonPrimitive(value.boolValue)
            "f", "d" -> JsonPrimitive(value.doubleValue)
            else -> JsonPrimitive(value.longLongValue)
        }
        is List<*> -> JsonArray(value.map { toJson(it) ?: return null })
        is Map<*, *> -> JsonObject(
            value.entries.associate { (k, v) -> (k as? String ?: return null) to (toJson(v) ?: return null) },
        )
        else -> null // NSData, NSDate, … stay on this device
    }

    private fun fromJson(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull
        }
        // UserDefaults cannot store NSNull, so nested nulls are dropped.
        is JsonArray -> element.mapNotNull(::fromJson)
        is JsonObject -> element.mapNotNull { (k, v) -> fromJson(v)?.let { k to it } }.toMap()
    }

    // ---- HTTP + helpers ----------------------------------------------------

    private suspend fun request(method: HttpMethod, path: String, body: String?, authToken: String?): String {
        val response = try {
            http.request(serverUrl.trimEnd('/') + path) {
                this.method = method
                header("User-Agent", "WatchParty-iPad")
                authToken?.let { header("Authorization", "Bearer $it") }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (e: Exception) {
            throw AccountException("network")
        }
        val text = response.bodyAsText()
        val status = response.status.value
        if (status in 200..299) return text
        val code = runCatching {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: "http_$status"
        if (status == 401 && authToken != null) clearSession()
        throw AccountException(code)
    }

    private fun requireToken(): String = token ?: throw AccountException("unauthorized")

    private fun clearSession() {
        listOf("token", "user_id", "username", "is_admin").forEach { accountStore.removeObjectForKey(it) }
        _session.value = null
    }

    private fun clearSuite(store: NSUserDefaults) {
        store.dictionaryRepresentation().keys.forEach { key -> (key as? String)?.let { store.removeObjectForKey(it) } }
        store.synchronize()
    }

    private fun recordError(error: Throwable) {
        val code = (error as? AccountException)?.code ?: "network"
        _syncStatus.value = _syncStatus.value.copy(syncing = false, lastError = code)
    }

    private fun deviceName(): String = "iPad " + UIDevice.currentDevice.name

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    private fun fnv64(value: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (char in value) {
            hash = (hash xor char.code.toLong()) * 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }

    private fun decodeMap(raw: String?): MutableMap<String, String> =
        raw?.let {
            runCatching {
                json.parseToJsonElement(it).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }.toMutableMap()
            }.getOrNull()
        } ?: mutableMapOf()

    private fun encodeMap(map: Map<String, String>): String =
        JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: throw AccountException("invalid_response")
}
