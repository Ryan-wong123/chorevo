package com.example.chorevo.firebase

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
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
    vm: AuthViewModel
) {
    val s by vm.uiState.collectAsStateWithLifecycle()

    // Listen for one-off "Authed" event from VM
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is AuthEvent.Authed -> onAuthed(e.profile)
            }
        }
    }

    val primaryTeal = Color(0xFF00B8C8)

    val authTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedLabelColor = Color.Black,
        unfocusedLabelColor = Color.Black,
        cursorColor = Color.Black,
        focusedBorderColor = Color.Black,
        unfocusedBorderColor = Color.Black,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFF2F5F8),
                        Color(0xFFFFFFFF)
                    )
                )
            )
            .padding(horizontal = 20.dp)
    ) {
        Text(
            text = if (s.mode == AuthMode.LOGIN) "Login" else "Sign Up",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A8A8A),
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
                color = Color.White
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Welcome",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF111111),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    if (s.mode == AuthMode.SIGNUP) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = s.displayName,
                            onValueChange = vm::setDisplayName,
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            label = { Text("Display Name") },
                            colors = authTextFieldColors
                        )
                    }

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = s.email,
                        onValueChange = vm::setEmail,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(if (s.mode == AuthMode.LOGIN) "Username / Email" else "Enter your Email") },
                        colors = authTextFieldColors
                    )

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = s.password,
                        onValueChange = vm::setPassword,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
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
                            contentColor = Color.White,
                            disabledContainerColor = primaryTeal.copy(alpha = 0.5f),
                            disabledContentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        onClick = vm::submit
                    ) {
                        Text(if (s.mode == AuthMode.LOGIN) "Login" else "Create Account")
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
                            color = Color(0xFF2D2D2D)
                        )
                    }
                }
            }
        }
    }
}