package com.example.chorevo.community

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chorevo.community.GroupsTestFixture.Companion.member
import com.example.chorevo.community.GroupsTestFixture.Companion.privateGroup
import com.example.chorevo.community.GroupsTestFixture.Companion.publicGroup
import com.example.chorevo.community.viewmodel.GroupsViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.repository.UserSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GroupDetailScreen is shown by GroupsScreen once a group is open, so these tests open
 * the group through the real [GroupsViewModel] and render GroupsScreen.
 */
@RunWith(AndroidJUnit4::class)
class GroupDetailScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fx: GroupsTestFixture
    private lateinit var vm: GroupsViewModel

    private val me = member("u1", "Alice Tan")
    private val owner = member("owner1", "Olivia", GroupMember.ROLE_OWNER)

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

    private fun waitForTextGone(text: String, timeoutMs: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
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

    /** Opens [group] as [membership] (null = not a member) and shows the screen. */
    private fun openGroup(
        group: DanceGroup = publicGroup(),
        membership: GroupMember? = me,
        members: List<GroupMember> = listOfNotNull(owner, membership),
        posts: List<GroupPost> = emptyList(),
        onOpenDance: (String) -> Unit = {}
    ) {
        fx.group.value = group
        fx.membership.value = membership
        fx.members.value = members
        fx.posts.value = posts
        vm.bind(fx.profile.uid, "Alice Tan", null)
        vm.openGroup(group.id)
        composeRule.setContent {
            GroupsScreen(profile = fx.profile, vm = vm, onOpenDance = onOpenDance)
        }
        waitForTagExists("groupPostList")
    }

    private fun textPost(id: String = "p1", uid: String = "u2", name: String = "Bob", body: String = "Anyone up for a jam?") =
        GroupPost(id = id, groupId = "g1", uid = uid, displayName = name, body = body, kind = GroupPostKind.TEXT)

    private fun openMenu() {
        composeRule.onNodeWithTag("groupMenuButton").performClick()
    }

    // ── Header ───────────────────────────────────────────────────────────────

    @Test
    fun header_showsNameVisibilityMemberCountAndDescription() {
        openGroup(group = publicGroup(name = "Salsa Crew", description = "We dance salsa", memberCount = 3))

        waitForTextExists("Public · 3 members")
        composeRule.onNodeWithText("Public · 3 members").assertIsDisplayed()
        composeRule.onNodeWithText("We dance salsa").assertIsDisplayed()
        // Title in the top bar and in the header card.
        assertTrue(composeRule.onAllNodesWithText("Salsa Crew").fetchSemanticsNodes().size >= 2)
    }

    @Test
    fun header_privateGroupMember_showsInviteCodeAndQrButton() {
        openGroup(group = privateGroup(inviteCode = "K7QW2MZP"), membership = me)

        waitForTagExists("groupInviteCode")
        composeRule.onNodeWithText("Private · 2 members").assertIsDisplayed()
        composeRule.onNodeWithText("Invite code").assertIsDisplayed()
        composeRule.onNodeWithTag("groupInviteCode").assertIsDisplayed()
        composeRule.onNodeWithText("K7QW2MZP").assertIsDisplayed()
        composeRule.onNodeWithTag("showQrButton").assertIsDisplayed()
    }

    @Test
    fun header_publicGroup_hasNoInviteCodeCard() {
        openGroup(group = publicGroup(), membership = me)

        composeRule.onAllNodesWithTag("groupInviteCode").assertCountEquals(0)
    }

    @Test
    fun showQrButton_opensInviteQrDialog() {
        openGroup(group = privateGroup(name = "Secret Studio", inviteCode = "K7QW2MZP"), membership = me)

        waitForTagExists("showQrButton")
        composeRule.onNodeWithTag("showQrButton").performClick()

        waitForTextExists("Join Secret Studio")
        composeRule.onNodeWithText("Join Secret Studio").assertIsDisplayed()
        composeRule.onNodeWithTag("inviteQrImage").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        waitForTextGone("Join Secret Studio")
    }

    @Test
    fun clickBack_closesTheGroupAndReturnsToTheList() {
        openGroup()

        composeRule.onNodeWithTag("groupBackButton").performClick()

        waitForTagGone("groupPostList")
        waitForTextExists("Groups")
    }

    // ── Membership ───────────────────────────────────────────────────────────

    @Test
    fun nonMemberOfPublicGroup_seesJoinBanner_noComposer_andJoinCallsRepository() {
        coEvery { fx.repo.joinPublicGroup(any(), any(), any(), any()) } returns Result.success(Unit)
        openGroup(membership = null, members = listOf(owner))

        waitForTagExists("joinGroupButton")
        composeRule.onNodeWithText("Join to post your dances and reply to others.").assertIsDisplayed()
        composeRule.onAllNodesWithTag("groupComposerField").assertCountEquals(0)
        composeRule.onNodeWithText("Join the group to post and reply.").assertIsDisplayed()

        composeRule.onNodeWithTag("joinGroupButton").performClick()

        coVerify(timeout = 5_000) { fx.repo.joinPublicGroup("g1", "u1", "Alice Tan", null) }
    }

    @Test
    fun nonMemberOfPrivateGroup_hasNoJoinBanner() {
        openGroup(group = privateGroup(), membership = null, members = listOf(owner))

        composeRule.onAllNodesWithTag("joinGroupButton").assertCountEquals(0)
        composeRule.onAllNodesWithTag("groupComposerField").assertCountEquals(0)
    }

    @Test
    fun member_seesComposer_andNoJoinBanner() {
        openGroup(membership = me)

        waitForTagExists("groupComposerField")
        composeRule.onNodeWithTag("groupComposerField").assertIsDisplayed()
        composeRule.onNodeWithText("Write something…").assertIsDisplayed()
        composeRule.onAllNodesWithTag("joinGroupButton").assertCountEquals(0)
    }

    // ── Posts ────────────────────────────────────────────────────────────────

    @Test
    fun posts_whileLoading_showShimmer() {
        every { fx.repo.observePosts(any()) } returns emptyFlow()
        openGroup()

        waitForTagExists("shimmerList")
        composeRule.onNodeWithTag("shimmerList").assertIsDisplayed()
        composeRule.onAllNodesWithText("No posts yet").assertCountEquals(0)
    }

    @Test
    fun posts_emptyForMember_promptsToStartTheConversation() {
        openGroup(membership = me, posts = emptyList())

        waitForTextExists("No posts yet")
        composeRule.onNodeWithText("Start the conversation: share a dance, a video or a question.").assertIsDisplayed()
    }

    @Test
    fun posts_textPost_showsAuthorBodyAndRepliesButton() {
        openGroup(posts = listOf(textPost()))

        waitForTagExists("groupPost_p1")
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
        composeRule.onNodeWithText("Anyone up for a jam?").assertIsDisplayed()
        composeRule.onNodeWithTag("replies_p1").assertIsDisplayed()
        composeRule.onAllNodesWithText("No posts yet").assertCountEquals(0)
    }

    @Test
    fun posts_dancePost_showsTitleAndOpenDanceCallsBackWithDanceId() {
        var opened: String? = null
        val post = GroupPost(
            id = "p2", groupId = "g1", uid = "u2", displayName = "Bob", body = "Check this out",
            kind = GroupPostKind.DANCE, danceId = "dance42", danceTitle = "Salsa Basics"
        )
        openGroup(posts = listOf(post), onOpenDance = { opened = it })

        waitForTagExists("openDance_p2")
        composeRule.onNodeWithText("Salsa Basics").assertIsDisplayed()
        composeRule.onNodeWithTag("openDance_p2").performClick()

        assertEquals("dance42", opened)
    }

    @Test
    fun posts_deleteButton_onlyOnOwnPosts_forAPlainMember() {
        openGroup(
            membership = me,
            posts = listOf(textPost(id = "mine", uid = "u1", name = "Alice Tan", body = "my post"), textPost(id = "theirs"))
        )

        waitForTagExists("groupPost_theirs")
        composeRule.onNodeWithTag("deletePost_mine").assertIsDisplayed()
        composeRule.onAllNodesWithTag("deletePost_theirs").assertCountEquals(0)
    }

    @Test
    fun posts_groupOwner_canDeleteAnyPost() {
        openGroup(
            membership = member("u1", "Alice Tan", GroupMember.ROLE_OWNER),
            posts = listOf(textPost(id = "theirs"))
        )

        waitForTagExists("deletePost_theirs")
        composeRule.onNodeWithTag("deletePost_theirs").assertIsDisplayed()
    }

    @Test
    fun deletePost_confirm_callsRepository() {
        val post = textPost(id = "mine", uid = "u1", name = "Alice Tan", body = "my post")
        coEvery { fx.repo.deletePost(any(), any()) } returns Result.success(Unit)
        openGroup(posts = listOf(post))

        waitForTagExists("deletePost_mine")
        composeRule.onNodeWithTag("deletePost_mine").performClick()
        composeRule.onNodeWithText("Delete this post?").assertIsDisplayed()
        composeRule.onNodeWithText("Its replies are deleted too.").assertIsDisplayed()
        composeRule.onNodeWithTag("confirmDialogButton").performClick()

        coVerify(timeout = 5_000) { fx.repo.deletePost(post, "u1") }
    }

    @Test
    fun deletePost_cancel_doesNotCallRepository() {
        openGroup(posts = listOf(textPost(id = "mine", uid = "u1", name = "Alice Tan")))

        waitForTagExists("deletePost_mine")
        composeRule.onNodeWithTag("deletePost_mine").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        waitForTextGone("Delete this post?")
        coVerify(exactly = 0) { fx.repo.deletePost(any(), any()) }
    }

    // ── Composer ─────────────────────────────────────────────────────────────

    @Test
    fun composer_postButtonIsDisabledUntilThereIsText() {
        openGroup()

        waitForTagExists("postButton")
        composeRule.onNodeWithTag("postButton").assertIsNotEnabled()

        composeRule.onNodeWithTag("groupComposerField").performTextInput("Hello crew")
        composeRule.onNodeWithTag("postButton").assertIsEnabled()
    }

    @Test
    fun composer_post_createsTextPostInTheOpenGroup() {
        coEvery { fx.repo.createTextPost(any(), any(), any()) } returns Result.success(Unit)
        openGroup()

        waitForTagExists("groupComposerField")
        composeRule.onNodeWithTag("groupComposerField").performTextInput("Hello crew")
        composeRule.onNodeWithTag("postButton").performClick()

        coVerify(timeout = 5_000) { fx.repo.createTextPost("g1", fx.author, "Hello crew") }
    }

    @Test
    fun composer_postFailure_showsSnackbarMessage() {
        coEvery { fx.repo.createTextPost(any(), any(), any()) } returns Result.failure(RuntimeException("offline"))
        openGroup()

        waitForTagExists("groupComposerField")
        composeRule.onNodeWithTag("groupComposerField").performTextInput("Hello crew")
        composeRule.onNodeWithTag("postButton").performClick()

        waitForTextExists("Couldn't post: offline")
        composeRule.onNodeWithText("Couldn't post: offline").assertIsDisplayed()
    }

    @Test
    fun composer_shareDanceButton_disabledWithoutPublicReadyDances() {
        openGroup()

        waitForTagExists("attachDanceButton")
        composeRule.onNodeWithTag("attachDanceButton").assertIsNotEnabled()
    }

    @Test
    fun composer_pickDance_attachesItAsChip_andCanBeRemoved() {
        val dance = DanceLibraryModel(
            danceId = "d1", title = "Salsa Basics", danceType = "Salsa", educatorUid = "u1",
            enabled = true, templateGenerationStatus = "ready"
        )
        coEvery { fx.uploads.loadVideos(any()) } returns Result.success(listOf(dance))
        openGroup()

        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithTag("attachDanceButton").assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag("attachDanceButton").performClick()
        composeRule.onNodeWithText("Share one of your dances").assertIsDisplayed()
        composeRule.onNodeWithTag("pickDance_d1").performClick()

        waitForTagExists("composerAttachment")
        composeRule.onNodeWithText("Dance: Salsa Basics").assertIsDisplayed()
        // A dance alone is enough to post.
        composeRule.onNodeWithTag("postButton").assertIsEnabled()

        composeRule.onNodeWithTag("composerAttachment").performClick()
        waitForTagGone("composerAttachment")
    }

    // ── Replies ──────────────────────────────────────────────────────────────

    @Test
    fun replies_openSheet_showsPostAndEmptyMessage() {
        openGroup(posts = listOf(textPost()))

        waitForTagExists("replies_p1")
        composeRule.onNodeWithTag("replies_p1").performClick()

        waitForTagExists("repliesSheet")
        composeRule.onNodeWithText("No replies yet.").assertIsDisplayed()
        composeRule.onNodeWithTag("replyField").assertIsDisplayed()
        composeRule.onNodeWithTag("sendReplyButton").assertIsNotEnabled()
        coVerify(timeout = 5_000) { fx.repo.observeReplies("g1", "p1") }
    }

    @Test
    fun replies_existingReplies_areListed() {
        fx.replies.value = listOf(
            GroupReply(id = "r1", postId = "p1", uid = "u3", displayName = "Carol", body = "Count me in")
        )
        openGroup(posts = listOf(textPost()))

        waitForTagExists("replies_p1")
        composeRule.onNodeWithTag("replies_p1").performClick()

        waitForTextExists("Count me in")
        composeRule.onNodeWithText("Carol").assertIsDisplayed()
        composeRule.onAllNodesWithText("No replies yet.").assertCountEquals(0)
    }

    @Test
    fun replies_send_createsReplyOnThePost() {
        coEvery { fx.repo.createReply(any(), any(), any(), any()) } returns Result.success(Unit)
        openGroup(posts = listOf(textPost()))

        waitForTagExists("replies_p1")
        composeRule.onNodeWithTag("replies_p1").performClick()
        waitForTagExists("replyField")
        composeRule.onNodeWithTag("replyField").performTextInput("See you there")
        composeRule.onNodeWithTag("sendReplyButton").assertIsEnabled()
        composeRule.onNodeWithTag("sendReplyButton").performClick()

        coVerify(timeout = 5_000) { fx.repo.createReply("g1", "p1", fx.author, "See you there") }
    }

    @Test
    fun replies_nonMember_cannotWriteAReply() {
        openGroup(membership = null, members = listOf(owner), posts = listOf(textPost()))

        waitForTagExists("replies_p1")
        composeRule.onNodeWithTag("replies_p1").performClick()

        waitForTagExists("repliesSheet")
        composeRule.onAllNodesWithTag("replyField").assertCountEquals(0)
    }

    // ── Options menu ─────────────────────────────────────────────────────────

    @Test
    fun menu_plainMember_canSeeMembersInviteAndLeave_butNotDelete() {
        openGroup(membership = me)

        openMenu()

        waitForTextExists("Members")
        composeRule.onNodeWithText("Members").assertIsDisplayed()
        composeRule.onNodeWithTag("inviteByUsernameItem").assertIsDisplayed()
        composeRule.onNodeWithTag("leaveGroupItem").assertIsDisplayed()
        composeRule.onAllNodesWithTag("deleteGroupItem").assertCountEquals(0)
        composeRule.onAllNodesWithText("Show invite QR code").assertCountEquals(0)
    }

    @Test
    fun menu_owner_canDeleteButNotLeave() {
        openGroup(membership = member("u1", "Alice Tan", GroupMember.ROLE_OWNER))

        openMenu()

        waitForTagExists("deleteGroupItem")
        composeRule.onNodeWithTag("deleteGroupItem").assertIsDisplayed()
        composeRule.onAllNodesWithTag("leaveGroupItem").assertCountEquals(0)
    }

    @Test
    fun menu_nonMember_hasOnlyMembers() {
        openGroup(membership = null, members = listOf(owner))

        openMenu()

        waitForTextExists("Members")
        composeRule.onAllNodesWithTag("inviteByUsernameItem").assertCountEquals(0)
        composeRule.onAllNodesWithTag("leaveGroupItem").assertCountEquals(0)
        composeRule.onAllNodesWithTag("deleteGroupItem").assertCountEquals(0)
    }

    @Test
    fun menu_privateGroupMember_offersQrAndShareInvite() {
        openGroup(group = privateGroup(), membership = me)

        openMenu()

        waitForTagExists("showQrItem")
        composeRule.onNodeWithTag("showQrItem").assertIsDisplayed()
        composeRule.onNodeWithText("Share invite").assertIsDisplayed()
    }

    @Test
    fun leaveGroup_confirm_callsRepositoryAndClosesTheGroup() {
        coEvery { fx.repo.leaveGroup(any(), any()) } returns Result.success(Unit)
        openGroup(membership = me)

        openMenu()
        waitForTagExists("leaveGroupItem")
        composeRule.onNodeWithTag("leaveGroupItem").performClick()
        composeRule.onNodeWithText("Leave this group?").assertIsDisplayed()
        composeRule.onNodeWithText("You can join again any time.").assertIsDisplayed()
        composeRule.onNodeWithTag("confirmDialogButton").performClick()

        coVerify(timeout = 5_000) { fx.repo.leaveGroup("g1", "u1") }
        waitForTagGone("groupPostList")
    }

    @Test
    fun leaveGroup_privateGroup_warnsAnInviteCodeIsNeededToReturn() {
        openGroup(group = privateGroup(), membership = me)

        openMenu()
        waitForTagExists("leaveGroupItem")
        composeRule.onNodeWithTag("leaveGroupItem").performClick()

        composeRule.onNodeWithText("You'll need an invite code to join again.").assertIsDisplayed()
    }

    @Test
    fun leaveGroup_cancel_keepsMembership() {
        openGroup(membership = me)

        openMenu()
        waitForTagExists("leaveGroupItem")
        composeRule.onNodeWithTag("leaveGroupItem").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        waitForTextGone("Leave this group?")
        coVerify(exactly = 0) { fx.repo.leaveGroup(any(), any()) }
    }

    @Test
    fun deleteGroup_ownerConfirms_callsRepository() {
        coEvery { fx.repo.deleteGroup(any()) } returns Result.success(Unit)
        openGroup(membership = member("u1", "Alice Tan", GroupMember.ROLE_OWNER))

        openMenu()
        waitForTagExists("deleteGroupItem")
        composeRule.onNodeWithTag("deleteGroupItem").performClick()
        composeRule.onNodeWithText("Delete this group?").assertIsDisplayed()
        composeRule.onNodeWithTag("confirmDialogButton").performClick()

        coVerify(timeout = 5_000) { fx.repo.deleteGroup("g1") }
    }

    // ── Members dialog ───────────────────────────────────────────────────────

    @Test
    fun membersDialog_listsMembersWithOwnerLabel_andNoRemoveForPlainMember() {
        openGroup(membership = me, members = listOf(owner, me, member("u2", "Bob")))

        openMenu()
        waitForTextExists("Members")
        composeRule.onNodeWithText("Members").performClick()

        waitForTextExists("Members (3)")
        composeRule.onNodeWithText("Olivia").assertIsDisplayed()
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
        composeRule.onNodeWithText("Owner").assertIsDisplayed()
        composeRule.onAllNodesWithTag("removeMember_u2").assertCountEquals(0)
        composeRule.onNodeWithText("Close").performClick()
        waitForTextGone("Members (3)")
    }

    @Test
    fun membersDialog_owner_canRemoveAMember() {
        coEvery { fx.repo.removeMember(any(), any()) } returns Result.success(Unit)
        coEvery { fx.repo.bannedMembers(any()) } returns Result.success(emptyList())
        val ownerMe = member("u1", "Alice Tan", GroupMember.ROLE_OWNER)
        openGroup(membership = ownerMe, members = listOf(ownerMe, member("u2", "Bob")))

        openMenu()
        waitForTextExists("Members")
        composeRule.onNodeWithText("Members").performClick()

        waitForTagExists("removeMember_u2")
        composeRule.onAllNodesWithTag("removeMember_u1").assertCountEquals(0)
        composeRule.onNodeWithTag("removeMember_u2").performClick()

        coVerify(timeout = 5_000) { fx.repo.removeMember("g1", "u2") }
    }

    @Test
    fun membersDialog_owner_seesRemovedMembersAndCanUnban() {
        val ownerMe = member("u1", "Alice Tan", GroupMember.ROLE_OWNER)
        coEvery { fx.repo.bannedMembers(any()) } returns
            Result.success(listOf(BannedMember(uid = "u9", displayName = "Mallory", photoUrl = null, bannedAt = 1L)))
        coEvery { fx.repo.unbanMember(any(), any()) } returns Result.success(Unit)
        openGroup(membership = ownerMe, members = listOf(ownerMe))

        openMenu()
        waitForTextExists("Members")
        composeRule.onNodeWithText("Members").performClick()

        waitForTagExists("unbanMember_u9")
        composeRule.onNodeWithText("Mallory").assertIsDisplayed()
        composeRule.onNodeWithTag("unbanMember_u9").performClick()

        coVerify(timeout = 5_000) { fx.repo.unbanMember("g1", "u9") }
    }

    // ── Invite by username ───────────────────────────────────────────────────

    @Test
    fun inviteByUsername_searchesAndSendsInvitation() {
        val bob = UserSearchResult(uid = "u2", username = "bobby", displayName = "Bob", photoUrl = null)
        coEvery { fx.usernames.search(any()) } returns Result.success(listOf(bob))
        coEvery { fx.repo.sendInvitation(any(), any(), any()) } returns Result.success(Unit)
        val g = publicGroup()
        openGroup(group = g, membership = me, members = listOf(owner, me))

        openMenu()
        waitForTagExists("inviteByUsernameItem")
        composeRule.onNodeWithTag("inviteByUsernameItem").performClick()
        waitForTagExists("inviteUsernameField")
        composeRule.onNodeWithTag("inviteUsernameField").performTextInput("bob")

        waitForTagExists("inviteResult_bobby")
        composeRule.onNodeWithText("@bobby").assertIsDisplayed()
        composeRule.onNodeWithTag("sendInvite_bobby").performClick()

        coVerify(timeout = 5_000) { fx.repo.sendInvitation(g, fx.author, bob) }
        waitForTextExists("Invited")
    }

    @Test
    fun inviteByUsername_noMatch_showsNoOneMessage() {
        coEvery { fx.usernames.search(any()) } returns Result.success(emptyList())
        openGroup(membership = me)

        openMenu()
        waitForTagExists("inviteByUsernameItem")
        composeRule.onNodeWithTag("inviteByUsernameItem").performClick()
        waitForTagExists("inviteUsernameField")
        composeRule.onNodeWithTag("inviteUsernameField").performTextInput("nobody")

        waitForTextExists("No one with that username.")
        composeRule.onNodeWithText("No one with that username.").assertIsDisplayed()
    }

    @Test
    fun inviteByUsername_existingMember_showsMemberLabelInsteadOfInviteButton() {
        val bob = UserSearchResult(uid = "u2", username = "bobby", displayName = "Bob", photoUrl = null)
        coEvery { fx.usernames.search(any()) } returns Result.success(listOf(bob))
        openGroup(membership = me, members = listOf(owner, me, member("u2", "Bob")))

        openMenu()
        waitForTagExists("inviteByUsernameItem")
        composeRule.onNodeWithTag("inviteByUsernameItem").performClick()
        waitForTagExists("inviteUsernameField")
        composeRule.onNodeWithTag("inviteUsernameField").performTextInput("bob")

        waitForTagExists("inviteResult_bobby")
        composeRule.onNodeWithText("Member").assertIsDisplayed()
        composeRule.onAllNodesWithTag("sendInvite_bobby").assertCountEquals(0)
    }
}
