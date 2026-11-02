package com.example.chorevo.community

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.community.GroupsTestFixture.Companion.privateGroup
import com.example.chorevo.community.GroupsTestFixture.Companion.publicGroup
import com.example.chorevo.community.viewmodel.GroupsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fx: GroupsTestFixture
    private lateinit var vm: GroupsViewModel

    @Before
    fun setUp() {
        fx = GroupsTestFixture()
        vm = fx.newViewModel()
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

    private fun waitForTagExists(tag: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setScreen(
        pendingInviteCode: String? = null,
        onInviteHandled: () -> Unit = {},
        onOpenDance: (String) -> Unit = {}
    ) {
        composeRule.setContent {
            GroupsScreen(
                profile = fx.profile,
                vm = vm,
                onOpenDance = onOpenDance,
                pendingInviteCode = pendingInviteCode,
                onInviteHandled = onInviteHandled
            )
        }
    }

    // ── Chrome ───────────────────────────────────────────────────────────────

    @Test
    fun chrome_showsTitleTabsFabAndTopBarActions() {
        setScreen()

        composeRule.onNodeWithText("Groups").assertIsDisplayed()
        composeRule.onNodeWithTag("groupsTab_my").assertIsDisplayed()
        composeRule.onNodeWithTag("groupsTab_discover").assertIsDisplayed()
        composeRule.onNodeWithText("My groups").assertIsDisplayed()
        composeRule.onNodeWithText("Discover").assertIsDisplayed()
        composeRule.onNodeWithTag("createGroupButton").assertIsDisplayed()
        composeRule.onNodeWithText("New group", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Scan a group QR code").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Join with invite code").assertIsDisplayed()
    }

    @Test
    fun launch_bindsTheViewModelToTheProfile_andLoadsDiscover() {
        setScreen()

        coVerify(timeout = 5_000) { fx.repo.observeMyGroups("u1") }
        coVerify(timeout = 5_000) { fx.repo.observeInvitations("u1") }
        coVerify(timeout = 5_000) { fx.repo.loadPublicGroups("") }
    }

    // ── My groups ────────────────────────────────────────────────────────────

    @Test
    fun myGroups_whileLoading_showsShimmer() {
        every { fx.repo.observeMyGroups(any()) } returns emptyFlow()
        setScreen()

        waitForTagExists("shimmerList")
        composeRule.onNodeWithTag("shimmerList").assertIsDisplayed()
        composeRule.onAllNodesWithText("You're not in any groups yet").assertCountEquals(0)
    }

    @Test
    fun myGroups_empty_showsEmptyStateWithCreateAndDiscoverActions() {
        setScreen()

        waitForTextExists("You're not in any groups yet")
        composeRule.onNodeWithText("You're not in any groups yet").assertIsDisplayed()
        composeRule.onNodeWithText("Create one for your crew, or find a public group to join.").assertIsDisplayed()
        composeRule.onNodeWithText("Create a group").assertIsDisplayed()
        composeRule.onNodeWithText("Discover groups").assertIsDisplayed()
    }

    @Test
    fun myGroups_emptyState_discoverGroupsButtonSwitchesTab() {
        setScreen()

        waitForTextExists("Discover groups")
        composeRule.onNodeWithText("Discover groups").performClick()

        waitForTagExists("discoverSearch")
        composeRule.onNodeWithTag("discoverSearch").assertIsDisplayed()
    }

    @Test
    fun myGroups_emptyState_createAGroupButtonOpensDialog() {
        setScreen()

        waitForTextExists("Create a group")
        composeRule.onNodeWithText("Create a group").performClick()

        composeRule.onNodeWithTag("groupNameField").assertIsDisplayed()
    }

    @Test
    fun myGroups_list_showsNameDescriptionMemberCountAndJoinedMarker() {
        fx.myGroups.value = listOf(
            publicGroup(id = "g1", name = "Salsa Crew", description = "We dance salsa", memberCount = 3),
            publicGroup(id = "g2", name = "Solo Stars", description = "", memberCount = null)
        )
        setScreen()

        waitForTagExists("groupCard_g1")
        composeRule.onNodeWithText("Salsa Crew").assertIsDisplayed()
        composeRule.onNodeWithText("We dance salsa").assertIsDisplayed()
        composeRule.onNodeWithText("3 members · Joined").assertIsDisplayed()
        composeRule.onNodeWithText("Solo Stars").assertIsDisplayed()
        // Unknown member count (server hasn't run yet) counts the creator alone.
        composeRule.onNodeWithText("1 member · Joined").assertIsDisplayed()
    }

    @Test
    fun myGroups_privateGroup_showsLockIcon() {
        fx.myGroups.value = listOf(privateGroup())
        setScreen()

        waitForTagExists("groupCard_pg1")
        composeRule.onNodeWithContentDescription("Private").assertIsDisplayed()
    }

    @Test
    fun clickGroupCard_opensTheGroupDetail() {
        val g = publicGroup()
        fx.myGroups.value = listOf(g)
        fx.group.value = g
        fx.membership.value = GroupsTestFixture.member("u1", "Alice Tan")
        setScreen()

        waitForTagExists("groupCard_g1")
        composeRule.onNodeWithTag("groupCard_g1").performClick()

        waitForTagExists("groupPostList")
        composeRule.onNodeWithTag("groupBackButton").assertIsDisplayed()
        coVerify(timeout = 5_000) { fx.repo.observePosts("g1") }
    }

    // ── Invitations ──────────────────────────────────────────────────────────

    private val invitation = GroupInvitation(
        id = "g9_u1", groupId = "g9", groupName = "Hip Hop Heads",
        inviteeUid = "u1", inviteeUsername = "alice", inviterUid = "u2", inviterName = "Bob"
    )

    @Test
    fun invitations_areListedWithInviterAndTabBadge() {
        fx.invitations.value = listOf(invitation)
        setScreen()

        waitForTagExists("invitationsSection")
        composeRule.onNodeWithText("1 invitation").assertIsDisplayed()
        composeRule.onNodeWithText("Hip Hop Heads").assertIsDisplayed()
        composeRule.onNodeWithText("Bob invited you").assertIsDisplayed()
        composeRule.onNodeWithTag("acceptInvitation_g9").assertIsDisplayed()
        composeRule.onNodeWithTag("declineInvitation_g9").assertIsDisplayed()
        // The My groups tab carries a badge with the number of invitations.
        composeRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun invitations_multiple_useThePluralHeading() {
        fx.invitations.value = listOf(invitation, invitation.copy(id = "g8_u1", groupId = "g8", groupName = "Ballet Club"))
        setScreen()

        waitForTextExists("2 invitations")
        composeRule.onNodeWithText("2 invitations").assertIsDisplayed()
    }

    @Test
    fun noInvitations_hidesTheInvitationsSection() {
        setScreen()

        waitForTextExists("You're not in any groups yet")
        composeRule.onAllNodesWithTag("invitationsSection").assertCountEquals(0)
    }

    @Test
    fun clickAcceptInvitation_callsRepositoryWithInvitationAndAuthor() {
        fx.invitations.value = listOf(invitation)
        coEvery { fx.repo.acceptInvitation(any(), any()) } returns Result.success("g9")
        setScreen()

        waitForTagExists("acceptInvitation_g9")
        composeRule.onNodeWithTag("acceptInvitation_g9").performClick()

        coVerify(timeout = 5_000) { fx.repo.acceptInvitation(invitation, fx.author) }
    }

    @Test
    fun clickDeclineInvitation_callsRepositoryWithInvitation() {
        fx.invitations.value = listOf(invitation)
        coEvery { fx.repo.declineInvitation(any()) } returns Result.success(Unit)
        setScreen()

        waitForTagExists("declineInvitation_g9")
        composeRule.onNodeWithTag("declineInvitation_g9").performClick()

        coVerify(timeout = 5_000) { fx.repo.declineInvitation(invitation) }
    }

    // ── Discover ─────────────────────────────────────────────────────────────

    @Test
    fun discover_listsPublicGroups_andMarksOnesTheUserAlreadyJoined() {
        val mine = publicGroup(id = "g1", name = "Salsa Crew")
        fx.myGroups.value = listOf(mine)
        fx.discoverGroups = listOf(mine, publicGroup(id = "g2", name = "Tango Team", description = "Argentine", memberCount = 8))
        setScreen()

        waitForTagExists("groupsTab_discover")
        composeRule.onNodeWithTag("groupsTab_discover").performClick()

        waitForTagExists("groupCard_g2")
        composeRule.onNodeWithText("Tango Team").assertIsDisplayed()
        composeRule.onNodeWithText("8 members").assertIsDisplayed()
        composeRule.onNodeWithText("3 members · Joined").assertIsDisplayed()
    }

    @Test
    fun discover_noGroups_showsEmptyState() {
        setScreen()

        composeRule.onNodeWithTag("groupsTab_discover").performClick()

        waitForTextExists("No public groups yet")
        composeRule.onNodeWithText("Start one and invite your dance friends.").assertIsDisplayed()
    }

    @Test
    fun discover_search_queriesRepositoryAndShowsNoMatchState() {
        fx.discoverGroups = listOf(publicGroup(id = "g2", name = "Tango Team"))
        setScreen()

        composeRule.onNodeWithTag("groupsTab_discover").performClick()
        waitForTagExists("groupCard_g2")
        composeRule.onNodeWithTag("discoverSearch").performTextInput("zzz")

        coVerify(timeout = 5_000) { fx.repo.loadPublicGroups("zzz") }
        waitForTextExists("No groups match")
        composeRule.onNodeWithText("No groups match").assertIsDisplayed()
    }

    @Test
    fun discover_clickGroup_opensItsDetail() {
        val g = publicGroup(id = "g2", name = "Tango Team")
        fx.discoverGroups = listOf(g)
        fx.group.value = g
        setScreen()

        composeRule.onNodeWithTag("groupsTab_discover").performClick()
        waitForTagExists("groupCard_g2")
        composeRule.onNodeWithTag("groupCard_g2").performClick()

        waitForTagExists("groupBackButton")
        // A non-member of a public group is offered to join.
        waitForTagExists("joinGroupButton")
    }

    // ── Create group ─────────────────────────────────────────────────────────

    @Test
    fun createDialog_createButtonNeedsAValidName() {
        setScreen()

        composeRule.onNodeWithTag("createGroupButton").performClick()

        composeRule.onNodeWithTag("groupNameField").assertIsDisplayed()
        composeRule.onNodeWithTag("groupDescriptionField").assertIsDisplayed()
        composeRule.onNodeWithText("3–60 characters").assertIsDisplayed()
        composeRule.onNodeWithTag("confirmCreateGroup").assertIsNotEnabled()

        composeRule.onNodeWithTag("groupNameField").performTextInput("ab")
        composeRule.onNodeWithTag("confirmCreateGroup").assertIsNotEnabled()

        composeRule.onNodeWithTag("groupNameField").performTextInput("c")
        composeRule.onNodeWithTag("confirmCreateGroup").assertIsEnabled()
    }

    @Test
    fun createDialog_publicGroup_callsRepositoryWithTypedValues() {
        val created = publicGroup(id = "new1", name = "Salsa Crew")
        coEvery { fx.repo.createGroup(any(), any(), any(), any(), any(), any()) } returns Result.success(created)
        fx.group.value = created
        setScreen()

        composeRule.onNodeWithTag("createGroupButton").performClick()
        composeRule.onNodeWithTag("groupNameField").performTextInput("Salsa Crew")
        composeRule.onNodeWithTag("groupDescriptionField").performTextInput("We dance salsa")
        composeRule.onNodeWithText("Anyone can find and join it from Discover.").assertIsDisplayed()
        composeRule.onNodeWithTag("confirmCreateGroup").performClick()

        coVerify(timeout = 5_000) {
            fx.repo.createGroup("u1", "Alice Tan", null, "Salsa Crew", "We dance salsa", false)
        }
        // The new group opens right away.
        waitForTagExists("groupBackButton")
    }

    @Test
    fun createDialog_privateChip_createsPrivateGroupAndExplainsInviteCodes() {
        val created = privateGroup(id = "new2", name = "Secret Studio")
        coEvery { fx.repo.createGroup(any(), any(), any(), any(), any(), any()) } returns Result.success(created)
        fx.group.value = created
        setScreen()

        composeRule.onNodeWithTag("createGroupButton").performClick()
        composeRule.onNodeWithTag("groupNameField").performTextInput("Secret Studio")
        composeRule.onNodeWithTag("groupPrivateChip").performClick()
        composeRule.onNodeWithText("Hidden from Discover. People join with an invite code you share.").assertIsDisplayed()
        composeRule.onNodeWithTag("confirmCreateGroup").performClick()

        coVerify(timeout = 5_000) {
            fx.repo.createGroup("u1", "Alice Tan", null, "Secret Studio", "", true)
        }
    }

    @Test
    fun createDialog_cancel_closesWithoutCreating() {
        setScreen()

        composeRule.onNodeWithTag("createGroupButton").performClick()
        composeRule.onNodeWithTag("groupNameField").performTextInput("Salsa Crew")
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("groupNameField").fetchSemanticsNodes().isEmpty()
        }
        coVerify(exactly = 0) { fx.repo.createGroup(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun createGroup_failure_showsSnackbarMessage() {
        coEvery { fx.repo.createGroup(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("boom"))
        setScreen()

        composeRule.onNodeWithTag("createGroupButton").performClick()
        composeRule.onNodeWithTag("groupNameField").performTextInput("Salsa Crew")
        composeRule.onNodeWithTag("confirmCreateGroup").performClick()

        waitForTextExists("Couldn't create the group: boom")
        composeRule.onNodeWithText("Couldn't create the group: boom").assertIsDisplayed()
    }

    // ── Join with code ───────────────────────────────────────────────────────

    @Test
    fun joinDialog_opensFromTopBar_andJoinNeedsAFullCode() {
        setScreen()

        composeRule.onNodeWithTag("joinCodeButton").performClick()

        composeRule.onNodeWithText("Join with invite code").assertIsDisplayed()
        composeRule.onNodeWithText("Scan QR code instead").assertIsDisplayed()
        composeRule.onNodeWithTag("confirmJoinCode").assertIsNotEnabled()

        composeRule.onNodeWithTag("inviteCodeField").performTextInput("ABC")
        composeRule.onNodeWithTag("confirmJoinCode").assertIsNotEnabled()
    }

    @Test
    fun joinDialog_normalisesTypedCode_andJoinsWithIt() {
        coEvery { fx.repo.joinWithCode(any(), any(), any(), any()) } returns Result.success("pg1")
        val g = privateGroup()
        fx.group.value = g
        setScreen()

        composeRule.onNodeWithTag("joinCodeButton").performClick()
        // Lower case becomes upper case; characters outside the alphabet (0, O, 1, I, L) are dropped.
        composeRule.onNodeWithTag("inviteCodeField").performTextInput("k7qw-2mzp")
        composeRule.onNodeWithTag("inviteCodeField").assertTextContains("K7QW2MZP")
        composeRule.onNodeWithTag("confirmJoinCode").assertIsEnabled()
        composeRule.onNodeWithTag("confirmJoinCode").performClick()

        coVerify(timeout = 5_000) { fx.repo.joinWithCode("K7QW2MZP", "u1", "Alice Tan", null) }
    }

    @Test
    fun joinDialog_wrongCode_showsRepositoryErrorInSnackbar() {
        coEvery { fx.repo.joinWithCode(any(), any(), any(), any()) } returns
            Result.failure(IllegalArgumentException("No group has that invite code."))
        setScreen()

        composeRule.onNodeWithTag("joinCodeButton").performClick()
        composeRule.onNodeWithTag("inviteCodeField").performTextInput("ABCD2345")
        composeRule.onNodeWithTag("confirmJoinCode").performClick()

        waitForTextExists("No group has that invite code.")
        composeRule.onNodeWithText("No group has that invite code.").assertIsDisplayed()
    }

    @Test
    fun pendingInviteCode_opensJoinDialogPrefilled_andReportsHandled() {
        var handled = 0
        setScreen(pendingInviteCode = "abcd2345", onInviteHandled = { handled++ })

        waitForTagExists("inviteCodeField")
        composeRule.onNodeWithTag("inviteCodeField").assertTextContains("ABCD2345")
        composeRule.onNodeWithTag("confirmJoinCode").assertIsEnabled()
        composeRule.waitUntil(5_000) { handled == 1 }
        assertEquals(1, handled)
    }
}
