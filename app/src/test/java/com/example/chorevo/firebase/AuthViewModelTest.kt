package com.example.chorevo.firebase

import com.example.chorevo.core.telemetry.AnalyticsEvents
import com.example.chorevo.core.telemetry.Telemetry
import com.example.chorevo.firebase.repository.FirebaseAuthRepository
import com.example.chorevo.firebase.viewmodel.AuthEvent
import com.example.chorevo.firebase.viewmodel.AuthUiState
import com.example.chorevo.firebase.viewmodel.AuthViewModel
import com.example.chorevo.firebase.viewmodel.authErrorMessage
import com.example.chorevo.testing.MainDispatcherRule
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Email login / sign-up, validation, password reset, UI-state setters and
 * error mapping for [AuthViewModel]. Google analytics lives in AuthAnalyticsTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

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

    private lateinit var repo: FirebaseAuthRepository
    private lateinit var telemetry: RecordingTelemetry
    private lateinit var vm: AuthViewModel

    private val profile = UserProfile(uid = "u1", email = "a@b.com", displayName = "Ann")

    @Before
    fun setUp() {
        repo = mockk()
        telemetry = RecordingTelemetry()
        vm = AuthViewModel(repo, telemetry)
    }

    /** Collects [AuthViewModel.events] eagerly (a SharedFlow drops emissions nobody is collecting). */
    private fun TestScope.collectEvents(): MutableList<AuthEvent> {
        val out = mutableListOf<AuthEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(out) }
        return out
    }

    // ── state setters ───────────────────────────────────────────────────

    @Test
    fun initialState_isLoginWithEmptyFields() {
        val s = vm.uiState.value
        assertEquals(AuthUiState(), s)
        assertEquals(AuthMode.LOGIN, s.mode)
        assertEquals("", s.email)
        assertFalse(s.loading)
        assertFalse(s.passwordVisible)
        assertNull(s.activeProvider)
    }

    @Test
    fun fieldSetters_updateState() {
        vm.setEmail("a@b.com")
        vm.setPassword("secret123")
        vm.setDisplayName("Ann")

        val s = vm.uiState.value
        assertEquals("a@b.com", s.email)
        assertEquals("secret123", s.password)
        assertEquals("Ann", s.displayName)
    }

    @Test
    fun togglePasswordVisible_flipsEachCall() {
        vm.togglePasswordVisible()
        assertTrue(vm.uiState.value.passwordVisible)
        vm.togglePasswordVisible()
        assertFalse(vm.uiState.value.passwordVisible)
    }

    @Test
    fun toggleMode_switchesBetweenLoginAndSignupAndClearsStatus() = runTest {
        // Produce a status message first.
        vm.setMode(AuthMode.SIGNUP)
        vm.submit()
        assertEquals("Please enter a display name.", vm.uiState.value.status)

        vm.toggleMode()
        assertEquals(AuthMode.LOGIN, vm.uiState.value.mode)
        assertNull(vm.uiState.value.status)

        vm.toggleMode()
        assertEquals(AuthMode.SIGNUP, vm.uiState.value.mode)
    }

    @Test
    fun setMode_setsModeAndClearsStatus() {
        vm.setMode(AuthMode.SIGNUP)
        vm.submit()
        assertEquals(AuthMode.SIGNUP, vm.uiState.value.mode)

        vm.setMode(AuthMode.LOGIN)

        assertEquals(AuthMode.LOGIN, vm.uiState.value.mode)
        assertNull(vm.uiState.value.status)
    }

    // ── sign-up validation ──────────────────────────────────────────────

    @Test
    fun submit_signupWithBlankDisplayName_showsMessageAndSkipsRepo() = runTest {
        vm.setMode(AuthMode.SIGNUP)
        vm.setEmail("a@b.com")
        vm.setPassword("longenough1")
        vm.setDisplayName("   ")

        vm.submit()
        advanceUntilIdle()

        assertEquals("Please enter a display name.", vm.uiState.value.status)
        assertFalse(vm.uiState.value.loading)
        coVerify(exactly = 0) { repo.signUp(any(), any(), any()) }
    }

    @Test
    fun submit_signupWithShortPassword_showsMessageAndSkipsRepo() = runTest {
        vm.setMode(AuthMode.SIGNUP)
        vm.setEmail("a@b.com")
        vm.setPassword("1234567")
        vm.setDisplayName("Ann")

        vm.submit()
        advanceUntilIdle()

        assertEquals("Use a password with at least 8 characters.", vm.uiState.value.status)
        coVerify(exactly = 0) { repo.signUp(any(), any(), any()) }
        assertTrue(telemetry.events.isEmpty())
    }

    @Test
    fun submit_signupPasswordOfExactlyEightCharacters_isAccepted() = runTest {
        coEvery { repo.signUp("a@b.com", "12345678", "Ann") } returns Result.success(profile)
        vm.setMode(AuthMode.SIGNUP)
        vm.setEmail("a@b.com")
        vm.setPassword("12345678")
        vm.setDisplayName("Ann")

        vm.submit()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.signUp("a@b.com", "12345678", "Ann") }
        assertNull(vm.uiState.value.status)
    }

    // ── sign-up flow ────────────────────────────────────────────────────

    @Test
    fun submit_signupSuccess_trimsInputs_emitsAuthed_andLogsSignUp() = runTest {
        val events = collectEvents()
        coEvery { repo.signUp("a@b.com", "password1", "Ann") } returns Result.success(profile)
        vm.setMode(AuthMode.SIGNUP)
        vm.setEmail("  a@b.com  ")
        vm.setPassword("password1")
        vm.setDisplayName("  Ann  ")

        vm.submit()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.signUp("a@b.com", "password1", "Ann") }
        assertEquals(listOf<AuthEvent>(AuthEvent.Authed(profile)), events)
        assertEquals(
            listOf(AnalyticsEvents.SIGN_UP to mapOf(AnalyticsEvents.PARAM_METHOD to "email")),
            telemetry.events
        )
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNull(s.activeProvider)
        assertNull(s.status)
    }

    @Test
    fun submit_signupEmailCollision_showsGenericMessage_andEmitsNothing() = runTest {
        val events = collectEvents()
        coEvery { repo.signUp(any(), any(), any()) } returns
            Result.failure(mockk<FirebaseAuthUserCollisionException>())
        vm.setMode(AuthMode.SIGNUP)
        vm.setEmail("a@b.com")
        vm.setPassword("password1")
        vm.setDisplayName("Ann")

        vm.submit()
        advanceUntilIdle()

        assertEquals(
            "Couldn't create an account with that email. Try signing in instead.",
            vm.uiState.value.status
        )
        assertTrue(events.isEmpty())
        assertTrue(telemetry.events.isEmpty())
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun submit_signupWeakPasswordFromServer_showsStrongerPasswordMessage() = runTest {
        coEvery { repo.signUp(any(), any(), any()) } returns
            Result.failure(mockk<FirebaseAuthWeakPasswordException>())
        vm.setMode(AuthMode.SIGNUP)
        vm.setEmail("a@b.com")
        vm.setPassword("password1")
        vm.setDisplayName("Ann")

        vm.submit()
        advanceUntilIdle()

        assertEquals("Use a stronger password (at least 8 characters).", vm.uiState.value.status)
    }

    // ── login flow ──────────────────────────────────────────────────────

    @Test
    fun submit_loginSuccess_trimsEmail_emitsAuthed_andLogsLogin() = runTest {
        val events = collectEvents()
        coEvery { repo.signIn("a@b.com", " pw ") } returns Result.success(profile)
        vm.setEmail("  a@b.com ")
        // Passwords are sent untouched (only the email is trimmed).
        vm.setPassword(" pw ")

        vm.submit()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.signIn("a@b.com", " pw ") }
        coVerify(exactly = 0) { repo.signUp(any(), any(), any()) }
        assertEquals(listOf<AuthEvent>(AuthEvent.Authed(profile)), events)
        assertEquals(
            listOf(AnalyticsEvents.LOGIN to mapOf(AnalyticsEvents.PARAM_METHOD to "email")),
            telemetry.events
        )
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun submit_loginDoesNotEnforcePasswordLengthOrDisplayName() = runTest {
        coEvery { repo.signIn("a@b.com", "x") } returns Result.success(profile)
        vm.setEmail("a@b.com")
        vm.setPassword("x")

        vm.submit()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.signIn("a@b.com", "x") }
    }

    @Test
    fun submit_loginInvalidCredentials_showsSameMessageAsUnknownUser() = runTest {
        val events = collectEvents()
        coEvery { repo.signIn("a@b.com", "bad") } returns
            Result.failure(mockk<FirebaseAuthInvalidCredentialsException>())
        vm.setEmail("a@b.com")
        vm.setPassword("bad")

        vm.submit()
        advanceUntilIdle()
        val wrongPassword = vm.uiState.value.status

        coEvery { repo.signIn("nobody@b.com", "bad") } returns
            Result.failure(mockk<FirebaseAuthInvalidUserException>())
        vm.setEmail("nobody@b.com")
        vm.submit()
        advanceUntilIdle()
        val unknownUser = vm.uiState.value.status

        assertEquals("Email or password is incorrect.", wrongPassword)
        assertEquals(wrongPassword, unknownUser)
        assertTrue(events.isEmpty())
        assertTrue(telemetry.events.isEmpty())
        assertFalse(vm.uiState.value.loading)
        assertNull(vm.uiState.value.activeProvider)
    }

    @Test
    fun submit_loginUnknownError_showsExceptionMessage() = runTest {
        coEvery { repo.signIn(any(), any()) } returns Result.failure(RuntimeException("Network down"))
        vm.setEmail("a@b.com")
        vm.setPassword("password1")

        vm.submit()
        advanceUntilIdle()

        assertEquals("Network down", vm.uiState.value.status)
    }

    @Test
    fun submit_whileRequestInFlight_isIgnoredAndShowsLoadingState() = runTest {
        val gate = CompletableDeferred<Result<UserProfile>>()
        coEvery { repo.signIn("a@b.com", "password1") } coAnswers { gate.await() }
        vm.setEmail("a@b.com")
        vm.setPassword("password1")

        vm.submit()
        runCurrent()

        assertTrue(vm.uiState.value.loading)
        assertEquals(AuthProvider.EMAIL, vm.uiState.value.activeProvider)
        assertNull(vm.uiState.value.status)

        vm.submit()
        runCurrent()
        coVerify(exactly = 1) { repo.signIn("a@b.com", "password1") }

        gate.complete(Result.success(profile))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertNull(vm.uiState.value.activeProvider)
    }

    @Test
    fun submit_clearsEarlierErrorWhenRetrying() = runTest {
        coEvery { repo.signIn("a@b.com", "password1") } returnsMany listOf(
            Result.failure(RuntimeException("first failure")),
            Result.success(profile)
        )
        vm.setEmail("a@b.com")
        vm.setPassword("password1")

        vm.submit()
        advanceUntilIdle()
        assertEquals("first failure", vm.uiState.value.status)

        vm.submit()
        advanceUntilIdle()
        assertNull(vm.uiState.value.status)
    }

    // ── password reset ──────────────────────────────────────────────────

    @Test
    fun sendPasswordReset_blankEmail_promptsForEmail() = runTest {
        vm.sendPasswordReset()
        advanceUntilIdle()

        assertEquals("Enter your email above, then tap \"Forgot password?\".", vm.uiState.value.status)
        coVerify(exactly = 0) { repo.sendPasswordReset(any()) }
    }

    @Test
    fun sendPasswordReset_emailWithoutAt_promptsForEmail() = runTest {
        vm.setEmail("not-an-email")

        vm.sendPasswordReset()
        advanceUntilIdle()

        assertEquals("Enter your email above, then tap \"Forgot password?\".", vm.uiState.value.status)
        coVerify(exactly = 0) { repo.sendPasswordReset(any()) }
    }

    @Test
    fun sendPasswordReset_validEmail_sendsTrimmedEmailAndShowsNeutralMessage() = runTest {
        coJustRun { repo.sendPasswordReset("a@b.com") }
        vm.setEmail("  a@b.com ")

        vm.sendPasswordReset()
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.sendPasswordReset("a@b.com") }
        assertEquals(
            "If an account exists for a@b.com, we've emailed a link to reset the password.",
            vm.uiState.value.status
        )
    }

    // ── Google entry points / external cancel ───────────────────────────

    @Test
    fun requestGoogleSignIn_setsLoadingAndEmitsLaunchEvent() = runTest {
        val events = collectEvents()

        vm.requestGoogleSignIn()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.loading)
        assertEquals(AuthProvider.GOOGLE, s.activeProvider)
        assertEquals(listOf<AuthEvent>(AuthEvent.LaunchGoogleSignIn), events)
    }

    @Test
    fun requestGoogleSignIn_whileLoading_isIgnored() = runTest {
        val events = collectEvents()
        vm.requestGoogleSignIn()
        advanceUntilIdle()

        vm.requestGoogleSignIn()
        advanceUntilIdle()

        assertEquals(1, events.size)
    }

    @Test
    fun onExternalAuthCancelled_resetsLoadingProviderAndSetsMessage() = runTest {
        vm.requestGoogleSignIn()
        advanceUntilIdle()

        vm.onExternalAuthCancelled("Sign-in cancelled")

        val s = vm.uiState.value
        assertFalse(s.loading)
        assertNull(s.activeProvider)
        assertEquals("Sign-in cancelled", s.status)
    }

    @Test
    fun onExternalAuthCancelled_withoutMessage_clearsStatus() = runTest {
        vm.requestGoogleSignIn()
        advanceUntilIdle()

        vm.onExternalAuthCancelled()

        assertNull(vm.uiState.value.status)
        assertFalse(vm.uiState.value.loading)
    }

    // ── authErrorMessage ────────────────────────────────────────────────

    @Test
    fun authErrorMessage_unknownUserAndBadCredentialsReadTheSame() {
        val a = authErrorMessage(mockk<FirebaseAuthInvalidUserException>())
        val b = authErrorMessage(mockk<FirebaseAuthInvalidCredentialsException>())

        assertEquals("Email or password is incorrect.", a)
        assertEquals(a, b)
    }

    @Test
    fun authErrorMessage_collision_doesNotRevealAccountExists() {
        val msg = authErrorMessage(mockk<FirebaseAuthUserCollisionException>())

        assertEquals("Couldn't create an account with that email. Try signing in instead.", msg)
    }

    @Test
    fun authErrorMessage_weakPassword_mentionsMinimumLength() {
        assertEquals(
            "Use a stronger password (at least 8 characters).",
            authErrorMessage(mockk<FirebaseAuthWeakPasswordException>())
        )
    }

    @Test
    fun authErrorMessage_otherError_usesItsMessage() {
        assertEquals("boom", authErrorMessage(IllegalStateException("boom")))
    }

    @Test
    fun authErrorMessage_nullError_isNull() {
        assertNull(authErrorMessage(null))
    }
}
