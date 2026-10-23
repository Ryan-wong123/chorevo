package com.example.chorevo.core

import com.example.chorevo.core.viewmodel.AppUiState
import com.example.chorevo.core.viewmodel.Screen
import com.example.chorevo.firebase.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiStateTest {

    private fun makeProfile(
        uid: String = "uid1",
        displayName: String? = "Alice"
    ) = UserProfile(uid = uid, email = null, displayName = displayName)

    // ── displayName ───────────────────────────────────────────────────────────

    @Test
    fun displayName_withValidProfileName_returnsProfileDisplayName() {
        val state = AppUiState(profile = makeProfile(displayName = "Alice"))
        assertEquals("Alice", state.displayName)
    }

    @Test
    fun displayName_withNullProfile_returnsUser() {
        val state = AppUiState(profile = null)
        assertEquals("User", state.displayName)
    }

    @Test
    fun displayName_withNullDisplayName_returnsUser() {
        val state = AppUiState(profile = makeProfile(displayName = null))
        assertEquals("User", state.displayName)
    }

    @Test
    fun displayName_withBlankDisplayName_returnsUser() {
        val state = AppUiState(profile = makeProfile(displayName = "   "))
        assertEquals("User", state.displayName)
    }

    @Test
    fun displayName_withEmptyDisplayName_returnsUser() {
        val state = AppUiState(profile = makeProfile(displayName = ""))
        assertEquals("User", state.displayName)
    }

    @Test
    fun displayName_trailingAndLeadingSpaces_areTrimmed() {
        val state = AppUiState(profile = makeProfile(displayName = "  Bob  "))
        assertEquals("Bob", state.displayName)
    }

    // ── isSessionReady ────────────────────────────────────────────────────────

    @Test
    fun isSessionReady_trueWhen_authCheckDone_andProfileSet() {
        val state = AppUiState(didAuthCheck = true, profile = makeProfile())
        assertTrue(state.isSessionReady)
    }

    @Test
    fun isSessionReady_falseWhen_authCheckNotDone() {
        val state = AppUiState(didAuthCheck = false, profile = makeProfile())
        assertFalse(state.isSessionReady)
    }

    @Test
    fun isSessionReady_falseWhen_profileIsNull() {
        val state = AppUiState(didAuthCheck = true, profile = null)
        assertFalse(state.isSessionReady)
    }

    @Test
    fun isSessionReady_falseWhen_bothAuthCheckAndProfileMissing() {
        val state = AppUiState(didAuthCheck = false, profile = null)
        assertFalse(state.isSessionReady)
    }

    // ── default state ─────────────────────────────────────────────────────────

    @Test
    fun defaultState_hasAuthScreen_andNotReady() {
        val state = AppUiState()
        assertEquals(Screen.AUTH, state.screen)
        assertFalse(state.didAuthCheck)
        assertFalse(state.isSessionReady)
        assertEquals("User", state.displayName)
    }
}
