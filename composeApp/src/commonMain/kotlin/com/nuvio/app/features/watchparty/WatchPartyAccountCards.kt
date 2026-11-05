package com.nuvio.app.features.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Lets a phone, tablet or PC that is signed in approve a TV that shows a sign-in code. */
@Composable
fun TvLoginApprovalCard() {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var device by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio

    fun failure(error: Throwable): org.jetbrains.compose.resources.StringResource =
        if ((error as? WatchPartyServerException)?.code == "invalid_code") Res.string.tvlogin_invalid else Res.string.tvlogin_failed

    fun check() {
        if (busy || TvLoginApproval.normalize(code).length != 6) return
        busy = true
        message = null
        scope.launch {
            TvLoginApproval.deviceFor(code)
                .onSuccess { device = it }
                .onFailure {
                    isError = true
                    message = getString(failure(it))
                }
            busy = false
        }
    }

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.tvlogin_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.tvlogin_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        val pending = device
        if (pending == null) {
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.uppercase().filter { it.isLetterOrDigit() || it == '-' || it == ' ' }.take(8) },
                label = { Text(stringResource(Res.string.tvlogin_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { check() }),
            )
            TextButton(onClick = { check() }, enabled = !busy && TvLoginApproval.normalize(code).length == 6) {
                Text(stringResource(Res.string.tvlogin_check))
            }
        } else {
            Text(
                text = stringResource(Res.string.tvlogin_confirm, pending),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        TvLoginApproval.approve(code)
                            .onSuccess { name ->
                                isError = false
                                message = getString(Res.string.tvlogin_done, name)
                                code = ""
                                device = null
                            }
                            .onFailure {
                                isError = true
                                message = getString(failure(it))
                                device = null
                            }
                        busy = false
                    }
                }) { Text(stringResource(Res.string.tvlogin_allow)) }
                TextButton(onClick = { device = null }) { Text(stringResource(Res.string.action_cancel)) }
            }
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (isError) tokens.colors.danger else tokens.colors.accent)
        }
    }
}

/** Opt-in switch for error reports plus a button that sends the recent log right away. */
@Composable
fun DiagnosticsCard() {
    val scope = rememberCoroutineScope()
    val enabled by DiagnosticsReporter.enabled.collectAsStateWithLifecycle()
    var sending by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.diagnostics_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.diagnostics_switch),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.diagnostics_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
            }
            Switch(checked = enabled, onCheckedChange = { DiagnosticsReporter.setEnabled(it) })
        }
        if (enabled) {
            TextButton(enabled = !sending, onClick = {
                sending = true
                scope.launch {
                    DiagnosticsReporter.sendNow()
                        .onSuccess { NuvioToastController.show(getString(Res.string.diagnostics_sent)) }
                        .onFailure { NuvioToastController.show(getString(Res.string.diagnostics_failed)) }
                    sending = false
                }
            }) { Text(stringResource(Res.string.diagnostics_send_now)) }
        }
    }
}
