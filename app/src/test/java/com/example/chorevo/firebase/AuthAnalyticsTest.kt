package com.example.chorevo.firebase

import com.example.chorevo.core.telemetry.AnalyticsEvents
import com.example.chorevo.core.telemetry.Telemetry
import com.example.chorevo.firebase.repository.FirebaseAuthRepository
import com.example.chorevo.firebase.viewmodel.AuthViewModel
import com.example.chorevo.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthAnalyticsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private class RecordingTelemetry : Telemetry {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun setUserId(uid: String?) = Unit
        override fun logEvent(name: String, params: Map<String, Any?>) {
            events += name to params
        }
        override fun recordError(error: Throwable, where: String) = Unit
    }

    private fun googleSignIn(isNewAccount: Boolean): List<Pair<String, Map<String, Any?>>> {
        val repo = mockk<FirebaseAuthRepository>()
        coEvery { repo.signInWithGoogle("token") } returns Result.success(
            UserProfile(uid = "u1", email = "a@b.c", displayName = "A", isNewAccount = isNewAccount)
        )
        val telemetry = RecordingTelemetry()
        val vm = AuthViewModel(repo, telemetry)
        vm.completeGoogleSignIn("token")
        return telemetry.events
    }

    @Test
    fun firstGoogleSignIn_isLoggedAsSignUp() = runTest {
        val events = googleSignIn(isNewAccount = true).also { advanceUntilIdle() }
        advanceUntilIdle()
        assertEquals(listOf(AnalyticsEvents.SIGN_UP to mapOf(AnalyticsEvents.PARAM_METHOD to "google")), events)
    }

    @Test
    fun laterGoogleSignIns_areLoggedAsLogin() = runTest {
        val events = googleSignIn(isNewAccount = false)
        advanceUntilIdle()
        assertEquals(listOf(AnalyticsEvents.LOGIN to mapOf(AnalyticsEvents.PARAM_METHOD to "google")), events)
    }
}
