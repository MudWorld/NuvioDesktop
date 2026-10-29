package com.nuvio.app.features.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.account.AccountException
import com.nuvio.app.core.account.AccountService
import com.nuvio.app.core.account.AccountSignInOutcome
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Full-screen sign-in shown instead of the Nuvio/Supabase login. */
@Composable
fun AccountLoginScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Nuvio",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.nuvio.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.account_login_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.nuvio.colors.textSecondary,
            )
            AccountForm()
            TextButton(
                onClick = { AuthRepository.signInAnonymously() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(Res.string.compose_auth_continue_without_account))
            }
        }
    }
}

/** Sign-in / registration form; on success with existing data it restarts the app. */
@Composable
fun AccountForm() {
    val scope = rememberCoroutineScope()
    var isRegister by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordRepeat by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var restarting by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio

    LaunchedEffect(restarting) {
        if (restarting) {
            delay(1500)
            AccountService.restartApp()
        }
    }

    fun submit() {
        if (busy) return
        error = null
        scope.launch {
            if (username.isBlank() || password.isBlank()) {
                error = getString(Res.string.account_error_fill_fields)
                return@launch
            }
            if (isRegister && password != passwordRepeat) {
                error = getString(Res.string.account_error_password_mismatch)
                return@launch
            }
            busy = true
            val result = if (isRegister) {
                AccountService.register(username.trim(), password, invite.trim())
            } else {
                AccountService.signIn(username.trim(), password)
            }
            busy = false
            result
                .onSuccess { outcome ->
                    password = ""
                    passwordRepeat = ""
                    if (outcome == AccountSignInOutcome.RestartRequired) {
                        restarting = true
                    } else {
                        NuvioToastController.show(getString(Res.string.account_uploaded))
                    }
                }
                .onFailure { error = getString(errorMessage(it)) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(stringResource(Res.string.account_sign_in), selected = !isRegister) {
                isRegister = false
                error = null
            }
            ModeChip(stringResource(Res.string.account_register), selected = isRegister) {
                isRegister = true
                error = null
            }
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(Res.string.account_username)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(Res.string.account_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (isRegister) ImeAction.Next else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (isRegister) {
            OutlinedTextField(
                value = passwordRepeat,
                onValueChange = { passwordRepeat = it },
                label = { Text(stringResource(Res.string.account_password_repeat)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = invite,
                onValueChange = { invite = it.uppercase() },
                label = { Text(stringResource(Res.string.account_invite)) },
                supportingText = { Text(stringResource(Res.string.account_invite_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Text(it, color = tokens.colors.danger, style = MaterialTheme.typography.bodyMedium) }
        if (restarting) {
            Text(stringResource(Res.string.account_restarting), color = tokens.colors.accent)
        }
        NuvioPrimaryButton(
            text = when {
                busy -> "…"
                isRegister -> stringResource(Res.string.account_register)
                else -> stringResource(Res.string.account_sign_in)
            },
            enabled = !busy && !restarting,
            onClick = ::submit,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(Res.string.account_server_note, AccountService.serverUrl.removePrefix("https://")),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    TextButton(
        onClick = onClick,
        modifier = Modifier.background(
            if (selected) tokens.colors.accent.copy(alpha = 0.18f) else Color.Transparent,
            RoundedCornerShape(12.dp),
        ),
    ) {
        Text(
            text = label,
            color = if (selected) tokens.colors.textPrimary else tokens.colors.textMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Account panel for Settings → Account on desktop. */
@Composable
fun AccountSettingsPanel() {
    val session by AccountService.session.collectAsStateWithLifecycle()
    val status by AccountService.syncStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showSignOut by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NuvioSurfaceCard {
            Text(
                text = stringResource(Res.string.account_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            val current = session
            if (current == null) {
                Text(
                    text = stringResource(Res.string.account_not_signed_in),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                AccountForm()
            } else {
                Text(
                    text = stringResource(Res.string.account_signed_in_as, current.username),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        status.syncing -> stringResource(Res.string.account_syncing)
                        status.lastError != null -> stringResource(errorMessage(AccountException(status.lastError!!)))
                        status.lastSyncEpochMs > 0 -> stringResource(Res.string.account_synced)
                        else -> stringResource(Res.string.account_sync_auto)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.lastError != null) tokens.colors.danger else tokens.colors.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch { AccountService.syncNow() } }) {
                        Text(stringResource(Res.string.account_sync_now))
                    }
                    TextButton(onClick = { showPassword = true }) {
                        Text(stringResource(Res.string.account_change_password))
                    }
                    if (current.isAdmin) {
                        TextButton(onClick = {
                            scope.launch {
                                AccountService.createInvite()
                                    .onSuccess { invite = it }
                                    .onFailure { NuvioToastController.show(getString(errorMessage(it))) }
                            }
                        }) {
                            Text(stringResource(Res.string.account_create_invite))
                        }
                    }
                }
                invite?.let { code ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.account_invite_created, code),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.accent,
                    )
                    TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                        Text(stringResource(Res.string.account_copy))
                    }
                }
            }
        }
        if (session != null) {
            NuvioPrimaryButton(
                text = stringResource(Res.string.account_sign_out),
                onClick = { showSignOut = true },
            )
        }
    }

    NuvioStatusModal(
        title = stringResource(Res.string.account_sign_out),
        message = stringResource(Res.string.account_sign_out_message),
        isVisible = showSignOut,
        confirmText = stringResource(Res.string.account_sign_out),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            showSignOut = false
            scope.launch { AccountService.signOut() }
        },
        onDismiss = { showSignOut = false },
    )

    if (showPassword) {
        ChangePasswordDialog(onDismiss = { showPassword = false })
    }
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.account_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it },
                    label = { Text(stringResource(Res.string.account_password_old)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it },
                    label = { Text(stringResource(Res.string.account_password_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                error?.let { Text(it, color = MaterialTheme.nuvio.colors.danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    AccountService.changePassword(old, new)
                        .onSuccess {
                            NuvioToastController.show(getString(Res.string.account_password_changed))
                            onDismiss()
                        }
                        .onFailure { error = getString(errorMessage(it)) }
                }
            }) { Text(stringResource(Res.string.livetv_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

private fun errorMessage(error: Throwable): StringResource = when ((error as? AccountException)?.code) {
    "invalid_credentials" -> Res.string.account_error_credentials
    "invalid_invite" -> Res.string.account_error_invite
    "username_taken" -> Res.string.account_error_taken
    "invalid_username" -> Res.string.account_error_username
    "weak_password" -> Res.string.account_error_weak
    "too_many_attempts" -> Res.string.account_error_rate
    "unauthorized" -> Res.string.account_error_session
    "account_full" -> Res.string.account_error_full
    "network" -> Res.string.account_error_network
    else -> Res.string.account_error_generic
}
