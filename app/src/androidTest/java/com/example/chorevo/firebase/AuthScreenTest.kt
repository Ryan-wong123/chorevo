package com.example.chorevo.firebase

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.chorevo.firebase.Repository.FirebaseAuthRepository
import com.example.chorevo.firebase.ViewModel.AuthViewModel
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AuthScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repo: FirebaseAuthRepository
    private lateinit var vm: AuthViewModel

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        vm = AuthViewModel(repo)
    }

    private fun loginButton() =
        composeRule.onAllNodesWithText("Login")
            .filterToOne(hasClickAction())

    @Test
    fun loginMode_showsLoginUi() {
        composeRule.setContent {
            AuthScreen(
                onAuthed = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Welcome").assertIsDisplayed()
        composeRule.onNodeWithText("Username / Email").assertIsDisplayed()
        composeRule.onNodeWithText("Enter your password").assertIsDisplayed()
        composeRule.onNodeWithText("Sign up here").assertIsDisplayed()

        composeRule.onAllNodesWithText("Login").assertCountEquals(2)
        loginButton().assertIsDisplayed()
    }

    @Test
    fun toggleMode_showsSignupUi() {
        composeRule.setContent {
            AuthScreen(
                onAuthed = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Sign up here").performClick()

        composeRule.onNodeWithText("Display Name").assertIsDisplayed()
        composeRule.onNodeWithText("Enter your Email").assertIsDisplayed()
        composeRule.onNodeWithText("Create Account").assertIsDisplayed()
        composeRule.onNodeWithText("Login here").assertIsDisplayed()
    }

    @Test
    fun signupWithoutDisplayName_showsValidationError() {
        composeRule.setContent {
            AuthScreen(
                onAuthed = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Sign up here").performClick()
        composeRule.onNodeWithText("Enter your Email").performTextInput("new@example.com")
        composeRule.onNodeWithText("Enter your password").performTextInput("123456")
        composeRule.onNodeWithText("Create Account").performClick()

        composeRule.onNodeWithText("Please enter a display name.").assertIsDisplayed()
    }

    @Test
    fun loginSuccess_callsOnAuthed() {
        val profile = UserProfile(
            uid = "u1",
            email = "test@example.com",
            displayName = "Test User"
        )

        coEvery {
            repo.signIn("test@example.com", "123456")
        } returns Result.success(profile)

        var authedProfile: UserProfile? = null

        composeRule.setContent {
            AuthScreen(
                onAuthed = { authedProfile = it },
                vm = vm
            )
        }

        composeRule.onNodeWithText("Username / Email").performTextInput("test@example.com")
        composeRule.onNodeWithText("Enter your password").performTextInput("123456")
        loginButton().performClick()

        composeRule.waitUntil(5_000) {
            authedProfile != null
        }

        assertEquals("u1", authedProfile?.uid)
    }

    @Test
    fun loginFailure_showsErrorMessage() {
        coEvery {
            repo.signIn("wrong@example.com", "wrongpass")
        } returns Result.failure(Exception("Invalid credentials"))

        composeRule.setContent {
            AuthScreen(
                onAuthed = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Username / Email").performTextInput("wrong@example.com")
        composeRule.onNodeWithText("Enter your password").performTextInput("wrongpass")
        loginButton().performClick()

        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithText("Invalid credentials").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }
}