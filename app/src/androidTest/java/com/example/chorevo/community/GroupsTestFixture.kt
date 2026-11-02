package com.example.chorevo.community

import com.example.chorevo.community.repository.GroupsRepository
import com.example.chorevo.community.viewmodel.GroupsViewModel
import com.example.chorevo.firebase.UserProfile
import com.example.chorevo.firebase.repository.UsernameRepository
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared set-up for the Groups screen tests: a mocked [GroupsRepository] whose
 * observers are backed by [MutableStateFlow]s the test can change, plus a real
 * [GroupsViewModel] on top of it.
 */
class GroupsTestFixture {

    val repo: GroupsRepository = mockk(relaxed = true)
    val uploads: VideoUploadsRepository = mockk(relaxed = true)
    val usernames: UsernameRepository = mockk(relaxed = true)

    val profile = UserProfile(uid = "u1", email = "alice@example.com", displayName = "Alice Tan")
    val author = GroupsRepository.Author(uid = "u1", displayName = "Alice Tan", photoUrl = null)

    // Lists screen.
    val myGroups = MutableStateFlow<List<DanceGroup>>(emptyList())
    val invitations = MutableStateFlow<List<GroupInvitation>>(emptyList())
    var discoverGroups: List<DanceGroup> = emptyList()

    // The open group.
    val group = MutableStateFlow<DanceGroup?>(null)
    val membership = MutableStateFlow<GroupMember?>(null)
    val members = MutableStateFlow<List<GroupMember>>(emptyList())
    val posts = MutableStateFlow<List<GroupPost>>(emptyList())
    val replies = MutableStateFlow<List<GroupReply>>(emptyList())

    init {
        every { repo.observeMyGroups(any()) } returns myGroups
        every { repo.observeInvitations(any()) } returns invitations
        every { repo.observeGroup(any()) } returns group
        every { repo.observeMembership(any(), any()) } returns membership
        every { repo.observeMembers(any()) } returns members
        every { repo.observePosts(any()) } returns posts
        every { repo.observeReplies(any(), any()) } returns replies
        coEvery { repo.loadPublicGroups(any()) } answers {
            val query = firstArg<String>().trim()
            Result.success(discoverGroups.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) })
        }
        coEvery { uploads.loadVideos(any()) } returns Result.success(emptyList())
    }

    fun newViewModel() = GroupsViewModel(repo, uploads, usernames)

    companion object {
        fun publicGroup(
            id: String = "g1",
            name: String = "Salsa Crew",
            description: String = "We dance salsa",
            memberCount: Int? = 3,
            ownerUid: String = "owner1"
        ) = DanceGroup(
            id = id, name = name, description = description,
            visibility = DanceGroup.VISIBILITY_PUBLIC, ownerUid = ownerUid, ownerName = "Olivia",
            memberCount = memberCount
        )

        fun privateGroup(
            id: String = "pg1",
            name: String = "Secret Studio",
            inviteCode: String? = "K7QW2MZP",
            ownerUid: String = "owner1"
        ) = DanceGroup(
            id = id, name = name, description = "",
            visibility = DanceGroup.VISIBILITY_PRIVATE, ownerUid = ownerUid, ownerName = "Olivia",
            memberCount = 2, inviteCode = inviteCode
        )

        fun member(uid: String, name: String, role: String = GroupMember.ROLE_MEMBER) =
            GroupMember(uid = uid, displayName = name, role = role)
    }
}
