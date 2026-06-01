package com.example.chorevo.firebase

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.CredentialManager
import com.example.chorevo.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

interface GoogleIdTokenProvider {
    suspend fun getGoogleIdToken(): Result<String>
}

@Composable
fun rememberGoogleIdTokenProvider(): GoogleIdTokenProvider {
    val context = LocalContext.current
    return remember(context) {
        FirebaseGoogleIdTokenProvider(context)
    }
}

private class FirebaseGoogleIdTokenProvider(
    context: Context
) : GoogleIdTokenProvider {

    private val activity = context.findActivity()
    private val credentialManager = CredentialManager.create(context)
    private val serverClientId = context.getString(R.string.default_web_client_id)

    override suspend fun getGoogleIdToken(): Result<String> = runCatching {
        requestGoogleIdToken(filterByAuthorizedAccounts = true)
            ?: requestGoogleIdToken(filterByAuthorizedAccounts = false)
            ?: error("No Google account is available on this device.")
    }

    private suspend fun requestGoogleIdToken(
        filterByAuthorizedAccounts: Boolean
    ): String? {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val response = try {
            credentialManager.getCredential(
                context = activity,
                request = request
            )
        } catch (e: GetCredentialException) {
            if (filterByAuthorizedAccounts) return null
            throw e
        }

        return response.googleIdTokenOrNull()
    }

    private fun Context.findActivity(): ComponentActivity {
        var current = this
        while (current is ContextWrapper) {
            if (current is ComponentActivity) return current
            current = current.baseContext
        }
        error("Google sign-in requires a ComponentActivity context.")
    }

    private fun GetCredentialResponse.googleIdTokenOrNull(): String {
        val credential = credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }

        error("Google sign-in returned an unsupported credential type.")
    }
}
