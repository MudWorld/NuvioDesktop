package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

internal object LiveTVRepository {
    private const val RecentLimit = 15
    private const val EpgMaxAgeMs = 12L * 60 * 60 * 1000
    private const val EpgWindowBackMs = 3L * 60 * 60 * 1000
    private const val EpgWindowAheadMs = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val store = DesktopStorage.store("nuvio_livetv")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    private val cacheDir: Path by lazy { DesktopStorage.cacheDir.resolve("livetv").also { Files.createDirectories(it) } }

    private val _playlists = MutableStateFlow<List<LiveTVPlaylist>>(emptyList())
    val playlists: StateFlow<List<LiveTVPlaylist>> = _playlists.asStateFlow()

    private val _channels = MutableStateFlow<List<LiveTVChannel>>(emptyList())
    val channels: StateFlow<List<LiveTVChannel>> = _channels.asStateFlow()

    private val _favoriteIds = MutableStateFlow<List<String>>(emptyList())
    val favoriteIds: StateFlow<List<String>> = _favoriteIds.asStateFlow()

    private val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds.asStateFlow()

    private val _recentIds = MutableStateFlow<List<String>>(emptyList())
    val recentIds: StateFlow<List<String>> = _recentIds.asStateFlow()

    private val _refreshingUrls = MutableStateFlow<Set<String>>(emptySet())
    val refreshingUrls: StateFlow<Set<String>> = _refreshingUrls.asStateFlow()

    /** Programmes keyed by lowercased `tvg-id`. */
    private val _guide = MutableStateFlow<Map<String, List<LiveTVProgramme>>>(emptyMap())
    val guide: StateFlow<Map<String, List<LiveTVProgramme>>> = _guide.asStateFlow()

    private val _guideLoading = MutableStateFlow(false)
    val guideLoading: StateFlow<Boolean> = _guideLoading.asStateFlow()

    /** The channel list the user last played from; keyboard zapping walks through it. */
    @Volatile
    var zapList: List<LiveTVChannel> = emptyList()

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _playlists.value = decode(store.getString("playlists"), ListSerializer(LiveTVPlaylist.serializer())).orEmpty()
        _favoriteIds.value = decode(store.getString("favorites"), ListSerializer(String.serializer())).orEmpty()
        _hiddenIds.value = decode(store.getString("hidden"), SetSerializer(String.serializer())).orEmpty()
        _recentIds.value = decode(store.getString("recent"), ListSerializer(String.serializer())).orEmpty()
        _channels.value = _playlists.value.flatMap { readChannelCache(it.url) }
        LiveTVKeyboardZapper.install()
        scope.launch { loadGuides(forceDownload = false) }
    }

    // region Playlists

    suspend fun addPlaylist(rawUrl: String, name: String?): Result<Int> = withContext(Dispatchers.IO) {
        ensureLoaded()
        runCatching {
            val url = rawUrl.trim()
            require(url.startsWith("http://", true) || url.startsWith("https://", true)) { "Invalid URL" }
            val parsed = downloadPlaylist(url)
            require(parsed.channels.isNotEmpty()) { "No channels found" }
            val playlist = LiveTVPlaylist(
                url = url,
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: defaultPlaylistName(url),
                epgUrl = parsed.epgUrl,
                epgUrlFromPlaylist = true,
                channelCount = parsed.channels.size,
                lastRefreshEpochMs = System.currentTimeMillis(),
            )
            writeChannelCache(url, parsed.channels)
            _playlists.update { current -> current.filterNot { it.url == url } + playlist }
            replaceChannels(url, parsed.channels)
            persistPlaylists()
            scope.launch { loadGuides(forceDownload = false) }
            parsed.channels.size
        }
    }

    fun removePlaylist(url: String) {
        ensureLoaded()
        _playlists.update { current -> current.filterNot { it.url == url } }
        _channels.update { current -> current.filterNot { it.playlistUrl == url } }
        runCatching { Files.deleteIfExists(channelCacheFile(url)) }
        persistPlaylists()
    }

    fun refresh(url: String) {
        ensureLoaded()
        if (url in _refreshingUrls.value) return
        _refreshingUrls.update { it + url }
        scope.launch {
            try {
                val parsed = downloadPlaylist(url)
                if (parsed.channels.isNotEmpty()) {
                    writeChannelCache(url, parsed.channels)
                    replaceChannels(url, parsed.channels)
                    _playlists.update { current ->
                        current.map { playlist ->
                            if (playlist.url != url) {
                                playlist
                            } else {
                                playlist.copy(
                                    epgUrl = if (playlist.epgUrlFromPlaylist) parsed.epgUrl else playlist.epgUrl,
                                    channelCount = parsed.channels.size,
                                    lastRefreshEpochMs = System.currentTimeMillis(),
                                )
                            }
                        }
                    }
                    persistPlaylists()
                }
            } catch (_: Exception) {
                // Keep the cached channels when the playlist host is unreachable.
            } finally {
                _refreshingUrls.update { it - url }
            }
        }
    }

    fun refreshAll() {
        ensureLoaded()
        _playlists.value.forEach { refresh(it.url) }
        scope.launch { loadGuides(forceDownload = true) }
    }

    fun setEpgUrl(playlistUrl: String, epgUrl: String?) {
        ensureLoaded()
        val cleaned = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        _playlists.update { current ->
            current.map {
                if (it.url == playlistUrl) it.copy(epgUrl = cleaned, epgUrlFromPlaylist = cleaned == null) else it
            }
        }
        persistPlaylists()
        scope.launch { loadGuides(forceDownload = true) }
    }

    private fun replaceChannels(playlistUrl: String, channels: List<LiveTVChannel>) {
        _channels.update { current ->
            val order = _playlists.value.map { it.url }
            (current.filterNot { it.playlistUrl == playlistUrl } + channels)
                .sortedBy { order.indexOf(it.playlistUrl).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        }
    }

    private fun downloadPlaylist(url: String): M3UParser.Result {
        val response = http.send(request(url), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        return M3UParser.parse(response.body(), url)
    }

    private fun defaultPlaylistName(url: String): String =
        runCatching { URI(url).path.substringAfterLast('/').substringBeforeLast('.') }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { URI(url).host }.getOrNull()
            ?: url

    // endregion

    // region Favorites, hidden, recents

    fun toggleFavorite(id: String) {
        ensureLoaded()
        if (id in _favoriteIds.value) {
            _favoriteIds.update { it - id }
        } else {
            _favoriteIds.update { it + id }
            _hiddenIds.update { it - id }
            persist("hidden", _hiddenIds.value, SetSerializer(String.serializer()))
        }
        persist("favorites", _favoriteIds.value, ListSerializer(String.serializer()))
    }

    fun toggleHidden(id: String) {
        ensureLoaded()
        if (id in _hiddenIds.value) {
            _hiddenIds.update { it - id }
        } else {
            _hiddenIds.update { it + id }
            _favoriteIds.update { it - id }
            persist("favorites", _favoriteIds.value, ListSerializer(String.serializer()))
        }
        persist("hidden", _hiddenIds.value, SetSerializer(String.serializer()))
    }

    fun markWatched(id: String) {
        ensureLoaded()
        _recentIds.update { current -> (listOf(id) + current.filterNot { it == id }).take(RecentLimit) }
        persist("recent", _recentIds.value, ListSerializer(String.serializer()))
    }

    fun clearRecents() {
        _recentIds.value = emptyList()
        persist("recent", _recentIds.value, ListSerializer(String.serializer()))
    }

    // endregion

    // region Guide (EPG)

    fun nowAndNext(channel: LiveTVChannel, nowEpochMs: Long = System.currentTimeMillis()): Pair<LiveTVProgramme?, LiveTVProgramme?> {
        val programmes = guideFor(channel) ?: return null to null
        val currentIndex = programmes.indexOfFirst { nowEpochMs >= it.startEpochMs && nowEpochMs < it.endEpochMs }
        if (currentIndex < 0) {
            return null to programmes.firstOrNull { it.startEpochMs > nowEpochMs }
        }
        return programmes[currentIndex] to programmes.getOrNull(currentIndex + 1)
    }

    private fun guideFor(channel: LiveTVChannel): List<LiveTVProgramme>? {
        val guide = _guide.value
        if (guide.isEmpty()) return null
        return channel.tvgId?.lowercase()?.let(guide::get)
            ?: channel.tvgName?.lowercase()?.let(guide::get)
            ?: guide[channel.name.lowercase()]
    }

    private suspend fun loadGuides(forceDownload: Boolean) = withContext(Dispatchers.IO) {
        val epgUrls = _playlists.value.mapNotNull { it.epgUrl }.distinct()
        if (epgUrls.isEmpty()) {
            _guide.value = emptyMap()
            return@withContext
        }
        val wanted = _channels.value
            .flatMap { listOfNotNull(it.tvgId, it.tvgName, it.name) }
            .toSet()
        if (wanted.isEmpty()) return@withContext
        _guideLoading.value = true
        try {
            val now = System.currentTimeMillis()
            val merged = mutableMapOf<String, List<LiveTVProgramme>>()
            epgUrls.forEach { epgUrl ->
                runCatching {
                    val file = epgCacheFile(epgUrl)
                    val stale = !Files.exists(file) ||
                        now - Files.getLastModifiedTime(file).toMillis() > EpgMaxAgeMs
                    if (forceDownload || stale) downloadToFile(epgUrl, file)
                    Files.newInputStream(file).use { input ->
                        XmltvParser.parse(
                            input = input,
                            wantedChannelIds = wanted,
                            windowStartMs = now - EpgWindowBackMs,
                            windowEndMs = now + EpgWindowAheadMs,
                        )
                    }
                }.onSuccess { parsed ->
                    parsed.forEach { (key, programmes) -> merged.putIfAbsent(key, programmes) }
                }
            }
            _guide.value = merged
        } finally {
            _guideLoading.value = false
        }
    }

    private fun downloadToFile(url: String, target: Path) {
        val pending = target.resolveSibling(target.fileName.toString() + ".part")
        val response = http.send(request(url), HttpResponse.BodyHandlers.ofFile(pending))
        if (response.statusCode() !in 200..299) {
            Files.deleteIfExists(pending)
            error("HTTP ${response.statusCode()}")
        }
        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
    }

    // endregion

    // region Persistence

    private fun request(url: String): HttpRequest = HttpRequest.newBuilder(URI(url))
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Nuvio-Desktop")
        .GET()
        .build()

    private fun channelCacheFile(playlistUrl: String): Path = cacheDir.resolve("${liveTVShortHash(playlistUrl)}.json")

    private fun epgCacheFile(epgUrl: String): Path = cacheDir.resolve("epg-${liveTVShortHash(epgUrl)}.xml")

    private fun readChannelCache(playlistUrl: String): List<LiveTVChannel> = runCatching {
        val file = channelCacheFile(playlistUrl)
        if (!Files.exists(file)) return@runCatching emptyList()
        json.decodeFromString(ListSerializer(LiveTVChannel.serializer()), Files.readString(file))
    }.getOrDefault(emptyList())

    private fun writeChannelCache(playlistUrl: String, channels: List<LiveTVChannel>) {
        runCatching {
            Files.writeString(
                channelCacheFile(playlistUrl),
                json.encodeToString(ListSerializer(LiveTVChannel.serializer()), channels),
            )
        }
    }

    private fun persistPlaylists() {
        persist("playlists", _playlists.value, ListSerializer(LiveTVPlaylist.serializer()))
    }

    private fun <T> persist(key: String, value: T, serializer: kotlinx.serialization.KSerializer<T>) {
        store.putString(key, json.encodeToString(serializer, value))
    }

    private fun <T> decode(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    // endregion
}
