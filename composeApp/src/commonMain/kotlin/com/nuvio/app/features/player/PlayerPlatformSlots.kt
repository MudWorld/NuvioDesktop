package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** Optional platform controls for the player header; the iPad installs its AirPlay button here. */
object PlayerPlatformSlots {
    var airPlayButton: (@Composable (buttonSize: Dp) -> Unit)? = null
    var remoteButton: (@Composable (buttonSize: Dp) -> Unit)? = null
}
