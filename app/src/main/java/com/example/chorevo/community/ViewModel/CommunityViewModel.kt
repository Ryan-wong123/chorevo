package com.example.chorevo.community.ViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.community.CommunityUiState
import com.example.chorevo.community.Repository.CommunityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommunityViewModel @Inject constructor(
    private val repository: CommunityRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CommunityUiState())
    val uiState: StateFlow<CommunityUiState> = _uiState.asStateFlow()

    private var repliesJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observePosts().collect { posts ->
                _uiState.update { current ->
                    val selectedId = current.selectedPostId
                        ?.takeIf { id -> posts.any { it.id == id } }
                    current.copy(
                        posts = posts,
                        selectedPostId = selectedId,
                        replies = if (selectedId == null) emptyList() else current.replies
                    )
                }
            }
        }
    }

    fun setPostTitleDraft(value: String) {
        _uiState.update { it.copy(postTitleDraft = value.take(120)) }
    }

    fun setPostBodyDraft(value: String) {
        _uiState.update { it.copy(postBodyDraft = value.take(1_500)) }
    }

    fun setReplyDraft(value: String) {
        _uiState.update { it.copy(replyDraft = value.take(500)) }
    }

    fun selectPost(postId: String) {
        if (_uiState.value.selectedPostId == postId) {
            repliesJob?.cancel()
            repliesJob = null
            _uiState.update { it.copy(selectedPostId = null, replies = emptyList(), replyDraft = "") }
            return
        }

        repliesJob?.cancel()
        _uiState.update { it.copy(selectedPostId = postId, replies = emptyList(), replyDraft = "") }
        repliesJob = viewModelScope.launch {
            repository.observeReplies(postId).collect { replies ->
                _uiState.update { it.copy(replies = replies) }
            }
        }
    }

    fun createPost(
        uid: String,
        displayName: String,
        photoUrl: String?,
        onCreated: () -> Unit = {}
    ) {
        val title = _uiState.value.postTitleDraft.trim()
        val body = _uiState.value.postBodyDraft.trim()
        if (title.isBlank() || body.isBlank() || _uiState.value.isPosting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPosting = true, errorMessage = null) }
            try {
                val post = repository.createPost(
                    uid = uid,
                    displayName = displayName,
                    photoUrl = photoUrl,
                    title = title,
                    body = body
                )
                _uiState.update {
                    it.copy(
                        posts = post?.let { created ->
                            listOf(created) + it.posts.filterNot { existing -> existing.id == created.id }
                        } ?: it.posts,
                        postTitleDraft = "",
                        postBodyDraft = "",
                        isPosting = false
                    )
                }
                if (post != null) {
                    onCreated()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isPosting = false,
                        errorMessage = e.message ?: "Could not publish post."
                    )
                }
            }
        }
    }

    fun createReply(
        uid: String,
        displayName: String,
        photoUrl: String?
    ) {
        val postId = _uiState.value.selectedPostId ?: return
        val body = _uiState.value.replyDraft.trim()
        if (body.isBlank() || _uiState.value.isReplying) return

        viewModelScope.launch {
            _uiState.update { it.copy(isReplying = true, errorMessage = null) }
            try {
                val reply = repository.createReply(
                    postId = postId,
                    uid = uid,
                    displayName = displayName,
                    photoUrl = photoUrl,
                    body = body
                )
                _uiState.update {
                    it.copy(
                        replies = reply?.let { created ->
                            it.replies.filterNot { existing -> existing.id == created.id } + created
                        } ?: it.replies,
                        replyDraft = "",
                        isReplying = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isReplying = false,
                        errorMessage = e.message ?: "Could not send reply."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
