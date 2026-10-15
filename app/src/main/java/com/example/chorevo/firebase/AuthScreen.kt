package com.example.chorevo.firebase

import com.example.chorevo.ui.theme.PillShape
import com.example.chorevo.ui.theme.ChorevoTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chorevo.firebase.ViewModel.AuthEvent
import com.example.chorevo.firebase.ViewModel.AuthViewModel

/**
 * ✅ Single function with default vm.
 * Tests can call: AuthScreen(onAuthed = { ... })
 */
@Composable
fun AuthScreen(
    onAuthed: (UserProfile) -> Unit,
    vm: AuthViewModel,
    googleIdTokenProvider: GoogleIdTokenProvider = rememberGoogleIdTokenProvider()
) {
    val s by vm.uiState.collectAsStateWithLifecycle()

    // Listen for one-off "Authed" event from VM
    LaunchedEffect(vm, googleIdTokenProvider) {
        vm.events.collect { e ->
            when (e) {
                is AuthEvent.Authed -> onAuthed(e.profile)
                AuthEvent.LaunchGoogleSignIn -> {
                    googleIdTokenProvider.getGoogleIdToken()
                        .onSuccess(vm::completeGoogleSignIn)
                        .onFailure { error ->
                            vm.onExternalAuthCancelled(error.message)
                        }
                }
            }
        }
    }

    val primaryTeal = ChorevoTheme.colors.accent

    val authTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedLabelColor = primaryTeal,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = primaryTeal,
        focusedBorderColor = primaryTeal,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(horizontal = 20.dp)
    ) {
        Text(
            text = if (s.mode == AuthMode.LOGIN) "Login" else "Sign Up",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 10.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Image(
                painter = painterResource(id = com.example.chorevo.R.drawable.learnsense_logo),
                contentDescription = "LearnSense logo",
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(80.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(22.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Welcome",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    if (s.mode == AuthMode.SIGNUP) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = s.displayName,
                            onValueChange = vm::setDisplayName,
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            label = { Text("Display Name") },
                            colors = authTextFieldColors
                        )
                    }

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = s.email,
                        onValueChange = vm::setEmail,
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        label = { Text(if (s.mode == AuthMode.LOGIN) "Username / Email" else "Enter your Email") },
                        colors = authTextFieldColors
                    )

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = s.password,
                        onValueChange = vm::setPassword,
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        label = { Text("Enter your password") },
                        visualTransformation =
                            if (s.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = vm::togglePasswordVisible) {
                                Icon(
                                    imageVector =
                                        if (s.passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription =
                                        if (s.passwordVisible) "Hide password" else "Show password"
                                )
                            }
                        },
                        colors = authTextFieldColors
                    )

                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = !s.loading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryTeal,
                            contentColor = ChorevoTheme.colors.onAccent,
                            disabledContainerColor = primaryTeal.copy(alpha = 0.5f),
                            disabledContentColor = ChorevoTheme.colors.onAccent
                        ),
                        shape = PillShape,
                        onClick = vm::submit
                    ) {
                        Text(if (s.mode == AuthMode.LOGIN) "Login" else "Create Account")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = "or continue with",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    OutlinedButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        enabled = !s.loading,
                        shape = PillShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        onClick = vm::requestGoogleSignIn
                    ) {
                        Text(
                            text = if (s.loading && s.activeProvider == AuthProvider.GOOGLE) {
                                "Connecting to Google..."
                            } else {
                                "Continue with Google"
                            },
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    s.status?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    TextButton(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        onClick = vm::toggleMode
                    ) {
                        Text(
                            text = if (s.mode == AuthMode.LOGIN) "Sign up here" else "Login here",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
