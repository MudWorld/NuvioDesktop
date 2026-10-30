package com.nuvio.app.core.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The MudWorld account server is only wired up in the desktop app. */
actual object AccountService {
    actual val supported: Boolean = false
    actual val serverUrl: String = ""
    actual val session: StateFlow<AccountSession?> = MutableStateFlow(null)
    actual val syncStatus: StateFlow<AccountSyncStatus> = MutableStateFlow(AccountSyncStatus())

    actual suspend fun signIn(username: String, password: String): Result<AccountSignInOutcome> =
        Result.failure(AccountException("unsupported"))

    actual suspend fun register(username: String, password: String, invite: String): Result<AccountSignInOutcome> =
        Result.failure(AccountException("unsupported"))

    actual suspend fun signOut() = Unit

    actual suspend fun createInvite(): Result<String> = Result.failure(AccountException("unsupported"))

    actual suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> =
        Result.failure(AccountException("unsupported"))

    actual suspend fun syncNow(): Result<Unit> = Result.failure(AccountException("unsupported"))

    actual val canImportFromDesktop: Boolean = false

    actual suspend fun importFromDesktop(): Result<Int> = Result.failure(AccountException("unsupported"))

    actual fun requestSync() = Unit

    actual fun restartApp() = Unit
}
