package com.nuvio.app.features.remote

/** Implemented in Swift (`AppleTVRemote.swift`): the iPad remote for the WatchParty Apple TV app. */
interface RemoteControlHost {
    fun openRemote()
    fun playLiveChannel(name: String, url: String, logo: String?, group: String?)
}

object RemoteControlBridge {
    var host: RemoteControlHost? = null
}
