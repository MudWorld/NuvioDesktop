package com.nuvio.app.features.livetv

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
internal data class LiveTVPlaylist(
    val url: String,
    val name: String,
    /** XMLTV guide; taken from `url-tvg` in the playlist header unless the user set one. */
    val epgUrl: String? = null,
    val epgUrlFromPlaylist: Boolean = true,
    val channelCount: Int = 0,
    val lastRefreshEpochMs: Long = 0L,
)

@Serializable
internal data class LiveTVChannel(
    val id: String,
    val name: String,
    val group: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    val playlistUrl: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

internal data class LiveTVProgramme(
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val description: String? = null,
) {
    fun progress(nowEpochMs: Long): Float {
        val duration = (endEpochMs - startEpochMs).coerceAtLeast(1L)
        return ((nowEpochMs - startEpochMs).toFloat() / duration).coerceIn(0f, 1f)
    }
}

internal fun LiveTVChannel.toPlayback(nowPlaying: String? = null) = LiveTVPlayback(
    channelId = id,
    title = name,
    url = streamUrl,
    headers = headers,
    logo = logoUrl,
    group = group,
    nowPlaying = nowPlaying,
)

internal fun liveTVShortHash(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

internal fun liveTVChannelId(playlistUrl: String, streamUrl: String): String =
    "$LiveTVContentType:" + liveTVShortHash("$playlistUrl|$streamUrl")
