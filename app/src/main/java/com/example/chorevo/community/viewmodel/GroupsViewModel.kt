package com.example.chorevo.community.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewmodel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.community.GroupInvitation
import com.example.chorevo.community.GroupPost
import com.example.chorevo.community.InviteSearch
import com.example.chorevo.community.GroupsTab
import com.example.chorevo.community.GroupsUiState
import com.example.chorevo.community.INVITE_CODE_LENGTH
import com.example.chorevo.community.PostComposer
import com.example.chorevo.community.repository.GroupsRepository
import com.example.chorevo.community.isValidGroupName
import com.example.chorevo.community.normalizeInviteCode
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.repository.UserSearchResult
import com.example.chorevo.firebase.repository.UsernameRepository
import com.example.chorevo.firebase.Usernames
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Groups list, discovery, and the open group's posts, replies and composer. */
@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val repository: GroupsRepository,
    private val uploads: VideoUploadsRepository,
    private val usernames: UsernameRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupsUiState())
    val uiState: StateFlow<GroupsUiState> = _uiState.asStateFlow()

    private var author: GroupsRepository.Author? = null
    private var myGroupsJob: Job? = null
    private var groupJobs: List<Job> = emptyList()
    private var repliesJob: Job? = null
    private var discoverJob: Job? = null
    private var invitationsJob: Job? = null
    private var inviteSearchJob: Job? = null

    /** Starts watching the user's groups; call when the screen opens. */
    fun bind(uid: String, displayName: String, photoUrl: String?) {
        if (author?.uid == uid) return
        // Names are limited to 60 characters by firestore.rules.
        author = GroupsRepository.Author(uid, displayName.trim().ifBlank { "User" }.take(60), photoUrl)
        myGroupsJob?.cancel()
        myGroupsJob = viewModelScope.launch {
            repository.observeMyGroups(uid).collect { groups ->
                _uiState.update { it.copy(myGroups = groups, isLoadingMyGroups = false) }
            }
        }
        invitationsJob?.cancel()
        invitationsJob = viewModelScope.launch {
            repository.observeInvitations(uid).collect { invitations ->
                _uiState.update { it.copy(invitations = invitations) }
            }
        }
        refreshDiscover()
    }

    fun selectTab(tab: GroupsTab) {
        _uiState.update { it.copy(tab = tab) }
        if (tab == GroupsTab.DISCOVER && _uiState.value.discoverGroups.isEmpty()) refreshDiscover()
    }

    /** Updates the discover search; results reload once typing pauses. */
    fun setDiscoverQuery(query: String) {
        _uiState.update { it.copy(discoverQuery = query) }
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            loadDiscover()
        }
    }

    fun refreshDiscover() {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch { loadDiscover() }
    }

    private suspend fun loadDiscover() {
        _uiState.update { it.copy(isLoadingDiscover = true) }
        val result = repository.loadPublicGroups(_uiState.value.discoverQuery)
        _uiState.update {
            it.copy(
                discoverGroups = result.getOrDefault(it.discoverGroups),
                isLoadingDiscover = false,
                message = result.exceptionOrNull()?.let { e -> "Couldn't load groups: ${e.message}" } ?: it.message
            )
        }
    }

    // ── Create / join ───────────────────────────────────────────────────

    fun createGroup(name: String, description: String, private: Boolean) {
        val me = author ?: return
        if (!isValidGroupName(name) || _uiState.value.isWorking) return
        work {
            repository.createGroup(me.uid, me.displayName, me.photoUrl, name, description, private)
                .onSuccess { group -> openGroup(group.id) }
                .onFailure { e -> say("Couldn't create the group: ${e.message}") }
        }
    }

    fun joinOpenGroup() {
        val me = author ?: return
        val groupId = _uiState.value.openGroupId ?: return
        work {
            repository.joinPublicGroup(groupId, me.uid, me.displayName, me.photoUrl)
                .onSuccess { say("You joined the group") }
                .onFailure { e -> say("Couldn't join: ${e.message}") }
        }
    }

    fun joinWithCode(rawCode: String) {
        val me = author ?: return
        val code = normalizeInviteCode(rawCode)
        if (code.length != INVITE_CODE_LENGTH) {
            say("Invite codes have $INVITE_CODE_LENGTH letters and numbers.")
            return
        }
        work {
            repository.joinWithCode(code, me.uid, me.displayName, me.photoUrl)
                .onSuccess { groupId ->
                    openGroup(groupId)
                    say("You joined the group")
                }
                .onFailure { e -> say(e.message ?: "Couldn't join with that code.") }
        }
    }

    fun leaveOpenGroup() {
        val me = author ?: return
        val groupId = _uiState.value.openGroupId ?: return
        work {
            repository.leaveGroup(groupId, me.uid)
                .onSuccess {
                    closeGroup()
                    say("You left the group")
                }
                .onFailure { e -> say("Couldn't leave: ${e.message}") }
        }
    }

    fun deleteOpenGroup() {
        val groupId = _uiState.value.openGroupId ?: return
        if (!_uiState.value.isOwner) return
        work {
            repository.deleteGroup(groupId)
                .onSuccess {
                    closeGroup()
                    say("Group deleted")
                }
                .onFailure { e -> say("Couldn't delete the group: ${e.message}") }
        }
    }

    fun removeMember(memberUid: String) {
        val groupId = _uiState.value.openGroupId ?: return
        if (!_uiState.value.isOwner || memberUid == author?.uid) return
        work {
            repository.removeMember(groupId, memberUid)
                .onFailure { e -> say("Couldn't remove member: ${e.message}") }
        }
    }

    // ── Invitations by username ─────────────────────────────────────────

    fun acceptInvitation(invitation: GroupInvitation) {
        val me = author ?: return
        if (_uiState.value.answeringInvitationId != null) return
        _uiState.update { it.copy(answeringInvitationId = invitation.id) }
        viewModelScope.launch {
            repository.acceptInvitation(invitation, me)
                .onSuccess { groupId ->
                    openGroup(groupId)
                    say("You joined ${invitation.groupName}")
                }
                .onFailure { e -> say(e.message ?: "Couldn't join the group.") }
            _uiState.update { it.copy(answeringInvitationId = null) }
        }
    }

    fun declineInvitation(invitation: GroupInvitation) {
        if (_uiState.value.answeringInvitationId != null) return
        _uiState.update { it.copy(answeringInvitationId = invitation.id) }
        viewModelScope.launch {
            repository.declineInvitation(invitation)
                .onFailure { e -> say("Couldn't decline: ${e.message}") }
            _uiState.update { it.copy(answeringInvitationId = null) }
        }
    }

    /** Searches usernames for the open group's invite dialog; results load once typing pauses. */
    fun setInviteQuery(query: String) {
        _uiState.update { it.copy(inviteSearch = it.inviteSearch.copy(query = query)) }
        inviteSearchJob?.cancel()
        val prefix = Usernames.normalize(query)
        if (prefix.isEmpty()) {
            _uiState.update { it.copy(inviteSearch = it.inviteSearch.copy(results = emptyList(), isSearching = false)) }
            return
        }
        _uiState.update { it.copy(inviteSearch = it.inviteSearch.copy(isSearching = true)) }
        inviteSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val result = usernames.search(prefix)
            val myUid = author?.uid
            _uiState.update { s ->
                s.copy(
                    inviteSearch = s.inviteSearch.copy(
                        results = result.getOrDefault(emptyList()).filter { it.uid != myUid },
                        isSearching = false
                    ),
                    message = result.exceptionOrNull()?.let { e -> "Couldn't search: ${e.message}" } ?: s.message
                )
            }
        }
    }

    fun resetInviteSearch() {
        inviteSearchJob?.cancel()
        _uiState.update { it.copy(inviteSearch = InviteSearch()) }
    }

    fun sendInvitation(user: UserSearchResult) {
        val me = author ?: return
        val state = _uiState.value
        val group = state.openGroup ?: return
        if (!state.isMember || state.inviteSearch.sendingUid != null) return
        if (state.members.any { it.uid == user.uid }) {
            say("@${user.username} is already in this group")
            return
        }
        _uiState.update { it.copy(inviteSearch = it.inviteSearch.copy(sendingUid = user.uid)) }
        viewModelScope.launch {
            val result = repository.sendInvitation(group, me, user)
            _uiState.update { s ->
                s.copy(
                    inviteSearch = s.inviteSearch.copy(
                        sendingUid = null,
                        invitedUids = if (result.isSuccess) s.inviteSearch.invitedUids + user.uid else s.inviteSearch.invitedUids
                    ),
                    message = if (result.isSuccess) {
                        "Invited @${user.username}"
                    } else {
                        "Couldn't invite @${user.username}. They may already be invited or in the group."
                    }
                )
            }
        }
    }

    // ── The open group ──────────────────────────────────────────────────

    fun openGroup(groupId: String) {
        val me = author ?: return
        groupJobs.forEach { it.cancel() }
        repliesJob?.cancel()
        _uiState.update {
            it.copy(
                openGroupId = groupId,
                openGroup = it.myGroups.firstOrNull { g -> g.id == groupId }
                    ?: it.discoverGroups.firstOrNull { g -> g.id == groupId },
                membership = null,
                members = emptyList(),
                posts = emptyList(),
                isLoadingPosts = true,
                composer = PostComposer(),
                openPostId = null,
                replies = emptyList(),
                inviteSearch = InviteSearch()
            )
        }
        groupJobs = listOf(
            viewModelScope.launch {
                repository.observeGroup(groupId).collect { group ->
                    _uiState.update { s -> if (s.openGroupId == groupId) s.copy(openGroup = group ?: s.openGroup) else s }
                }
            },
            viewModelScope.launch {
                repository.observeMembership(groupId, me.uid).collect { m ->
                    _uiState.update { s -> if (s.openGroupId == groupId) s.copy(membership = m) else s }
                }
            },
            viewModelScope.launch {
                repository.observePosts(groupId).collect { posts ->
                    _uiState.update { s -> if (s.openGroupId == groupId) s.copy(posts = posts, isLoadingPosts = false) else s }
                }
            },
            viewModelScope.launch {
                repository.observeMembers(groupId).collect { members ->
                    _uiState.update { s -> if (s.openGroupId == groupId) s.copy(members = members) else s }
                }
            }
        )
        loadMyDances()
    }

    fun closeGroup() {
        groupJobs.forEach { it.cancel() }
        groupJobs = emptyList()
        repliesJob?.cancel()
        _uiState.update {
            it.copy(
                openGroupId = null,
                openGroup = null,
                membership = null,
                members = emptyList(),
                posts = emptyList(),
                composer = PostComposer(),
                openPostId = null,
                replies = emptyList()
            )
        }
    }

    /** The user's public dances, offered when sharing a dance in a group. */
    private fun loadMyDances() {
        val me = author ?: return
        if (_uiState.value.myDances.isNotEmpty()) return
        viewModelScope.launch {
            uploads.loadVideos(me.uid).onSuccess { dances ->
                _uiState.update { s ->
                    s.copy(myDances = dances.filter { !it.isPrivate && it.enabled && it.isTemplateReady })
                }
            }
        }
    }

    // ── Composer ────────────────────────────────────────────────────────

    fun setComposerText(text: String) {
        _uiState.update { it.copy(composer = it.composer.copy(text = text.take(1_500))) }
    }

    fun attachDance(dance: DanceLibraryModel) {
        _uiState.update { it.copy(composer = it.composer.copy(dance = dance, videoUri = null)) }
    }

    fun attachVideo(uri: Uri) {
        _uiState.update { it.copy(composer = it.composer.copy(videoUri = uri, dance = null)) }
    }

    fun clearAttachment() {
        _uiState.update { it.copy(composer = it.composer.copy(dance = null, videoUri = null)) }
    }

    fun submitPost(context: Context) {
        val me = author ?: return
        val groupId = _uiState.value.openGroupId ?: return
        val composer = _uiState.value.composer
        if (!composer.canPost || !_uiState.value.isMember) return

        _uiState.update { it.copy(composer = it.composer.copy(isPosting = true, progress = null)) }
        viewModelScope.launch {
            val result = when {
                composer.videoUri != null -> repository.createVideoPost(
                    context.applicationContext, groupId, me, composer.text, composer.videoUri
                ) { p -> _uiState.update { it.copy(composer = it.composer.copy(progress = p)) } }
                composer.dance != null -> repository.createDancePost(groupId, me, composer.text, composer.dance)
                else -> repository.createTextPost(groupId, me, composer.text)
            }
            result
                .onSuccess { _uiState.update { it.copy(composer = PostComposer()) } }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            composer = it.composer.copy(isPosting = false, progress = null),
                            message = "Couldn't post: ${e.message}"
                        )
                    }
                }
        }
    }

    fun deletePost(post: GroupPost) {
        val me = author ?: return
        if (post.uid != me.uid && !_uiState.value.isOwner) return
        work {
            repository.deletePost(post, me.uid)
                .onSuccess { if (_uiState.value.openPostId == post.id) closePost() }
                .onFailure { e -> say("Couldn't delete the post: ${e.message}") }
        }
    }

    // ── Replies ─────────────────────────────────────────────────────────

    fun openPost(postId: String) {
        val groupId = _uiState.value.openGroupId ?: return
        repliesJob?.cancel()
        _uiState.update { it.copy(openPostId = postId, replies = emptyList(), replyDraft = "") }
        repliesJob = viewModelScope.launch {
            repository.observeReplies(groupId, postId).collect { replies ->
                _uiState.update { s -> if (s.openPostId == postId) s.copy(replies = replies) else s }
            }
        }
    }

    fun closePost() {
        repliesJob?.cancel()
        _uiState.update { it.copy(openPostId = null, replies = emptyList(), replyDraft = "") }
    }

    fun setReplyDraft(text: String) {
        _uiState.update { it.copy(replyDraft = text.take(500)) }
    }

    fun sendReply() {
        val me = author ?: return
        val state = _uiState.value
        val groupId = state.openGroupId ?: return
        val postId = state.openPostId ?: return
        if (state.replyDraft.isBlank() || state.isReplying || !state.isMember) return
        _uiState.update { it.copy(isReplying = true) }
        viewModelScope.launch {
            repository.createReply(groupId, postId, me, state.replyDraft)
                .onSuccess { _uiState.update { it.copy(replyDraft = "", isReplying = false) } }
                .onFailure { e -> _uiState.update { it.copy(isReplying = false, message = "Couldn't reply: ${e.message}") } }
        }
    }

    /** Shows a one-off snackbar message. */
    fun showMessage(message: String) = say(message)

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun say(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            try {
                block()
            } finally {
                _uiState.update { it.copy(isWorking = false) }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
