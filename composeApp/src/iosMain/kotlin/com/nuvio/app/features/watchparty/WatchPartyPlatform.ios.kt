package com.nuvio.app.features.watchparty

import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPad
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

internal actual object WatchPartyPlatform {
    private const val Prefix = "watchparty_extras."
    private val defaults get() = NSUserDefaults.standardUserDefaults

    actual val name: String =
        if (UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPad) "ipad" else "iphone"

    actual val deviceName: String = UIDevice.currentDevice.name

    actual fun getString(key: String): String? = defaults.stringForKey(Prefix + key)

    actual fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(Prefix + key) else defaults.setObject(value, forKey = Prefix + key)
    }

    @OptIn(ExperimentalNativeApi::class)
    actual fun installCrashCapture(onCrash: (Throwable) -> Unit) {
        var previous: ((Throwable) -> Unit)? = null
        previous = setUnhandledExceptionHook { error ->
            runCatching {
                onCrash(error)
                NSUserDefaults.standardUserDefaults.synchronize()
            }
            previous?.invoke(error)
        }
    }
}
