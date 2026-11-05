package com.nuvio.app.features.watchparty

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

internal actual object WatchPartyPlatform {
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("watchparty_extras", Context.MODE_PRIVATE)
    }

    actual val name: String = "android"

    actual val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    actual fun getString(key: String): String? = preferences?.getString(key, null)

    actual fun putString(key: String, value: String?) {
        preferences?.edit()?.apply { if (value == null) remove(key) else putString(key, value) }?.commit()
    }

    actual fun installCrashCapture(onCrash: (Throwable) -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { onCrash(error) }
            previous?.uncaughtException(thread, error)
        }
    }
}
