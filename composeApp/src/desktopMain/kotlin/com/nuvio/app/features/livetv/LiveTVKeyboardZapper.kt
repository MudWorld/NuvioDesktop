package com.nuvio.app.features.livetv

import com.nuvio.app.core.ui.NuvioToastController
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * Channel switching while a Live TV stream plays: ↑ / Bild↑ = previous channel,
 * ↓ / Bild↓ = next channel, F = toggle favorite. The player draws into a native
 * surface, so keys are caught at the AWT level instead of through Compose focus.
 */
internal object LiveTVKeyboardZapper {
    @Volatile
    private var currentChannelId: String? = null
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        LiveTVPlaybackLauncher.onPlaybackStarted = { playback ->
            currentChannelId = playback.channelId
            LiveTVRepository.markWatched(playback.channelId)
        }
        LiveTVPlaybackLauncher.onPlaybackEnded = { channelId ->
            if (currentChannelId == channelId) currentChannelId = null
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(
            KeyEventDispatcher { event -> handle(event) },
        )
    }

    private fun handle(event: KeyEvent): Boolean {
        val channelId = currentChannelId ?: return false
        if (event.id != KeyEvent.KEY_PRESSED) return false
        if (event.isControlDown || event.isAltDown || event.isMetaDown) return false
        return when (event.keyCode) {
            KeyEvent.VK_UP, KeyEvent.VK_PAGE_UP -> zap(channelId, step = -1)
            KeyEvent.VK_DOWN, KeyEvent.VK_PAGE_DOWN -> zap(channelId, step = 1)
            KeyEvent.VK_F -> {
                LiveTVRepository.toggleFavorite(channelId)
                val name = LiveTVRepository.channels.value.firstOrNull { it.id == channelId }?.name.orEmpty()
                val isFavorite = channelId in LiveTVRepository.favoriteIds.value
                NuvioToastController.show(if (isFavorite) "★ $name" else "☆ $name")
                true
            }
            else -> false
        }
    }

    private fun zap(channelId: String, step: Int): Boolean {
        val list = LiveTVRepository.zapList.ifEmpty { LiveTVRepository.channels.value }
        if (list.size < 2) return false
        val index = list.indexOfFirst { it.id == channelId }
        val target = if (index < 0) list.first() else list[(index + step + list.size) % list.size]
        val (now, _) = LiveTVRepository.nowAndNext(target)
        val handler = LiveTVPlaybackLauncher.handler ?: return false
        handler(target.toPlayback(nowPlaying = now?.title), true)
        return true
    }
}
