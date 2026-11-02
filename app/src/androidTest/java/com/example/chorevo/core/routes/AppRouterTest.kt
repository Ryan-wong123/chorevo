package com.example.chorevo.core.routes

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.core.FloatingBarBottomMargin
import com.example.chorevo.core.FloatingBarHeight
import com.example.chorevo.core.LocalFloatingBarInset
import com.example.chorevo.core.LocalFloatingBarState
import com.example.chorevo.core.viewmodel.AppViewModel
import com.example.chorevo.core.viewmodel.Screen
import com.example.chorevo.dancelibrarylist.repository.DanceLibraryRepository
import com.example.chorevo.firebase.AutoRetryUploaderService
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.firebase.repository.FirebaseAuthRepository
import com.example.chorevo.notification.NotificationPreferencesDataStore
import com.example.chorevo.notification.PushTokens
import com.example.chorevo.notification.ReminderScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Navigation tests for the app's routing layer.
 *
 * AppRouter shows the signed-in screens through `hiltViewModel()`, which needs a Hilt
 * activity, so the signed-in tree is covered through its building blocks instead:
 * [AppNavigationRoute] (bottom bar and padding), [AppViewModel] (which screen is current)
 * and AppRouter itself for the signed-out states that need no Hilt view models.
 */
@RunWith(AndroidJUnit4::class)
class AppRouterTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var authRepo: FirebaseAuthRepository
    private lateinit var settings: NotificationPreferencesDataStore

    private val profile = UserProfile(uid = "u1", email = "test@example.com", displayName = "Test User", username = "tester")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        authRepo = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        coEvery { authRepo.getCurrentUserProfile() } returns null
        every { settings.pushEnabledFlow } returns flowOf(false)
        every { settings.onboardingSeenFlow } returns flowOf(true)
        every { settings.themeModeFlow } returns flowOf("dark")
        every { settings.notificationTimeFlow } returns flowOf(9 to 0)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun newViewModel() = AppViewModel(
        appContext = context,
        authRepository = authRepo,
        retryUploader = mockk<AutoRetryUploaderService>(relaxed = true),
        settings = settings,
        danceLibraryRepository = mockk<DanceLibraryRepository>(relaxed = true),
        reminderScheduler = mockk<ReminderScheduler>(relaxed = true),
        pushTokens = mockk<PushTokens>(relaxed = true)
    )

    /** A view model whose start-up auth check has finished. */
    private fun readyViewModel(): AppViewModel {
        val vm = newViewModel()
        val deadline = System.currentTimeMillis() + 10_000
        while (!vm.uiState.value.didAuthCheck && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue("auth check should finish", vm.uiState.value.didAuthCheck)
        return vm
    }

    private fun waitForTagExists(tag: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTagGone(tag: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForTextExists(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ── AppNavigationRoute: bottom bar ───────────────────────────────────────

    /** Hosts the navigation scaffold the way AppRouter does: tabs switch the current screen. */
    private fun setNavigation(
        start: Screen = Screen.HOME,
        groupsBadge: Int = 0,
        content: @androidx.compose.runtime.Composable (Screen, androidx.compose.foundation.layout.PaddingValues) -> Unit =
            { screen, _ -> Text("current:${screen.name}") }
    ) {
        composeRule.setContent {
            var screen by remember { mutableStateOf(start) }
            AppNavigationRoute(
                screen = screen,
                onHomeSelected = { screen = Screen.HOME },
                onUploadSelected = { screen = Screen.UPLOAD },
                onCommunitySelected = { screen = Screen.COMMUNITY },
                onLeaderboardSelected = { screen = Screen.LEADERBOARD },
                onProfileSelected = { screen = Screen.PROFILE },
                groupsBadge = groupsBadge
            ) { padding -> content(screen, padding) }
        }
    }

    @Test
    fun bottomBar_showsFiveDestinations() {
        setNavigation()

        composeRule.onNodeWithTag("floatingBottomBar").assertIsDisplayed()
        listOf("HOME", "LEADERBOARD", "UPLOAD", "COMMUNITY", "PROFILE").forEach {
            composeRule.onNodeWithTag("bottomNav_$it").assertIsDisplayed()
        }
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Score").assertIsDisplayed()
        composeRule.onNodeWithText("Groups").assertIsDisplayed()
        composeRule.onNodeWithText("Profile").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Upload").assertIsDisplayed()
    }

    @Test
    fun clickingEachTab_switchesTheCurrentScreen() {
        setNavigation(start = Screen.HOME)
        composeRule.onNodeWithText("current:HOME").assertIsDisplayed()

        composeRule.onNodeWithTag("bottomNav_UPLOAD").performClick()
        composeRule.onNodeWithText("current:UPLOAD").assertIsDisplayed()

        composeRule.onNodeWithTag("bottomNav_COMMUNITY").performClick()
        composeRule.onNodeWithText("current:COMMUNITY").assertIsDisplayed()

        composeRule.onNodeWithTag("bottomNav_LEADERBOARD").performClick()
        composeRule.onNodeWithText("current:LEADERBOARD").assertIsDisplayed()

        composeRule.onNodeWithTag("bottomNav_PROFILE").performClick()
        composeRule.onNodeWithText("current:PROFILE").assertIsDisplayed()

        composeRule.onNodeWithTag("bottomNav_HOME").performClick()
        composeRule.onNodeWithText("current:HOME").assertIsDisplayed()
    }

    @Test
    fun clickingATab_callsOnlyItsOwnCallback() {
        val calls = mutableListOf<String>()
        composeRule.setContent {
            AppNavigationRoute(
                screen = Screen.HOME,
                onHomeSelected = { calls += "home" },
                onUploadSelected = { calls += "upload" },
                onCommunitySelected = { calls += "community" },
                onLeaderboardSelected = { calls += "leaderboard" },
                onProfileSelected = { calls += "profile" }
            ) { Text("content") }
        }

        composeRule.onNodeWithTag("bottomNav_LEADERBOARD").performClick()
        composeRule.onNodeWithTag("bottomNav_PROFILE").performClick()
        composeRule.onNodeWithTag("bottomNav_COMMUNITY").performClick()
        composeRule.onNodeWithTag("bottomNav_UPLOAD").performClick()
        composeRule.onNodeWithTag("bottomNav_HOME").performClick()

        assertEquals(listOf("leaderboard", "profile", "community", "upload", "home"), calls)
    }

    @Test
    fun bottomBar_isHiddenOnAuthAndPracticeScreens() {
        setNavigation(start = Screen.AUTH)
        composeRule.onAllNodesWithTag("floatingBottomBar").assertCountEquals(0)
    }

    @Test
    fun bottomBar_isHiddenWhilePractising() {
        setNavigation(start = Screen.DANCE_PRACTICE)
        composeRule.onAllNodesWithTag("floatingBottomBar").assertCountEquals(0)
    }

    @Test
    fun bottomBar_staysVisibleOnProfileSubScreens() {
        setNavigation(start = Screen.SETTINGS)
        composeRule.onNodeWithTag("floatingBottomBar").assertIsDisplayed()
    }

    @Test
    fun bottomBar_staysVisibleOnWeeklyChallenges() {
        setNavigation(start = Screen.WEEKLY_CHALLENGES)
        composeRule.onNodeWithTag("floatingBottomBar").assertIsDisplayed()
    }

    @Test
    fun feedCanTuckTheBarAway() {
        setNavigation(start = Screen.HOME) { _, _ ->
            val barState = LocalFloatingBarState.current
            Text("hide bar", modifier = Modifier.clickable { barState.hidden = true })
        }
        composeRule.onNodeWithTag("floatingBottomBar").assertIsDisplayed()

        composeRule.onNodeWithText("hide bar").performClick()

        waitForTagGone("floatingBottomBar")
    }

    // ── AppNavigationRoute: groups badge ─────────────────────────────────────

    @Test
    fun groupsBadge_showsInvitationCount_andAnnouncesIt() {
        setNavigation(groupsBadge = 3)

        composeRule.onNodeWithTag("bottomNavBadge_COMMUNITY").assertExists()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Groups, 3 new").assertIsDisplayed()
    }

    @Test
    fun groupsBadge_capsAtNinePlus() {
        setNavigation(groupsBadge = 12)

        composeRule.onNodeWithText("9+").assertIsDisplayed()
    }

    @Test
    fun groupsBadge_zero_showsNoBadge() {
        setNavigation(groupsBadge = 0)

        composeRule.onAllNodesWithTag("bottomNavBadge_COMMUNITY").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Groups").assertIsDisplayed()
    }

    // ── AppNavigationRoute: content padding ──────────────────────────────────

    @Test
    fun contentPadding_reservesRoomForTheBar_exceptOnFullBleedHome() {
        var homeBottom = Dp.Unspecified
        var homeInset = Dp.Unspecified
        var uploadBottom = Dp.Unspecified
        var authBottom = Dp.Unspecified
        var screen by mutableStateOf(Screen.HOME)
        composeRule.setContent {
            AppNavigationRoute(
                screen = screen,
                onHomeSelected = {}, onUploadSelected = {}, onCommunitySelected = {},
                onLeaderboardSelected = {}, onProfileSelected = {}
            ) { padding ->
                val inset = LocalFloatingBarInset.current
                when (screen) {
                    Screen.HOME -> { homeBottom = padding.calculateBottomPadding(); homeInset = inset }
                    Screen.UPLOAD -> uploadBottom = padding.calculateBottomPadding()
                    Screen.AUTH -> authBottom = padding.calculateBottomPadding()
                    else -> Unit
                }
                Text("current:${screen.name}")
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { screen = Screen.UPLOAD }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { screen = Screen.AUTH }
        composeRule.waitForIdle()

        val barReserve = FloatingBarHeight.value + FloatingBarBottomMargin.value + 8f
        // The feed is drawn behind the bar, so it gets no padding but is told the inset instead.
        assertEquals(0f, homeBottom.value, 0.5f)
        assertTrue("home inset should cover the bar", homeInset.value >= barReserve - 0.5f)
        // Other screens are padded by exactly the bar's footprint compared with a screen without a bar.
        assertEquals(barReserve, uploadBottom.value - authBottom.value, 0.5f)
        assertTrue(0.dp.value <= authBottom.value)
    }

    // ── Screen back targets (predictive back) ────────────────────────────────

    @Test
    fun screenBackTargets_matchTheNavigationHierarchy() {
        listOf(Screen.DANCE_PRACTICE, Screen.UPLOAD, Screen.COMMUNITY, Screen.LEADERBOARD, Screen.PROFILE)
            .forEach { assertEquals("$it", Screen.HOME, it.backTarget) }
        listOf(Screen.SETTINGS, Screen.WEEKLY_CHALLENGES)
            .forEach { assertEquals("$it", Screen.PROFILE, it.backTarget) }
        assertNull(Screen.HOME.backTarget)
        assertNull(Screen.AUTH.backTarget)
    }

    // ── AppViewModel: which screen is current ────────────────────────────────

    @Test
    fun signedOutStartup_goesToAuthScreen() {
        val vm = readyViewModel()

        assertEquals(Screen.AUTH, vm.uiState.value.screen)
        assertNull(vm.uiState.value.profile)
        assertFalse(vm.uiState.value.isSessionReady)
    }

    @Test
    fun existingSession_startsOnHome() {
        coEvery { authRepo.getCurrentUserProfile() } returns profile
        val vm = readyViewModel()

        assertEquals(Screen.HOME, vm.uiState.value.screen)
        assertEquals("u1", vm.uiState.value.profile?.uid)
        assertTrue(vm.uiState.value.isSessionReady)
    }

    @Test
    fun onAuthed_storesProfileAndOpensHome() {
        val vm = readyViewModel()

        vm.onAuthed(profile)

        assertEquals(Screen.HOME, vm.uiState.value.screen)
        assertEquals(profile, vm.uiState.value.profile)
    }

    @Test
    fun openFunctions_switchToTheirScreens() {
        coEvery { authRepo.getCurrentUserProfile() } returns profile
        val vm = readyViewModel()

        vm.openUpload(); assertEquals(Screen.UPLOAD, vm.uiState.value.screen)
        vm.openCommunity(); assertEquals(Screen.COMMUNITY, vm.uiState.value.screen)
        vm.openLeaderboard(); assertEquals(Screen.LEADERBOARD, vm.uiState.value.screen)
        vm.openProfile(); assertEquals(Screen.PROFILE, vm.uiState.value.screen)
        vm.openSettings(); assertEquals(Screen.SETTINGS, vm.uiState.value.screen)
        vm.openWeeklyChallenges(); assertEquals(Screen.WEEKLY_CHALLENGES, vm.uiState.value.screen)
        vm.backToHome(); assertEquals(Screen.HOME, vm.uiState.value.screen)
    }

    @Test
    fun navigateBack_walksUpTheHierarchy_andLetsTheSystemCloseTheAppAtTheRoot() {
        coEvery { authRepo.getCurrentUserProfile() } returns profile
        val vm = readyViewModel()

        vm.openSettings()
        assertTrue(vm.navigateBack())
        assertEquals(Screen.PROFILE, vm.uiState.value.screen)
        assertTrue(vm.navigateBack())
        assertEquals(Screen.HOME, vm.uiState.value.screen)
        // Home is a root: the system handles back.
        assertFalse(vm.navigateBack())
        assertEquals(Screen.HOME, vm.uiState.value.screen)
    }

    @Test
    fun navigateBack_fromWeeklyChallenges_returnsToProfile() {
        coEvery { authRepo.getCurrentUserProfile() } returns profile
        val vm = readyViewModel()

        vm.openWeeklyChallenges()

        assertTrue(vm.navigateBack())
        assertEquals(Screen.PROFILE, vm.uiState.value.screen)
    }

    @Test
    fun logout_returnsToAuth_andSignsOut() {
        coEvery { authRepo.getCurrentUserProfile() } returns profile
        val vm = readyViewModel()
        vm.openProfile()

        vm.logout()

        assertEquals(Screen.AUTH, vm.uiState.value.screen)
        assertNull(vm.uiState.value.profile)
        io.mockk.verify { authRepo.signOut() }
    }

    @Test
    fun groupInviteLink_whileSignedIn_opensCommunityWithTheCode() {
        coEvery { authRepo.getCurrentUserProfile() } returns profile
        val vm = readyViewModel()

        vm.openGroupInvite("K7QW2MZP")

        assertEquals(Screen.COMMUNITY, vm.uiState.value.screen)
        assertEquals("K7QW2MZP", vm.uiState.value.pendingGroupInvite)
        vm.clearGroupInvite()
        assertNull(vm.uiState.value.pendingGroupInvite)
    }

    @Test
    fun groupInviteLink_beforeSignIn_isKeptUntilAuthed() {
        val vm = readyViewModel()

        vm.openGroupInvite("K7QW2MZP")
        assertEquals(Screen.AUTH, vm.uiState.value.screen)
        assertNull(vm.uiState.value.pendingGroupInvite)

        vm.onAuthed(profile)

        assertEquals(Screen.COMMUNITY, vm.uiState.value.screen)
        assertEquals("K7QW2MZP", vm.uiState.value.pendingGroupInvite)
    }

    @Test
    fun groupInvitationPush_whileSignedIn_opensCommunity() {
        coEvery { authRepo.getCurrentUserProfile() } returns profile
        val vm = readyViewModel()

        vm.openGroupInvitations()

        assertEquals(Screen.COMMUNITY, vm.uiState.value.screen)
    }

    // ── AppRouter: signed-out states (no Hilt view models needed) ────────────

    @Test
    fun appRouter_whileAuthCheckIsPending_showsSpinnerOnly() {
        val gate = CompletableDeferred<UserProfile?>()
        coEvery { authRepo.getCurrentUserProfile() } coAnswers { gate.await() }
        val vm = newViewModel()

        composeRule.setContent { AppRouter(appViewModel = vm) }

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("onboarding").assertCountEquals(0)
        composeRule.onAllNodesWithTag("floatingBottomBar").assertCountEquals(0)
        gate.cancel()
    }

    @Test
    fun appRouter_signedOutBeforeOnboardingPreferenceLoads_showsSpinner() {
        every { settings.onboardingSeenFlow } returns emptyFlow()
        val vm = readyViewModel()

        composeRule.setContent { AppRouter(appViewModel = vm) }

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("onboarding").assertCountEquals(0)
    }

    @Test
    fun appRouter_firstLaunchSignedOut_showsOnboardingWithoutBottomBar() {
        every { settings.onboardingSeenFlow } returns flowOf(false)
        val vm = readyViewModel()

        composeRule.setContent { AppRouter(appViewModel = vm) }

        waitForTagExists("onboarding")
        composeRule.onNodeWithTag("onboarding").assertIsDisplayed()
        waitForTextExists("Learn any routine")
        composeRule.onAllNodesWithTag("floatingBottomBar").assertCountEquals(0)
        coVerify(exactly = 0) { settings.setOnboardingSeen() }
    }
}
