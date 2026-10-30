@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.Icon
import com.nuvio.app.features.remote.RemoteControlBridge
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.remote_title
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.Dp
import platform.AVKit.AVRoutePickerView
import platform.UIKit.UIColor

/**
 * The system AirPlay picker. Choosing an Apple TV hands the stream to AVPlayer
 * (see `AirPlayHandoff.swift`), so the TV fetches it directly and the iPad becomes the remote.
 */
@Composable
private fun AirPlayRouteButton(buttonSize: Dp) {
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        UIKitView(
            modifier = Modifier.size(buttonSize * 0.78f),
            factory = {
                AVRoutePickerView().apply {
                    backgroundColor = UIColor.clearColor
                    tintColor = UIColor.whiteColor
                    activeTintColor = UIColor(red = 0.49, green = 0.36, blue = 0.96, alpha = 1.0)
                    prioritizesVideoDevices = true
                }
            },
        )
    }
}

/** Opens the Apple TV remote sheet (Swift); only shown when the Swift host is installed. */
@Composable
private fun RemoteSheetButton(buttonSize: Dp) {
    if (RemoteControlBridge.host == null) return
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable { RemoteControlBridge.host?.openRemote() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Cast,
            contentDescription = stringResource(Res.string.remote_title),
            tint = Color.White,
            modifier = Modifier.size(buttonSize * 0.5f),
        )
    }
}

/** Called once from the iOS entry points before the first player opens. */
fun installPlayerPlatformSlots() {
    PlayerPlatformSlots.airPlayButton = { size -> AirPlayRouteButton(size) }
    PlayerPlatformSlots.remoteButton = { size -> RemoteSheetButton(size) }
}
