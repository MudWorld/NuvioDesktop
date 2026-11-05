package com.nuvio.app.features.watchparty

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.features.streams.StreamItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Starts the WatchParty extras once per app run: error capture, pending crash upload, provider status. */
internal object WatchPartyRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start() {
        if (started) return
        started = true
        DiagnosticsReporter.install()
        scope.launch {
            delay(10.seconds)
            DiagnosticsReporter.sendPendingCrash()
            while (true) {
                ProviderStatusRepository.refresh()
                delay(6.hours)
            }
        }
    }
}

// ---- sign in a TV with the code it shows ------------------------------------------------

internal object TvLoginApproval {
    fun normalize(code: String): String =
        code.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(6)

    /** The name of the TV that shows [code], or a failure ("invalid_code", "signed_out", ...). */
    suspend fun deviceFor(code: String): Result<String> = runCatching {
        val body = WatchPartyServer.request("GET", "/v1/tv-login/info?code=" + normalize(code))
        WatchPartyServer.json.parseToJsonElement(body).jsonObject["device"]?.jsonPrimitive?.contentOrNull ?: "TV"
    }

    suspend fun approve(code: String): Result<String> = runCatching {
        val body = WatchPartyServer.request("POST", "/v1/tv-login/approve", buildJsonObject { put("code", normalize(code)) })
        WatchPartyServer.json.parseToJsonElement(body).jsonObject["device"]?.jsonPrimitive?.contentOrNull ?: "TV"
    }
}

// ---- provider status from the nightly server check -------------------------------------

/**
 * German Providers that failed two nightly checks in a row. The stream search skips them and the
 * plugin list marks them, so nobody waits for a provider that is known to be broken.
 */
internal object ProviderStatusRepository {
    private const val RepositoryMarker = "nuvio-german-providers"
    private const val CacheKey = "provider_down"
    private val _down = MutableStateFlow(
        WatchPartyPlatform.getString(CacheKey).orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
    )
    val down: StateFlow<Set<String>> = _down.asStateFlow()

    suspend fun refresh() {
        runCatching {
            val body = WatchPartyServer.request("GET", "/v1/plugin-status", authorized = false)
            val providers = WatchPartyServer.json.parseToJsonElement(body).jsonObject["providers"]?.jsonObject
                ?: return@runCatching
            val down = providers.filter { (_, value) ->
                runCatching { value.jsonObject["status"]?.jsonPrimitive?.contentOrNull }.getOrNull() == "down"
            }.keys
            _down.value = down
            WatchPartyPlatform.putString(CacheKey, down.joinToString(","))
        }.onFailure { Logger.withTag("ProviderStatus").i { "status not loaded: ${it.message}" } }
    }

    fun isDown(scraperId: String, repositoryUrl: String): Boolean =
        repositoryUrl.contains(RepositoryMarker, ignoreCase = true) && scraperId in _down.value
}

// ---- "link does not work" --------------------------------------------------------------

internal object StreamReports {
    suspend fun send(
        stream: StreamItem,
        title: String,
        contentId: String?,
        contentType: String?,
        season: Int?,
        episode: Int?,
    ): Result<Unit> = runCatching {
        val body = buildJsonObject {
            put("platform", WatchPartyPlatform.name)
            put("appVersion", AppVersionPolicy.displayVersionName)
            put("provider", stream.addonName)
            put("addon", stream.addonId)
            put("title", title)
            contentId?.let { put("contentId", it) }
            contentType?.let { put("contentType", it) }
            season?.let { put("season", it) }
            episode?.let { put("episode", it) }
            put("streamName", listOfNotNull(stream.name, stream.title).joinToString(" · ").take(300))
            put("error", DiagnosticsReporter.recentProblems(6))
        }
        WatchPartyServer.request("POST", "/v1/reports", body)
        Unit
    }
}

// ---- error reports (opt-in) -------------------------------------------------------------

/**
 * Keeps the last warnings and errors of this run in memory. When the user switched error reports on,
 * a crash is stored and sent on the next start, and "send report now" uploads the recent log.
 * The server strips links, keys and tokens once more before it stores anything.
 */
internal object DiagnosticsReporter {
    private const val EnabledKey = "diagnostics_enabled"
    private const val PendingKey = "diagnostics_pending_crash"
    private const val MaxLines = 400
    private val lock = SynchronizedObject()
    private val lines = ArrayDeque<String>()
    private var installed = false
    private val _enabled = MutableStateFlow(WatchPartyPlatform.getString(EnabledKey) == "true")
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        WatchPartyPlatform.putString(EnabledKey, value.toString())
        if (!value) WatchPartyPlatform.putString(PendingKey, null)
    }

    fun install() {
        if (installed) return
        installed = true
        Logger.addLogWriter(object : LogWriter() {
            override fun isLoggable(tag: String, severity: Severity): Boolean = severity >= Severity.Warn

            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                val line = buildString {
                    append(timestamp()).append(' ').append(severity.name.first()).append(' ').append(tag).append(": ")
                    append(message.take(600))
                    throwable?.let { append(" | ").append(it::class.simpleName).append(": ").append(it.message?.take(300)) }
                }
                add(line)
            }
        })
        WatchPartyPlatform.installCrashCapture { error ->
            if (!_enabled.value) return@installCrashCapture
            val text = buildString {
                appendLine("CRASH ${timestamp()} ${error::class.simpleName}: ${error.message}")
                appendLine(error.stackTraceToString().take(24_000))
                appendLine("--- last log lines ---")
                snapshot().takeLast(150).forEach { appendLine(it) }
            }
            WatchPartyPlatform.putString(PendingKey, text.take(60_000))
        }
    }

    private fun add(line: String) {
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MaxLines) lines.removeFirst()
        }
    }

    private fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun recentProblems(count: Int): String = snapshot().takeLast(count).joinToString("\n").take(1800)

    suspend fun sendPendingCrash() {
        if (!_enabled.value) return
        val text = WatchPartyPlatform.getString(PendingKey) ?: return
        upload("crash", text).onSuccess { WatchPartyPlatform.putString(PendingKey, null) }
    }

    suspend fun sendNow(): Result<Unit> = upload("log", buildString {
        appendLine("Report from ${WatchPartyPlatform.deviceName} at ${timestamp()}")
        snapshot().forEach { appendLine(it) }
    })

    private suspend fun upload(kind: String, text: String): Result<Unit> = runCatching {
        WatchPartyServer.request("POST", "/v1/diagnostics", buildJsonObject {
            put("platform", WatchPartyPlatform.name)
            put("appVersion", AppVersionPolicy.displayVersionName)
            put("device", WatchPartyPlatform.deviceName)
            put("kind", kind)
            put("text", text)
        })
        Unit
    }

    private fun timestamp(): String = Clock.System.now().toString().take(19)
}
