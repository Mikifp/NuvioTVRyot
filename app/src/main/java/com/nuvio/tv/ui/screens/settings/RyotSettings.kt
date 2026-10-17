@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.ryot.RyotApiException
import com.nuvio.tv.data.ryot.RyotAuthStore
import com.nuvio.tv.data.ryot.RyotRepository
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RyotSettingsUiState(
    val isConnected: Boolean = false,
    val username: String? = null,
    val serverUrl: String? = null,
    val savedServerUrl: String? = null,
    val savedUsername: String? = null,
    val isConnecting: Boolean = false,
    val isSyncing: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class RyotSettingsViewModel @Inject constructor(
    private val repository: RyotRepository,
    private val auth: RyotAuthStore,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private data class Action(val connecting: Boolean = false, val error: String? = null)

    private val action = MutableStateFlow(Action())
    private var job: Job? = null

    val uiState = combine(auth.state, repository.state, action) { authState, sync, action ->
        val connected = authState.isAuthenticated
        val snapshot = sync.snapshot.takeIf { sync.profileId == authState.profileId }
        RyotSettingsUiState(
            isConnected = connected,
            username = authState.credentials?.username,
            serverUrl = authState.credentials?.serverUrl,
            savedServerUrl = authState.serverUrl,
            savedUsername = authState.username,
            isConnecting = action.connecting,
            isSyncing = connected && sync.refreshing,
            statusMessage = when {
                !connected -> null
                sync.refreshing -> context.getString(R.string.ryot_syncing)
                snapshot != null -> context.getString(
                    R.string.ryot_last_synced,
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(snapshot.syncedAt))
                ) + " · " + context.getString(R.string.ryot_summary, snapshot.items.size, snapshot.collections.size)
                else -> null
            },
            errorMessage = action.error ?: (if (connected) sync.error else authState.error)
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RyotSettingsUiState())

    fun connectWithPassword(server: String, username: String, password: String) {
        if (server.isBlank() || username.isBlank() || password.isEmpty()) {
            action.value = Action(error = context.getString(R.string.ryot_missing_fields))
            return
        }
        connect { profileId -> repository.connectWithPassword(profileId, server, username, password) }
    }

    fun connectWithToken(server: String, token: String) {
        if (server.isBlank() || token.isBlank()) {
            action.value = Action(error = context.getString(R.string.ryot_missing_fields))
            return
        }
        connect { profileId -> repository.connectWithToken(profileId, server, token) }
    }

    private fun connect(block: suspend (Int) -> Unit) {
        if (job?.isActive == true) return
        val profileId = auth.state.value.profileId
        action.value = Action(connecting = true)
        job = viewModelScope.launch {
            try {
                block(profileId)
                action.value = Action()
            } catch (error: CancellationException) {
                action.value = Action()
                throw error
            } catch (error: Exception) {
                action.value = Action(error = error.messageForUser())
            }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch {
            try {
                action.value = Action()
                repository.refresh(TrackingRefreshIntent.USER_INITIATED)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                action.value = Action(error = error.messageForUser())
            }
        }
    }

    fun onDisconnect() {
        job?.cancel()
        val profileId = auth.state.value.profileId
        action.value = Action()
        viewModelScope.launch { repository.disconnect(profileId) }
    }

    fun clearError() {
        if (action.value.error != null) action.value = action.value.copy(error = null)
    }

    private fun Exception.messageForUser(): String = when (this) {
        is RyotApiException -> message ?: "Ryot request failed"
        is IOException -> "Couldn't reach the Ryot server. Check the address and that this TV can reach it."
        else -> message ?: "Ryot request failed"
    }
}

@Composable
internal fun ryotConnectionPresentation(state: RyotSettingsUiState): TrackingConnectionPresentation = when {
    state.isConnecting -> TrackingConnectionPresentation(
        stringResource(R.string.tracking_connecting_provider, stringResource(R.string.ryot_name)),
        stringResource(R.string.tracking_status_connecting), NuvioTheme.colors.Info
    )
    state.isConnected -> TrackingConnectionPresentation(
        stringResource(R.string.ryot_connected_as, state.username ?: state.serverUrl.orEmpty()),
        stringResource(R.string.tracking_status_connected), NuvioTheme.colors.Success
    )
    else -> TrackingConnectionPresentation(
        state.errorMessage ?: stringResource(R.string.ryot_tracking_description),
        stringResource(R.string.tracking_status_disconnected), NuvioTheme.colors.TextSecondary
    )
}

@Composable
internal fun RyotAccountDialog(
    state: RyotSettingsUiState,
    onConnectWithPassword: (server: String, username: String, password: String) -> Unit,
    onConnectWithToken: (server: String, token: String) -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    if (state.isConnected) {
        NuvioDialog(
            onDismiss = onDismiss,
            title = stringResource(R.string.ryot_name),
            subtitle = stringResource(R.string.ryot_connected_as, state.username.orEmpty()) +
                (state.serverUrl?.let { " · $it" } ?: ""),
            width = 640.dp
        ) {
            state.statusMessage?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
            }
            state.errorMessage?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Error)
            }
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.ryot_close), onClick = onDismiss)
                SettingsDialogActionButton(text = stringResource(R.string.ryot_disconnect), onClick = onDisconnect)
                SettingsDialogActionButton(
                    text = stringResource(if (state.isSyncing) R.string.ryot_syncing else R.string.ryot_sync_now),
                    onClick = onSync,
                    enabled = !state.isSyncing,
                    primary = true
                )
            }
        }
        return
    }

    var useToken by remember { mutableStateOf(false) }
    var server by remember(state.savedServerUrl) { mutableStateOf(state.savedServerUrl.orEmpty()) }
    var username by remember(state.savedUsername) { mutableStateOf(state.savedUsername.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.ryot_connect_title),
        subtitle = stringResource(R.string.ryot_connect_subtitle),
        width = 720.dp
    ) {
        SettingsDialogActionRow(horizontalAlignment = androidx.compose.ui.Alignment.Start) {
            SettingsDialogActionButton(
                text = stringResource(R.string.ryot_mode_password),
                onClick = { useToken = false },
                primary = !useToken
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.ryot_mode_token),
                onClick = { useToken = true },
                primary = useToken
            )
        }
        RyotTextField(
            label = stringResource(R.string.ryot_server_label),
            value = server,
            placeholder = stringResource(R.string.ryot_server_placeholder),
            keyboardType = KeyboardType.Uri,
            onValueChange = { server = it }
        )
        if (useToken) {
            RyotTextField(
                label = stringResource(R.string.ryot_token_label),
                value = token,
                placeholder = stringResource(R.string.ryot_token_placeholder),
                keyboardType = KeyboardType.Password,
                secret = true,
                onValueChange = { token = it }
            )
        } else {
            RyotTextField(
                label = stringResource(R.string.ryot_username_label),
                value = username,
                placeholder = "",
                keyboardType = KeyboardType.Text,
                onValueChange = { username = it }
            )
            RyotTextField(
                label = stringResource(R.string.ryot_password_label),
                value = password,
                placeholder = "",
                keyboardType = KeyboardType.Password,
                secret = true,
                onValueChange = { password = it }
            )
        }
        state.errorMessage?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.Error)
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            SettingsDialogActionButton(
                text = stringResource(if (state.isConnecting) R.string.ryot_connecting else R.string.ryot_connect),
                enabled = !state.isConnecting,
                primary = true,
                onClick = {
                    if (useToken) onConnectWithToken(server, token) else onConnectWithPassword(server, username, password)
                }
            )
        }
    }
}

@Composable
private fun RyotTextField(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    secret: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextSecondary)
        Card(
            onClick = {
                focusRequester.requestFocus()
                keyboard?.show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = shape),
                focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
            ),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onKeyEvent { event ->
                            // Let the remote's OK button open the keyboard instead of being swallowed.
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false
                    ),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = NuvioTheme.colors.TextPrimary,
                        textDirection = TextDirection.Content
                    ),
                    cursorBrush = SolidColor(if (focused) NuvioTheme.colors.Primary else androidx.compose.ui.graphics.Color.Transparent),
                    decorationBox = { inner ->
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(text = placeholder, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextTertiary)
                        }
                        inner()
                    }
                )
            }
        }
    }
}
