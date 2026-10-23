package com.example.chorevo.community

import com.example.chorevo.community.repository.GroupsRepository
import com.example.chorevo.community.viewmodel.GroupsViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.repository.UserSearchResult
import com.example.chorevo.firebase.repository.UsernameRepository
import com.example.chorevo.testing.MainDispatcherRule
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: GroupsRepository
    private lateinit var uploads: VideoUploadsRepository
    private lateinit var usernames: UsernameRepository
    private val invitations = MutableStateFlow<List<GroupInvitation>>(emptyList())
    private lateinit var vm: GroupsViewModel
    private val membership = MutableStateFlow<GroupMember?>(null)

    private val pub = DanceGroup(id = "g1", name = "Salsa", ownerUid = "owner")

    @Before
    fun setUp() {
        repo = mockk(relaxed = true)
        uploads = mockk(relaxed = true)
        usernames = mockk(relaxed = true)
        every { repo.observeInvitations("me") } returns invitations
        every { repo.observeMyGroups("me") } returns flowOf(listOf(pub))
        coEvery { repo.loadPublicGroups(any()) } returns Result.success(listOf(pub))
        every { repo.observeGroup("g1") } returns flowOf(pub)
        every { repo.observeMembership("g1", "me") } returns membership
        every { repo.observeMembers("g1") } returns emptyFlow()
        every { repo.observePosts("g1") } returns flowOf(listOf(GroupPost(id = "p1", uid = "other")))
        every { repo.observeReplies(any(), any()) } returns flowOf(emptyList())
        coEvery { uploads.loadVideos("me") } returns Result.success(
            listOf(
                DanceLibraryModel(danceId = "public"),
                DanceLibraryModel(danceId = "private", visibility = DanceLibraryModel.VISIBILITY_PRIVATE),
                DanceLibraryModel(danceId = "pending", templateGenerationStatus = "processing")
            )
        )
        vm = GroupsViewModel(repo, uploads, usernames)
    }

    @Test
    fun bind_loadsMyGroupsAndDiscover() = runTest {
        vm.bind("me", "Me", null)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf("g1"), s.myGroups.map { it.id })
        assertEquals(listOf("g1"), s.discoverGroups.map { it.id })
        assertFalse(s.isLoadingMyGroups)
    }

    @Test
    fun openGroup_observesPostsAndOffersOnlyPublicReadyDances() = runTest {
        vm.bind("me", "Me", null)
        vm.openGroup("g1")
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("g1", s.openGroupId)
        assertEquals("Salsa", s.openGroup?.name)
        assertEquals(listOf("p1"), s.posts.map { it.id })
        assertFalse(s.isLoadingPosts)
        assertEquals(listOf("public"), s.myDances.map { it.danceId })
    }

    @Test
    fun submitPost_requiresMembership_andPicksTheRightKind() = runTest {
        vm.bind("me", "Me", null)
        vm.openGroup("g1")
        advanceUntilIdle()

        vm.setComposerText("hello")
        vm.submitPost(mockk(relaxed = true))
        advanceUntilIdle()
        coVerify(exactly = 0) { repo.createTextPost(any(), any(), any()) }

        membership.value = GroupMember(uid = "me")
        advanceUntilIdle()
        coEvery { repo.createTextPost(any(), any(), any()) } returns Result.success(Unit)
        vm.submitPost(mockk(relaxed = true))
        advanceUntilIdle()
        coVerify { repo.createTextPost("g1", GroupsRepository.Author("me", "Me", null), "hello") }
        assertEquals("", vm.uiState.value.composer.text)

        val dance = DanceLibraryModel(danceId = "d1", title = "Bachata")
        coEvery { repo.createDancePost(any(), any(), any(), any()) } returns Result.success(Unit)
        vm.attachDance(dance)
        vm.submitPost(mockk(relaxed = true))
        advanceUntilIdle()
        coVerify { repo.createDancePost("g1", any(), "", dance) }
    }

    @Test
    fun submitPost_failureKeepsTheDraftAndShowsAMessage() = runTest {
        vm.bind("me", "Me", null)
        vm.openGroup("g1")
        membership.value = GroupMember(uid = "me")
        advanceUntilIdle()
        coEvery { repo.createTextPost(any(), any(), any()) } returns Result.failure(IllegalStateException("offline"))

        vm.setComposerText("keep me")
        vm.submitPost(mockk(relaxed = true))
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals("keep me", s.composer.text)
        assertFalse(s.composer.isPosting)
        assertNotNull(s.message)
    }

    @Test
    fun joinWithCode_rejectsShortCodesWithoutCallingTheRepository() = runTest {
        vm.bind("me", "Me", null)
        vm.joinWithCode("abc")
        advanceUntilIdle()
        coVerify(exactly = 0) { repo.joinWithCode(any(), any(), any(), any()) }
        assertNotNull(vm.uiState.value.message)
    }

    @Test
    fun joinWithCode_normalisesAndOpensTheGroup() = runTest {
        coEvery { repo.joinWithCode("ABCD2345", "me", "Me", null) } returns Result.success("g1")
        vm.bind("me", "Me", null)
        vm.joinWithCode("abcd-2345")
        advanceUntilIdle()
        assertEquals("g1", vm.uiState.value.openGroupId)
    }

    @Test
    fun onlyTheOwnerCanDeleteTheGroup() = runTest {
        vm.bind("me", "Me", null)
        vm.openGroup("g1")
        membership.value = GroupMember(uid = "me")
        advanceUntilIdle()
        vm.deleteOpenGroup()
        advanceUntilIdle()
        coVerify(exactly = 0) { repo.deleteGroup(any()) }

        membership.value = GroupMember(uid = "me", role = GroupMember.ROLE_OWNER)
        advanceUntilIdle()
        coEvery { repo.deleteGroup("g1") } returns Result.success(Unit)
        vm.deleteOpenGroup()
        advanceUntilIdle()
        coVerify { repo.deleteGroup("g1") }
        assertNull(vm.uiState.value.openGroupId)
    }

    @Test
    fun invitations_areObserved_andAcceptingOpensTheGroup() = runTest {
        val inv = GroupInvitation(id = "g1_me", groupId = "g1", groupName = "Salsa", inviteeUid = "me", inviterName = "Ana")
        coEvery { repo.acceptInvitation(inv, any()) } returns Result.success("g1")
        vm.bind("me", "Me", null)
        invitations.value = listOf(inv)
        advanceUntilIdle()
        assertEquals(listOf("g1_me"), vm.uiState.value.invitations.map { it.id })

        vm.acceptInvitation(inv)
        advanceUntilIdle()
        coVerify { repo.acceptInvitation(inv, GroupsRepository.Author("me", "Me", null)) }
        assertEquals("g1", vm.uiState.value.openGroupId)
        assertNull(vm.uiState.value.answeringInvitationId)
    }

    @Test
    fun declineInvitation_deletesIt() = runTest {
        val inv = GroupInvitation(id = "g1_me", groupId = "g1")
        coEvery { repo.declineInvitation(inv) } returns Result.success(Unit)
        vm.bind("me", "Me", null)
        vm.declineInvitation(inv)
        advanceUntilIdle()
        coVerify { repo.declineInvitation(inv) }
    }

    @Test
    fun inviteSearch_hidesMyself_andSendingMarksInvited() = runTest {
        val ana = UserSearchResult("ana", "ana_d", "Ana", null)
        val me = UserSearchResult("me", "me_me", "Me", null)
        coEvery { usernames.search("an") } returns Result.success(listOf(ana, me))
        coEvery { repo.sendInvitation(any(), any(), ana) } returns Result.success(Unit)
        vm.bind("me", "Me", null)
        vm.openGroup("g1")
        membership.value = GroupMember(uid = "me")
        advanceUntilIdle()

        vm.setInviteQuery("@An")
        advanceUntilIdle()
        assertEquals(listOf("ana"), vm.uiState.value.inviteSearch.results.map { it.uid })

        vm.sendInvitation(ana)
        advanceUntilIdle()
        coVerify { repo.sendInvitation(pub, GroupsRepository.Author("me", "Me", null), ana) }
        assertTrue("ana" in vm.uiState.value.inviteSearch.invitedUids)
    }

    @Test
    fun sendInvitation_needsMembership() = runTest {
        vm.bind("me", "Me", null)
        vm.openGroup("g1")
        advanceUntilIdle()
        vm.sendInvitation(UserSearchResult("ana", "ana_d", "Ana", null))
        advanceUntilIdle()
        coVerify(exactly = 0) { repo.sendInvitation(any(), any(), any()) }
    }

    @Test
    fun openPost_thenCloseGroup_clearsEverything() = runTest {
        vm.bind("me", "Me", null)
        vm.openGroup("g1")
        advanceUntilIdle()
        vm.openPost("p1")
        advanceUntilIdle()
        assertEquals("p1", vm.uiState.value.openPost?.id)

        vm.closeGroup()
        val s = vm.uiState.value
        assertNull(s.openGroupId)
        assertNull(s.openPostId)
        assertTrue(s.posts.isEmpty())
    }
}
