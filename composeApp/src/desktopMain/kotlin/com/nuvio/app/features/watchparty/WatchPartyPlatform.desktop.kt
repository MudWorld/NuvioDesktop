package com.nuvio.app.features.watchparty

import com.nuvio.app.core.storage.DesktopStorage

internal actual object WatchPartyPlatform {
    /** Device-local: excluded from the PC account sync (see AccountService.desktop). */
    private val store = DesktopStorage.store("watchparty_extras")

    actual val name: String = "pc"

    actual val deviceName: String =
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.let { "PC $it" } ?: "PC"

    actual fun getString(key: String): String? = store.getString(key)

    actual fun putString(key: String, value: String?) {
        store.putString(key, value)
    }

    actual fun installCrashCapture(onCrash: (Throwable) -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { onCrash(error) }
            previous?.uncaughtException(thread, error)
        }
    }
}
