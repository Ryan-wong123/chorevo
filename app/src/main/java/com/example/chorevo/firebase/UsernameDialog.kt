package com.example.chorevo.firebase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chorevo.firebase.viewmodel.UsernameViewModel
import com.example.chorevo.ui.theme.ChorevoTheme

/**
 * Pick (or change) a unique username. With [required] (users without one,
 * after signing in) it can't be tapped or backed away; "Later" puts it off
 * until the next launch.
 */
@Composable
fun UsernameDialog(
    profile: UserProfile,
    required: Boolean,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit = {},
    vm: UsernameViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(profile.uid) { vm.start(profile.username, profile.displayName) }

    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !required, dismissOnClickOutside = !required),
        title = { Text(if (required) "Choose a username" else "Change username") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Friends use it to invite you to groups. a–z, 0–9 and _, 3–20 characters.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = state.input,
                    onValueChange = vm::setInput,
                    prefix = { Text("@") },
                    singleLine = true,
                    enabled = !state.isSaving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    isError = state.problem != null || state.isAvailable == false,
                    trailingIcon = {
                        when {
                            state.isChecking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            state.isAvailable == true && state.problem == null ->
                                Icon(Icons.Filled.CheckCircle, contentDescription = "Available", tint = ChorevoTheme.colors.accent)
                            state.isAvailable == false || state.problem != null ->
                                Icon(Icons.Filled.ErrorOutline, contentDescription = "Not available", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    supportingText = {
                        val text = state.error
                            ?: state.problem
                            ?: when (state.isAvailable) {
                                true -> "@${state.input} is available"
                                false -> "@${state.input} is taken"
                                null -> null
                            }
                        if (text != null) Text(text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("usernameField")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.save(profile.uid, onSaved) },
                enabled = state.canSave,
                modifier = Modifier.testTag("saveUsernameButton")
            ) { Text(if (state.isSaving) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("usernameLaterButton")) {
                Text(if (required) "Later" else "Cancel")
            }
        }
    )
}
