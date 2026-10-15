package com.example.chorevo.firebase

import com.example.chorevo.ui.theme.PillShape
import com.example.chorevo.ui.theme.ChorevoTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.example.chorevo.core.DancingFigure
import com.example.chorevo.core.StageBackground
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
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
@OptIn(ExperimentalTextApi::class)
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

    val colors = ChorevoTheme.colors
    val primaryTeal = colors.accent

    val authTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedLabelColor = primaryTeal,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = primaryTeal,
        focusedBorderColor = primaryTeal,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedLeadingIconColor = primaryTeal,
        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
    )
    val emailLoading = s.loading && s.activeProvider == AuthProvider.EMAIL
    val googleLoading = s.loading && s.activeProvider == AuthProvider.GOOGLE

    StageBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DancingFigure(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Wordmark in the brand gradient.
            Text(
                text = "Chorevo",
                style = MaterialTheme.typography.displaySmall.copy(
                    brush = colors.brandGradient,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.testTag("authWordmark")
            )
            Text(
                text = "Learn to dance with live AI feedback",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .animateContentSize(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, colors.glassBorder)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Welcome",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (s.mode == AuthMode.LOGIN) {
                                "Sign in to keep dancing"
                            } else {
                                "Create an account to start dancing"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (s.mode == AuthMode.SIGNUP) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = s.displayName,
                            onValueChange = vm::setDisplayName,
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            label = { Text("Display Name") },
                            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                            colors = authTextFieldColors
                        )
                    }

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = s.email,
                        onValueChange = vm::setEmail,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        label = { Text(if (s.mode == AuthMode.LOGIN) "Username / Email" else "Enter your Email") },
                        leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = authTextFieldColors
                    )

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = s.password,
                        onValueChange = vm::setPassword,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        label = { Text("Enter your password") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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

                    // Gradient primary button.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(PillShape)
                            .background(colors.brandGradient)
                            .alpha(if (s.loading) 0.6f else 1f)
                    ) {
                        Button(
                            modifier = Modifier.fillMaxSize(),
                            enabled = !s.loading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = colors.onGradient,
                                disabledContainerColor = Color.Transparent,
                                disabledContentColor = colors.onGradient
                            ),
                            shape = PillShape,
                            onClick = vm::submit
                        ) {
                            if (emailLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.onGradient
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                if (s.mode == AuthMode.LOGIN) "Login" else "Create Account",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
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
                            .height(52.dp),
                        enabled = !s.loading,
                        shape = PillShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        onClick = vm::requestGoogleSignIn
                    ) {
                        if (googleLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            GoogleBadge()
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (googleLoading) "Connecting to Google..." else "Continue with Google",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    AnimatedVisibility(visible = s.status != null) {
                        s.status?.let {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = it,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (s.mode == AuthMode.LOGIN) "New to Chorevo?" else "Already have an account?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = vm::toggleMode) {
                            Text(
                                text = if (s.mode == AuthMode.LOGIN) "Sign up here" else "Login here",
                                color = primaryTeal,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Small white circle with a "G", used on the Google sign-in button. */
@Composable
private fun GoogleBadge() {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "G",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4285F4)
        )
    }
}
