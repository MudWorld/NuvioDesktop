package com.nuvio.app.core.account

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.system.exitProcess

/**
 * Account + sync for the desktop app.
 *
 * Every Nuvio store (`%APPDATA%/Nuvio/<name>.properties`) except device-local ones is
 * uploaded as section `pc.<name>`. On start-up, newer sections from the server are
 * written before any store is opened. Data fetched while the app runs is staged and
 * applied on the next start, so in-memory stores can never overwrite it.
 *
 * Profiles and everything kept per profile (continue watching, watched, library, addons,
 * plugins, collections) go through the app's own Nuvio sync, which the same server answers.
 * Signing in here also signs in that sync. Those stores are still uploaded as `pc.*` (other
 * devices read the PC's sidebar and addons from there) but are never pulled back.
 */
actual object AccountService {
    actual val supported: Boolean = true

    private const val DefaultServerUrl = "https://sync.mudworld.space"
    private const val SectionPrefix = "pc."
    private const val PushIntervalSeconds = 600L
    private const val RequestDebounceSeconds = 5L

    /** Nuvio sync signs in with an e-mail; WatchParty accounts are user names. */
    private const val AccountEmailDomain = "watchparty.local"
    private const val NuvioSyncSignedInKey = "nuvio_sync_signed_in"

    /** Device-local files, caches and server/session state that must not follow the account. */
    private val excludedStores = setOf(
        "nuvio_account",
        "nuvio_auth",
        "nuvio_window_state",
        "nuvio_sync_client_identity",
        "nuvio_player_runtime",
        "nuvio_resume_prompt",
        "nuvio_continue_watching_enrichment",
        "nuvio_binge_group_cache",
        "nuvio_stream_link_cache",
        "nuvio_desktop_renderer",
        "nuvio_sentry_settings",
        "nuvio_member_access",
        "nuvio_downloads",
        "nuvio_app_icon",
        "nuvio_updater",
        "server_configuration",
        "server_credentials",
    )

    /** Kept by the Nuvio sync per profile; a `pc.*` copy must not overwrite them. */
    private val nuvioSyncedStores = setOf(
        "nuvio_profiles",
        "nuvio_profile_pin_cache",
        "nuvio_avatars",
        "nuvio_addons",
        "nuvio_plugins",
        "nuvio_collections",
        "nuvio_home_catalog_settings",
        "nuvio_watch_progress",
        "nuvio_watched",
        "nuvio_library",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val accountStore by lazy { DesktopStorage.store("nuvio_account") }
    private val stagingDir: Path by lazy { DesktopStorage.rootDir.resolveSibling("Nuvio-account-staging") }
    private val pushLock = Any()

    @Volatile
    private var suppressShutdownPush = false
    private var pushLoopStarted = false
    private var executor: java.util.concurrent.ScheduledExecutorService? = null
    private var pendingRequest: java.util.concurrent.ScheduledFuture<*>? = null

    private val _session = MutableStateFlow<AccountSession?>(null)
    actual val session: StateFlow<AccountSession?> = _session.asStateFlow()

    private val _syncStatus = MutableStateFlow(AccountSyncStatus())
    actual val syncStatus: StateFlow<AccountSyncStatus> = _syncStatus.asStateFlow()

    actual val serverUrl: String
        get() = accountStore.getString("server_url")?.takeIf { it.isNotBlank() } ?: DefaultServerUrl

    private val token: String?
        get() = accountStore.getString("token")?.takeIf { it.isNotBlank() }

    private val _needsSyncSignIn = MutableStateFlow(false)
    actual val needsSyncSignIn: StateFlow<Boolean> = _needsSyncSignIn.asStateFlow()

    // ---- start-up --------------------------------------------------------

    /**
     * Must run in `main()` before any repository opens a store: applies staged data and
     * pulls newer sections from the server (bounded wait, works offline).
     */
    fun prepareOnStartup() {
        applyStagedFiles()
        loadSession()
        if (token != null) {
            runCatching { pullNewerSections(directToStores = true) }
                .onFailure { recordError(it) }
        }
        if (token != null && accountStore.getBoolean(NuvioSyncSignedInKey) != true) {
            // Signed in before the Nuvio sync came along. The password is not stored, so the
            // account panel asks for it once more; local data stays.
            runCatching { pushChangedStores(force = false) }
            clearSession()
            _needsSyncSignIn.value = true
        }
        startPushLoop()
    }

    private fun loadSession() {
        val userId = accountStore.getString("user_id")
        val username = accountStore.getString("username")
        _session.value = if (token != null && userId != null && username != null) {
            AccountSession(userId, username, accountStore.getBoolean("is_admin") == true)
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
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JsonObject(
                    (fields + ("device" to deviceName())).mapValues { JsonPrimitive(it.value) },
                )
                val response = request("POST", path, body.toString(), authToken = null)
                val sessionJson = json.parseToJsonElement(response).jsonObject
                accountStore.putString("token", sessionJson.string("token"))
                accountStore.putString("user_id", sessionJson.string("userId"))
                accountStore.putString("username", sessionJson.string("username"))
                accountStore.putBoolean("is_admin", sessionJson["isAdmin"]?.jsonPrimitive?.booleanOrNull == true)
                accountStore.remove("versions")
                accountStore.remove("hashes")
                signInNuvioSync(sessionJson.string("username"), fields.getValue("password"))
                loadSession()
                _needsSyncSignIn.value = false

                val remote = listRemoteSections().filterKeys { it.startsWith(SectionPrefix) }
                if (remote.isEmpty()) {
                    pushChangedStores(force = true)
                } else {
                    pullNewerSections(directToStores = false, forceAll = true)
                }
                // The app starts over signed in to the Nuvio sync, which then pulls the profiles.
                AccountSignInOutcome.RestartRequired
            }.onFailure(::recordError)
        }

    /**
     * Signs in the app's Nuvio sync with the same account. The anonymous local id is dropped
     * afterwards, so the next start picks up the signed-in session.
     */
    private suspend fun signInNuvioSync(username: String, password: String) {
        AuthRepository.signInWithEmail("${username.lowercase()}@$AccountEmailDomain", password)
            .getOrElse { throw AccountException("sync_sign_in_failed") }
        AuthStorage.clearAnonymousUserId()
        accountStore.putBoolean(NuvioSyncSignedInKey, true)
    }

    actual suspend fun signOut() = withContext(Dispatchers.IO) {
        if (token != null) {
            runCatching { pushChangedStores(force = false) }
            runCatching { request("POST", "/v1/logout", "{}", token) }
        }
        runCatching { AuthRepository.signOut() }
        suppressShutdownPush = true
        DesktopStorage.wipe()
        runCatching { stagingDir.toFile().deleteRecursively() }
        restartApp()
    }

    actual suspend fun createInvite(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            json.parseToJsonElement(request("POST", "/v1/invites", "{}", requireToken())).jsonObject.string("code")
        }
    }

    actual suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JsonObject(
                    mapOf("oldPassword" to JsonPrimitive(oldPassword), "newPassword" to JsonPrimitive(newPassword)),
                )
                request("POST", "/v1/password", body.toString(), requireToken())
                Unit
            }
        }

    actual suspend fun syncNow(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { pushChangedStores(force = false) }.onFailure(::recordError)
    }

    actual fun requestSync() {
        val scheduler = executor ?: return
        if (token == null) return
        synchronized(this) {
            pendingRequest?.cancel(false)
            pendingRequest = scheduler.schedule(
                { runCatching { pushChangedStores(force = false) }.onFailure(::recordError) },
                RequestDebounceSeconds,
                TimeUnit.SECONDS,
            )
        }
    }

    actual fun restartApp() {
        suppressShutdownPush = true
        val command = ProcessHandle.current().info().command().orElse(null)
        if (command != null) {
            runCatching { ProcessBuilder(command).inheritIO().start() }
        }
        exitProcess(0)
    }

    // ---- sync engine -----------------------------------------------------

    private fun startPushLoop() {
        if (pushLoopStarted) return
        pushLoopStarted = true
        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nuvio-account-sync").apply { isDaemon = true }
        }
        executor = scheduler
        scheduler.scheduleWithFixedDelay(
            {
                if (token != null) {
                    runCatching {
                        pushChangedStores(force = false)
                        pullNewerSections(directToStores = false)
                    }.onFailure(::recordError)
                }
            },
            PushIntervalSeconds,
            PushIntervalSeconds,
            TimeUnit.SECONDS,
        )
        Runtime.getRuntime().addShutdownHook(
            Thread {
                if (!suppressShutdownPush && token != null) runCatching { pushChangedStores(force = false) }
            },
        )
    }

    private fun syncableFiles(): List<Path> {
        val root = DesktopStorage.rootDir
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".properties") }
                .filter { it.name.removeSuffix(".properties") !in excludedStores }
                .toList()
        }
    }

    private fun pushChangedStores(force: Boolean) = synchronized(pushLock) {
        val authToken = token ?: return
        _syncStatus.value = _syncStatus.value.copy(syncing = true)
        try {
            val hashes = decodeMap(accountStore.getString("hashes"))
            val versions = decodeMap(accountStore.getString("versions"))
            for (file in syncableFiles()) {
                val storeName = file.name.removeSuffix(".properties")
                if (Files.exists(stagingDir.resolve(file.name))) continue
                val content = Files.readString(file)
                val hash = sha256(content)
                if (!force && hashes[storeName] == hash) continue
                val body = JsonObject(mapOf("data" to JsonPrimitive(content))).toString()
                val response = json.parseToJsonElement(request("PUT", "/v1/data/$SectionPrefix$storeName", body, authToken))
                hashes[storeName] = hash
                versions[storeName] = response.jsonObject["updatedAt"]?.jsonPrimitive?.longOrNull?.toString() ?: "0"
            }
            accountStore.putString("hashes", encodeMap(hashes))
            accountStore.putString("versions", encodeMap(versions))
            _syncStatus.value = AccountSyncStatus(
                lastSyncEpochMs = System.currentTimeMillis(),
                pendingRestart = hasStagedFiles(),
            )
        } finally {
            _syncStatus.value = _syncStatus.value.copy(syncing = false)
        }
    }

    /**
     * Downloads sections that are newer on the server. With [directToStores] the files are
     * written straight into the Nuvio folder (start-up, before stores load); otherwise they
     * are staged and applied by [applyStagedFiles] on the next start.
     */
    private fun pullNewerSections(directToStores: Boolean, forceAll: Boolean = false) {
        val authToken = token ?: return
        val remote = listRemoteSections()
        val versions = decodeMap(accountStore.getString("versions"))
        val hashes = decodeMap(accountStore.getString("hashes"))
        for ((section, updatedAt) in remote) {
            if (!section.startsWith(SectionPrefix)) continue
            val storeName = section.removePrefix(SectionPrefix)
            if (storeName in excludedStores || storeName in nuvioSyncedStores) continue
            if (!storeName.matches(Regex("[a-z0-9_]+"))) continue
            val known = versions[storeName]?.toLongOrNull() ?: 0L
            if (!forceAll && updatedAt <= known) continue
            val sectionJson = json.parseToJsonElement(request("GET", "/v1/data/$section", null, authToken)).jsonObject
            val content = sectionJson["data"]?.jsonPrimitive?.contentOrNull ?: continue
            val targetDir = if (directToStores) DesktopStorage.rootDir else stagingDir.also { Files.createDirectories(it) }
            writeAtomically(targetDir.resolve("$storeName.properties"), content)
            versions[storeName] = updatedAt.toString()
            hashes[storeName] = sha256(content)
        }
        accountStore.putString("versions", encodeMap(versions))
        accountStore.putString("hashes", encodeMap(hashes))
        _syncStatus.value = AccountSyncStatus(
            lastSyncEpochMs = System.currentTimeMillis(),
            pendingRestart = !directToStores && hasStagedFiles(),
        )
    }

    private fun hasStagedFiles(): Boolean =
        Files.isDirectory(stagingDir) && Files.list(stagingDir).use { stream -> stream.findAny().isPresent }

    private fun applyStagedFiles() {
        if (!Files.isDirectory(stagingDir)) return
        Files.list(stagingDir).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".properties") }.toList()
        }.forEach { staged ->
            Files.createDirectories(DesktopStorage.rootDir)
            Files.move(staged, DesktopStorage.rootDir.resolve(staged.name), StandardCopyOption.REPLACE_EXISTING)
        }
        runCatching { stagingDir.toFile().deleteRecursively() }
    }

    private fun listRemoteSections(): Map<String, Long> {
        val response = json.parseToJsonElement(request("GET", "/v1/data", null, requireToken())).jsonObject
        val sections = response["sections"]?.jsonObject ?: return emptyMap()
        return sections.mapValues { (_, value) -> value.jsonPrimitive.longOrNull ?: 0L }
    }

    // ---- HTTP + helpers ----------------------------------------------------

    private fun request(method: String, path: String, body: String?, authToken: String?): String {
        val builder = HttpRequest.newBuilder(URI(serverUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Nuvio-Desktop")
        authToken?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw AccountException("network")
        }
        if (response.statusCode() in 200..299) return response.body()
        val code = runCatching {
            json.parseToJsonElement(response.body()).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: "http_${response.statusCode()}"
        if (response.statusCode() == 401 && authToken != null) clearSession()
        throw AccountException(code)
    }

    private fun requireToken(): String = token ?: throw AccountException("unauthorized")

    private fun clearSession() {
        listOf("token", "user_id", "username", "is_admin", NuvioSyncSignedInKey).forEach(accountStore::remove)
        _session.value = null
    }

    private fun recordError(error: Throwable) {
        val code = (error as? AccountException)?.code ?: "network"
        _syncStatus.value = _syncStatus.value.copy(syncing = false, lastError = code)
    }

    private fun writeAtomically(target: Path, content: String) {
        val tmp = target.resolveSibling(".${target.name}.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun deviceName(): String =
        "PC " + (System.getenv("COMPUTERNAME") ?: System.getProperty("os.name").orEmpty())

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun decodeMap(raw: String?): MutableMap<String, String> =
        raw?.let {
            runCatching {
                json.parseToJsonElement(it).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }.toMutableMap()
            }.getOrNull()
        } ?: mutableMapOf()

    private fun encodeMap(map: Map<String, String>): String =
        JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonElement)?.jsonPrimitive?.contentOrNull ?: throw AccountException("invalid_response")
}
