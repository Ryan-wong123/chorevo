package com.example.chorevo.notification

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.core.viewmodel.AppViewModel
import com.example.chorevo.dancelibrarylist.repository.DanceLibraryRepository
import com.example.chorevo.firebase.AutoRetryUploaderService
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.firebase.repository.FirebaseAuthRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var authRepo: FirebaseAuthRepository
    private lateinit var settings: NotificationPreferencesDataStore
    private lateinit var scheduler: ReminderScheduler
    private lateinit var vm: AppViewModel

    private val pushFlow = MutableStateFlow(false)
    private val themeFlow = MutableStateFlow<String?>("dark")
    private val timeFlow = MutableStateFlow(9 to 0)

    private val profile = UserProfile(uid = "u1", email = "test@example.com", displayName = "Test User")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        authRepo = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)

        coEvery { authRepo.getCurrentUserProfile() } returns profile
        every { settings.pushEnabledFlow } returns pushFlow
        every { settings.onboardingSeenFlow } returns flowOf(true)
        every { settings.themeModeFlow } returns themeFlow
        every { settings.notificationTimeFlow } returns timeFlow
        coEvery { settings.setPushEnabled(any()) } answers { pushFlow.value = firstArg() }
        coEvery { settings.setThemeMode(any()) } answers { themeFlow.value = firstArg() }
        coEvery { settings.setNotificationTime(any(), any()) } answers {
            timeFlow.value = firstArg<Int>() to secondArg<Int>()
        }

        vm = AppViewModel(
            appContext = context,
            authRepository = authRepo,
            retryUploader = mockk<AutoRetryUploaderService>(relaxed = true),
            settings = settings,
            danceLibraryRepository = mockk<DanceLibraryRepository>(relaxed = true),
            reminderScheduler = scheduler,
            pushTokens = mockk<PushTokens>(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun waitForTextExists(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTextGone(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun setScreen(
        p: UserProfile = profile,
        onBack: () -> Unit = {},
        onLogout: () -> Unit = {}
    ) {
        composeRule.setContent {
            SettingsScreen(appViewModel = vm, profile = p, onBack = onBack, onLogout = onLogout)
        }
    }

    /**
     * The screen switches push off by itself on resume when the system has notifications
     * blocked; tests that need push on only make sense where they are allowed.
     */
    private fun requireSystemNotificationsEnabled() {
        assumeTrue(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    // ── Top bar ──────────────────────────────────────────────────────────────

    @Test
    fun topBar_showsTitleAndBackButton() {
        setScreen()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun clickBack_invokesOnBack() {
        var backCount = 0
        setScreen(onBack = { backCount++ })

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backCount)
    }

    // ── Profile header ───────────────────────────────────────────────────────

    @Test
    fun header_showsNameEmailAndInitialsAvatar() {
        setScreen()

        composeRule.onNodeWithText("Test User").assertIsDisplayed()
        composeRule.onNodeWithText("test@example.com").assertIsDisplayed()
        // No photo: the avatar shows the first two letters of the name.
        composeRule.onNodeWithText("TE").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Edit profile picture").assertIsDisplayed()
    }

    @Test
    fun header_blankNameAndEmail_fallBackToUserAndDash() {
        coEvery { authRepo.getCurrentUserProfile() } returns null
        vm = AppViewModel(
            appContext = context,
            authRepository = authRepo,
            retryUploader = mockk<AutoRetryUploaderService>(relaxed = true),
            settings = settings,
            danceLibraryRepository = mockk<DanceLibraryRepository>(relaxed = true),
            reminderScheduler = scheduler,
            pushTokens = mockk<PushTokens>(relaxed = true)
        )
        setScreen(p = UserProfile(uid = "u2", email = "", displayName = ""))

        composeRule.onNodeWithText("User").assertIsDisplayed()
        composeRule.onNodeWithText("-").assertIsDisplayed()
        composeRule.onNodeWithText("US").assertIsDisplayed()
    }

    @Test
    fun header_withPhotoUrl_showsProfilePicture() {
        coEvery { authRepo.getCurrentUserProfile() } returns null
        setScreen(p = profile.copy(photoUrl = "https://example.com/me.jpg"))

        composeRule.onNodeWithContentDescription("Profile picture").assertIsDisplayed()
    }

    @Test
    fun username_missing_showsChooseAUsernamePrompt() {
        setScreen()

        composeRule.onNodeWithTag("settingsUsername").assertIsDisplayed()
        composeRule.onNodeWithText("Choose a username").assertIsDisplayed()
    }

    @Test
    fun username_set_showsHandleWithChangeLink() {
        coEvery { authRepo.getCurrentUserProfile() } returns null
        setScreen(p = profile.copy(username = "alice"))

        composeRule.onNodeWithText("@alice · Change").assertIsDisplayed()
    }

    // ── Appearance ───────────────────────────────────────────────────────────

    @Test
    fun appearance_showsThreeThemeOptions() {
        setScreen()

        composeRule.onNodeWithTag("appearanceCard").assertIsDisplayed()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeRule.onNodeWithText("Dark").assertIsDisplayed()
        composeRule.onNodeWithText("Light").assertIsDisplayed()
        composeRule.onNodeWithText("System").assertIsDisplayed()
    }

    @Test
    fun clickLightTheme_savesThemeKey() {
        setScreen()

        composeRule.onNodeWithTag("themeMode_light").performClick()

        coVerify(timeout = 5_000) { settings.setThemeMode("light") }
    }

    @Test
    fun clickSystemTheme_savesThemeKey() {
        setScreen()

        composeRule.onNodeWithTag("themeMode_system").performClick()

        coVerify(timeout = 5_000) { settings.setThemeMode("system") }
    }

    // ── Notifications ────────────────────────────────────────────────────────

    @Test
    fun notificationCard_showsLabelsAndSwitch() {
        setScreen()

        composeRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeRule.onNodeWithText("Get notified about reminders and updates").assertIsDisplayed()
        composeRule.onNodeWithText("Push Notifications").assertIsDisplayed()
        composeRule.onNodeWithText("Reminder Time").assertIsDisplayed()
        composeRule.onNode(isToggleable()).assertIsDisplayed()
    }

    @Test
    fun pushDisabled_timeRowAsksToEnableNotifications_andDoesNotOpenPicker() {
        setScreen()

        composeRule.onNodeWithText("Enable notifications to choose a time").assertIsDisplayed()
        composeRule.onNodeWithText("Reminder Time").performClick()

        composeRule.onAllNodesWithText("OK").assertCountEquals(0)
    }

    @Test
    fun pushEnabled_timeRowShowsFormattedReminderTime() {
        requireSystemNotificationsEnabled()
        pushFlow.value = true
        timeFlow.value = 7 to 5
        setScreen()

        waitForTextExists("7:05 AM")
        composeRule.onNodeWithText("7:05 AM").assertIsDisplayed()
    }

    @Test
    fun pushEnabled_afternoonTimeIsShownInTwelveHourFormat() {
        requireSystemNotificationsEnabled()
        pushFlow.value = true
        timeFlow.value = 15 to 30
        setScreen()

        waitForTextExists("3:30 PM")
    }

    @Test
    fun turningSwitchOff_disablesPushAndHidesReminderTime() {
        requireSystemNotificationsEnabled()
        pushFlow.value = true
        setScreen()

        waitForTextExists("9:00 AM")
        composeRule.onNode(isToggleable()).performClick()

        coVerify(timeout = 5_000) { settings.setPushEnabled(false) }
        waitForTextExists("Enable notifications to choose a time")
        coVerify(timeout = 5_000) { scheduler.rescheduleFromPreferences() }
    }

    @Test
    fun pushEnabled_clickReminderTime_opensPicker_andCancelDismisses() {
        requireSystemNotificationsEnabled()
        pushFlow.value = true
        setScreen()

        waitForTextExists("9:00 AM")
        composeRule.onNodeWithText("Reminder Time").performClick()

        waitForTextExists("OK")
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        waitForTextGone("OK")
        coVerify(exactly = 0) { settings.setNotificationTime(any(), any()) }
    }

    @Test
    fun pushEnabled_confirmTimePicker_savesSelectedTime() {
        requireSystemNotificationsEnabled()
        pushFlow.value = true
        setScreen()

        waitForTextExists("9:00 AM")
        composeRule.onNodeWithText("Reminder Time").performClick()
        waitForTextExists("OK")
        composeRule.onNodeWithText("OK").performClick()

        // The picker starts at the stored time, so OK saves it unchanged.
        coVerify(timeout = 5_000) { settings.setNotificationTime(9, 0) }
        waitForTextGone("OK")
    }

    // ── Sign out ─────────────────────────────────────────────────────────────

    @Test
    fun clickSignOut_invokesOnLogout() {
        var loggedOut = 0
        setScreen(onLogout = { loggedOut++ })

        composeRule.onNodeWithText("Sign Out").performClick()

        assertEquals(1, loggedOut)
    }

    // ── Delete account ───────────────────────────────────────────────────────

    @Test
    fun deleteAccount_buttonOpensConfirmationDialog() {
        setScreen()

        composeRule.onNodeWithTag("deleteAccountButton").assertIsDisplayed()
        composeRule.onNodeWithText("Delete account").assertIsDisplayed()
        composeRule.onNodeWithTag("deleteAccountButton").performClick()

        composeRule.onNodeWithText("Delete your account?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete forever").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun deleteAccount_cancel_closesDialogWithoutDeleting() {
        setScreen()

        composeRule.onNodeWithTag("deleteAccountButton").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        waitForTextGone("Delete your account?")
        coVerify(exactly = 0) { authRepo.deleteAccount() }
    }

    @Test
    fun deleteAccount_confirm_callsRepositoryAndClosesDialogOnSuccess() {
        coEvery { authRepo.deleteAccount() } returns Result.success(Unit)
        setScreen()

        composeRule.onNodeWithTag("deleteAccountButton").performClick()
        composeRule.onNodeWithTag("confirmDeleteAccount").performClick()

        coVerify(timeout = 5_000, exactly = 1) { authRepo.deleteAccount() }
        waitForTextGone("Delete your account?")
    }

    @Test
    fun deleteAccount_failure_keepsDialogOpenAndShowsError() {
        coEvery { authRepo.deleteAccount() } returns Result.failure(IllegalStateException("Please sign in again"))
        setScreen()

        composeRule.onNodeWithTag("deleteAccountButton").performClick()
        composeRule.onNodeWithTag("confirmDeleteAccount").performClick()

        waitForTextExists("Please sign in again")
        composeRule.onNodeWithText("Please sign in again").assertIsDisplayed()
        composeRule.onNodeWithText("Delete your account?").assertIsDisplayed()
        // The button is usable again for a retry.
        composeRule.onNodeWithText("Delete forever").assertIsDisplayed()
    }
}
