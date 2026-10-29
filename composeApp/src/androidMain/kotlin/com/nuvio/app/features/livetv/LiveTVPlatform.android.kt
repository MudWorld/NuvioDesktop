package com.nuvio.app.features.livetv

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

actual val liveTVSupported: Boolean = false

@Composable
actual fun LiveTVScreen(
    modifier: Modifier,
    topChromePadding: Dp?,
    onPlay: (LiveTVPlayback) -> Unit,
) {
    Box(modifier = modifier)
}
