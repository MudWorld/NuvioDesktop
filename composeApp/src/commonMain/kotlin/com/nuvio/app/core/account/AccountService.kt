package com.nuvio.app.core.account

import kotlinx.coroutines.flow.StateFlow

/** Signed-in account on the MudWorld sync server (replaces the Nuvio/Supabase login on desktop). */
data class AccountSession(
    val userId: String,
    val username: String,
    val isAdmin: Boolean,
)

data class AccountSyncStatus(
    val syncing: Boolean = false,
    val lastSyncEpochMs: Long = 0L,
    val lastError: String? = null,
    /** Newer data from another device is downloaded and applied on the next start. */
    val pendingRestart: Boolean = false,
)

/** What happened after a successful sign-in. */
enum class AccountSignInOutcome {
    /** The account was empty; this device's data was uploaded. Nothing else to do. */
    UploadedLocalData,

    /** The account already had data; it is applied after the app restarts. */
    RestartRequired,
}

/** Server error codes (see nuvio-account/server.py) plus "network". */
class AccountException(val code: String) : Exception(code)

expect object AccountService {
    val supported: Boolean
    val serverUrl: String
    val session: StateFlow<AccountSession?>
    val syncStatus: StateFlow<AccountSyncStatus>

    suspend fun signIn(username: String, password: String): Result<AccountSignInOutcome>
    suspend fun register(username: String, password: String, invite: String): Result<AccountSignInOutcome>

    /** Uploads the latest data, wipes this device's Nuvio data and restarts the app. */
    suspend fun signOut()
    suspend fun createInvite(): Result<String>
    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit>
    suspend fun syncNow(): Result<Unit>

    /** Uploads changes a few seconds from now (playback paused, closed or switched). */
    fun requestSync()
    fun restartApp()
}
