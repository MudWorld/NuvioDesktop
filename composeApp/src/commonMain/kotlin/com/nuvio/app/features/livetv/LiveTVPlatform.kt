package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Content type used for IPTV playback; watch progress and resume prompts skip it. */
const val LiveTVContentType = "iptv"

data class LiveTVPlayback(
    val channelId: String,
    val title: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val logo: String? = null,
    val group: String? = null,
    val nowPlaying: String? = null,
)

/** Live TV (M3U playlists) is implemented for desktop only. */
expect val liveTVSupported: Boolean

@Composable
expect fun LiveTVScreen(
    modifier: Modifier,
    topChromePadding: Dp?,
    onPlay: (LiveTVPlayback) -> Unit,
)

/**
 * Starts IPTV playback from outside the composition (keyboard channel switching).
 * The app shell installs the handler; `replaceCurrent` swaps the running player.
 */
object LiveTVPlaybackLauncher {
    var handler: ((playback: LiveTVPlayback, replaceCurrent: Boolean) -> Unit)? = null

    /** Notified when a Live TV channel starts playing. */
    var onPlaybackStarted: ((LiveTVPlayback) -> Unit)? = null

    /** Notified when the player showing [channelId] closes. */
    var onPlaybackEnded: ((channelId: String) -> Unit)? = null
}
