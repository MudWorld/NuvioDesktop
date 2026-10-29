@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.livetv

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.memcpy
import platform.posix.rewind

/** iPad Live TV store: settings in UserDefaults (synced with the account), caches on disk. */
internal object LiveTVRepository {
    private const val RecentLimit = 15
    private const val EpgMaxAgeMs = 12L * 60 * 60 * 1000
    private const val EpgWindowBackMs = 3L * 60 * 60 * 1000
    private const val EpgWindowAheadMs = 24L * 60 * 60 * 1000
    private const val KeyPrefix = "livetv_"

    private val json = Json { ignoreUnknownKeys = true }
    private val defaults get() = NSUserDefaults.standardUserDefaults
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient(Darwin) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 60_000
        }
    }
    private val cacheDir = "${NSHomeDirectory()}/Library/Caches/WatchPartyLiveTV"

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

    /** The channel list the user last played from; swipe zapping walks through it. */
    var zapList: List<LiveTVChannel> = emptyList()

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _playlists.value = decode("playlists", ListSerializer(LiveTVPlaylist.serializer())).orEmpty()
        _favoriteIds.value = decode("favorites", ListSerializer(String.serializer())).orEmpty()
        _hiddenIds.value = decode("hidden", SetSerializer(String.serializer())).orEmpty()
        _recentIds.value = decode("recent", ListSerializer(String.serializer())).orEmpty()
        LiveTVPlaybackLauncher.onPlaybackStarted = { playback -> markWatched(playback.channelId) }
        val cached = _playlists.value.flatMap { readChannelCache(it.url) }
        _channels.value = cached
        // A fresh install restored from the account has playlists but no channel cache yet.
        _playlists.value.filter { playlist -> cached.none { it.playlistUrl == playlist.url } }
            .forEach { refresh(it.url) }
        scope.launch { loadGuides(forceDownload = false) }
    }

    // region Playlists

    suspend fun addPlaylist(rawUrl: String, name: String?): Result<Int> = withContext(Dispatchers.Default) {
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
                lastRefreshEpochMs = nowMs(),
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
        NSFileManager.defaultManager.removeItemAtPath(channelCachePath(url), null)
        persistPlaylists()
    }

    fun refresh(url: String) {
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
                                    lastRefreshEpochMs = nowMs(),
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

    private suspend fun downloadPlaylist(url: String): M3UParser.Result {
        val response = get(url)
        require(response.status.value in 200..299) { "HTTP ${response.status.value}" }
        return M3UParser.parse(response.bodyAsText(), url)
    }

    private fun defaultPlaylistName(url: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val fileName = withoutQuery.substringAfterLast('/').substringBeforeLast('.')
        if (fileName.isNotBlank()) return fileName
        return withoutQuery.substringAfter("://").substringBefore('/').ifBlank { url }
    }

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

    // endregion

    // region Guide (EPG)

    fun nowAndNext(channel: LiveTVChannel, nowEpochMs: Long = nowMs()): Pair<LiveTVProgramme?, LiveTVProgramme?> {
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

    private suspend fun loadGuides(forceDownload: Boolean) = withContext(Dispatchers.Default) {
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
            val now = nowMs()
            val merged = mutableMapOf<String, List<LiveTVProgramme>>()
            epgUrls.forEach { epgUrl ->
                runCatching {
                    val path = "$cacheDir/epg-${liveTVShortHash(epgUrl)}.xml"
                    val stampPath = "$path.time"
                    val fetchedAt = readFile(stampPath)?.decodeToString()?.toLongOrNull() ?: 0L
                    val cached = if (forceDownload || now - fetchedAt > EpgMaxAgeMs) null else readFile(path)
                    val bytes = cached ?: run {
                        val response = get(epgUrl)
                        require(response.status.value in 200..299) { "HTTP ${response.status.value}" }
                        val downloaded = gunzipIfNeeded(response.bodyAsBytes()) ?: error("Unreadable guide")
                        writeFile(path, downloaded)
                        writeFile(stampPath, now.toString().encodeToByteArray())
                        downloaded
                    }
                    XmltvParser.parse(
                        text = bytes.decodeToString(),
                        wantedChannelIds = wanted,
                        windowStartMs = now - EpgWindowBackMs,
                        windowEndMs = now + EpgWindowAheadMs,
                    )
                }.onSuccess { parsed ->
                    parsed.forEach { (key, programmes) -> if (key !in merged) merged[key] = programmes }
                }
            }
            _guide.value = merged
        } finally {
            _guideLoading.value = false
        }
    }

    /** Guides are often served as `.xml.gz`; NSData inflates the raw deflate stream inside. */
    private fun gunzipIfNeeded(bytes: ByteArray): ByteArray? {
        if (bytes.size < 18 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
        val flags = bytes[3].toInt()
        var position = 10
        if (flags and 0x04 != 0) {
            position += 2 + ((bytes[position].toInt() and 0xff) or ((bytes[position + 1].toInt() and 0xff) shl 8))
        }
        if (flags and 0x08 != 0) {
            while (position < bytes.size && bytes[position] != 0.toByte()) position++
            position++
        }
        if (flags and 0x10 != 0) {
            while (position < bytes.size && bytes[position] != 0.toByte()) position++
            position++
        }
        if (flags and 0x02 != 0) position += 2
        if (position >= bytes.size - 8) return null
        val deflated = bytes.copyOfRange(position, bytes.size - 8).toNSData()
        return deflated.decompressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, null)?.toByteArray()
    }

    // endregion

    // region Persistence

    private suspend fun get(url: String): HttpResponse = http.get(url) {
        header("User-Agent", "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) WatchParty")
    }

    private fun channelCachePath(playlistUrl: String): String = "$cacheDir/${liveTVShortHash(playlistUrl)}.json"

    private fun readChannelCache(playlistUrl: String): List<LiveTVChannel> = runCatching {
        val bytes = readFile(channelCachePath(playlistUrl)) ?: return@runCatching emptyList()
        json.decodeFromString(ListSerializer(LiveTVChannel.serializer()), bytes.decodeToString())
    }.getOrDefault(emptyList())

    private fun writeChannelCache(playlistUrl: String, channels: List<LiveTVChannel>) {
        writeFile(
            channelCachePath(playlistUrl),
            json.encodeToString(ListSerializer(LiveTVChannel.serializer()), channels).encodeToByteArray(),
        )
    }

    private fun persistPlaylists() {
        persist("playlists", _playlists.value, ListSerializer(LiveTVPlaylist.serializer()))
    }

    private fun <T> persist(key: String, value: T, serializer: KSerializer<T>) {
        defaults.setObject(json.encodeToString(serializer, value), forKey = KeyPrefix + key)
    }

    private fun <T> decode(key: String, serializer: KSerializer<T>): T? =
        defaults.stringForKey(KeyPrefix + key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    private fun writeFile(path: String, bytes: ByteArray): Boolean {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = cacheDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val file = fopen(path, "wb") ?: return false
        return try {
            if (bytes.isEmpty()) return true
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toLong() == bytes.size.toLong()
            }
        } finally {
            fclose(file)
        }
    }

    private fun readFile(path: String): ByteArray? {
        val file = fopen(path, "rb") ?: return null
        return try {
            if (fseek(file, 0, SEEK_END) != 0) return null
            val size = ftell(file)
            if (size <= 0L || size > Int.MAX_VALUE) return null
            rewind(file)
            ByteArray(size.toInt()).also { result ->
                val read = result.usePinned { pinned ->
                    fread(pinned.addressOf(0), 1.convert(), result.size.convert(), file)
                }
                if (read.toLong() != result.size.toLong()) return null
            }
        } finally {
            fclose(file)
        }
    }

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.convert())
    }

    private fun NSData.toByteArray(): ByteArray {
        val result = ByteArray(length.toInt())
        if (result.isNotEmpty()) {
            result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
        }
        return result
    }

    // endregion
}
