package com.example.chorevo.dancelibrarylist.ViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.danceactivity.DanceSessionEntity
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.dancelibrarylist.DanceComment
import com.example.chorevo.dancelibrarylist.DanceCategory
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.DiscoveryFilter
import com.example.chorevo.dancelibrarylist.FeedTab
import com.example.chorevo.dancelibrarylist.applyDiscovery
import com.example.chorevo.dancelibrarylist.categoriesOf
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.posedetection.PoseTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DanceLibraryUiState(
    /** Every public, ready dance, in feed order. */
    val items: List<DanceLibraryModel> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
    /** Discovery bar: tab, category chip and search text. */
    val filter: DiscoveryFilter = DiscoveryFilter(),
    /** Category chips built from [items]. */
    val categories: List<DanceCategory> = emptyList(),
    /** [items] after the discovery filter; this is what the feed shows. */
    val visibleItems: List<DanceLibraryModel> = emptyList(),
    /** Like counts used to rank Trending; loaded the first time Trending is opened. */
    val likeCounts: Map<String, Int> = emptyMap(),
    val isLoadingTrending: Boolean = false,
)

data class DanceSessionUploadUiState(
    val isWorking: Boolean = false,
    val message: String? = null
)

/**
 * Holds the social state for one dance video.
 * Lives in the ViewModel so it survives:
 *  - scrolling away and back            (fix for issue #2)
 *  - pressing back from practice mode  (fix for issue #4)
 */
data class DanceSocialState(
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val comments: List<DanceComment> = emptyList(),
    val isLoadingMoreComments: Boolean = false,
    val hasMoreComments: Boolean = false,
    val commentCursor: DocumentSnapshot? = null
)

@HiltViewModel
class DanceLibraryListViewModel @Inject constructor(
    private val repo: DanceLibraryRepository,
    private val streakRepository: FirebaseStreakRepository,
    private val leaderboardRepository: LeaderboardRepository
) : ViewModel() {

    private val templateCache = linkedMapOf<String, PoseTemplate>()

    private val _uiState = MutableStateFlow(DanceLibraryUiState(isLoading = true, message = "Loading dances…"))
    val uiState: StateFlow<DanceLibraryUiState> = _uiState.asStateFlow()

    private val _uploadState = MutableStateFlow(DanceSessionUploadUiState())
    val uploadState: StateFlow<DanceSessionUploadUiState> = _uploadState.asStateFlow()

    // ── Social cache ──────────────────────────────────────────────────────
    // Keyed by danceId. Persists for the ViewModel lifetime so counts are
    // never wiped when the pager discards and recreates a page composable.
    private val _socialCache = MutableStateFlow<Map<String, DanceSocialState>>(emptyMap())
    val socialCache: StateFlow<Map<String, DanceSocialState>> = _socialCache.asStateFlow()

    /** Refreshes when ready. */
    fun refreshWhenReady(isLoggedIn: Boolean) {
        if (!isLoggedIn) {
            _uiState.update { it.copy(isLoading = true, message = "Signing in…") }
            return
        }
        refresh()
    }

    /** Handles refresh. */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = "Loading dances…") }

            val res = repo.listEnabled()
            res.fold(
                onSuccess = { list ->
                    _uiState.update {
                        it.copy(
                            items = list,
                            categories = categoriesOf(list),
                            isLoading = false,
                            message = if (list.isEmpty()) "No dances available." else null
                        ).withVisibleItems()
                    }
                    if (_uiState.value.filter.tab == FeedTab.TRENDING) loadLikeCounts()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, message = "Failed: ${e.message}") }
                }
            )
        }
    }

    // ── Discovery bar ─────────────────────────────────────────────────────

    /** Clock for Trending's age decay; replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    private fun DanceLibraryUiState.withVisibleItems(): DanceLibraryUiState =
        copy(visibleItems = applyDiscovery(items, filter, likeCounts, clock()))

    private fun updateFilter(transform: (DiscoveryFilter) -> DiscoveryFilter) {
        _uiState.update { it.copy(filter = transform(it.filter)).withVisibleItems() }
    }

    /** Switches between For You, Trending and New. */
    fun selectTab(tab: FeedTab) {
        updateFilter { it.copy(tab = tab) }
        if (tab == FeedTab.TRENDING && _uiState.value.likeCounts.isEmpty()) loadLikeCounts()
    }

    /** Selects a category chip; selecting the active chip again clears it. */
    fun selectCategory(key: String?) {
        updateFilter { it.copy(categoryKey = if (it.categoryKey == key) null else key) }
    }

    fun setSearchQuery(query: String) {
        updateFilter { it.copy(query = query) }
    }

    /** Clears the category and search, keeping the tab. */
    fun clearDiscoveryFilters() {
        updateFilter { it.copy(categoryKey = null, query = "") }
    }

    private fun loadLikeCounts() {
        val ids = _uiState.value.items.map { it.danceId }
        if (ids.isEmpty() || _uiState.value.isLoadingTrending) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTrending = true) }
            val counts = runCatching { repo.likeCounts(ids) }.getOrDefault(emptyMap())
            _uiState.update {
                it.copy(likeCounts = counts, isLoadingTrending = false).withVisibleItems()
            }
        }
    }

    /** Saves session and upload. */
    fun saveSessionAndUpload(
        uid: String,
        session: DanceSessionEntity,
        extraAnalytics: Map<String, Any?> = emptyMap(),
        onSuccess: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            _uploadState.update { it.copy(isWorking = true, message = null) }

            val res = repo.saveSessionAndUpload(
                session = session,
                extraAnalytics = extraAnalytics,
                uid = uid
            )

            res.fold(
                onSuccess = {
                    runCatching {
                        val updatedStreak = streakRepository.tickToday(uid)
                        leaderboardRepository.refreshUserStats(
                            uid = uid,
                            totalActiveDays = updatedStreak.totalStreakActivities
                        )
                    }
                    _uploadState.update { it.copy(isWorking = false, message = "Submitted practice result") }
                    onSuccess?.invoke()
                },
                onFailure = { e ->
                    _uploadState.update {
                        it.copy(
                            isWorking = false,
                            message = "Practice result submission failed. Saved locally. Upload failed: ${e.message}"
                        )
                    }
                }
            )
        }
    }

    /** Clears upload message. */
    fun clearUploadMessage() {
        _uploadState.update { it.copy(message = null) }
    }

    /** Resolves the reference template before opening practice mode. */
    suspend fun preparePracticeItem(item: DanceLibraryModel): Result<DanceLibraryModel> {
        item.template?.let { template ->
            templateCache[item.danceId] = template
            return Result.success(item)
        }

        templateCache[item.danceId]?.let { cached ->
            return Result.success(item.copy(template = cached))
        }

        return repo.loadTemplate(item).mapCatching { template ->
            templateCache[item.danceId] = template
            item.copy(template = template)
        }
    }

    // ── Social actions ────────────────────────────────────────────────────

    /**
     * Loads likes + comments for [danceId] into the ViewModel cache.
     *
     * If the danceId is already cached the network call is skipped entirely —
     * this is what keeps counts alive when scrolling back (issue #2) or
     * pressing back from practice (issue #4).
     */
    fun loadSocialData(danceId: String, uid: String) {
        if (_socialCache.value.containsKey(danceId)) return  // already cached — skip fetch
        viewModelScope.launch {
            repo.loadSocialData(danceId, uid).onSuccess { (count, liked) ->
                _socialCache.update { cache ->
                    cache + (danceId to (cache[danceId] ?: DanceSocialState()).copy(
                        likeCount = count,
                        isLiked = liked
                    ))
                }
            }
            repo.loadComments(danceId).onSuccess { (loaded, cursor) ->
                _socialCache.update { cache ->
                    cache + (danceId to (cache[danceId] ?: DanceSocialState()).copy(
                        comments = loaded,
                        commentCursor = cursor,
                        hasMoreComments = cursor != null
                    ))
                }
            }
        }
    }

    /**
     * Loads the next page of comments for [danceId].
     * Appends results to the existing list and updates the cursor.
     */
    fun loadMoreComments(danceId: String) {
        val current = _socialCache.value[danceId] ?: return
        val cursor = current.commentCursor ?: return
        if (current.isLoadingMoreComments) return

        _socialCache.update { cache ->
            cache + (danceId to current.copy(isLoadingMoreComments = true))
        }
        viewModelScope.launch {
            repo.loadMoreComments(danceId, cursor).onSuccess { (more, nextCursor) ->
                _socialCache.update { cache ->
                    val existing = cache[danceId] ?: DanceSocialState()
                    cache + (danceId to existing.copy(
                        comments = existing.comments + more,
                        commentCursor = nextCursor,
                        hasMoreComments = nextCursor != null,
                        isLoadingMoreComments = false
                    ))
                }
            }.onFailure {
                _socialCache.update { cache ->
                    cache + (danceId to (cache[danceId] ?: DanceSocialState())
                        .copy(isLoadingMoreComments = false))
                }
            }
        }
    }

    /**
     * Optimistically flips the like in the cache so the UI responds instantly,
     * then persists to Firestore in the background.
     */
    fun toggleLike(danceId: String, uid: String, isCurrentlyLiked: Boolean) {
        _socialCache.update { cache ->
            val current = cache[danceId] ?: DanceSocialState()
            cache + (danceId to current.copy(
                isLiked = !isCurrentlyLiked,
                likeCount = current.likeCount + if (isCurrentlyLiked) -1 else 1
            ))
        }
        viewModelScope.launch {
            repo.toggleLike(danceId, uid, isCurrentlyLiked)
        }
    }

    /**
     * Optimistically appends the comment so it appears immediately, then
     * persists to Firestore and refreshes to replace the local entry with the
     * real server doc (server-assigned commentId + createdAt).
     */
    fun postComment(danceId: String, uid: String, username: String, text: String) {
        val optimistic = DanceComment(uid = uid, username = username, text = text)
        _socialCache.update { cache ->
            val current = cache[danceId] ?: DanceSocialState()
            cache + (danceId to current.copy(comments = current.comments + optimistic))
        }
        viewModelScope.launch {
            repo.postComment(danceId, uid, username, text).onSuccess {
                repo.loadComments(danceId).onSuccess { (loaded, cursor) ->
                    _socialCache.update { cache ->
                        cache + (danceId to (cache[danceId] ?: DanceSocialState()).copy(
                            comments = loaded,
                            commentCursor = cursor,
                            hasMoreComments = cursor != null
                        ))
                    }
                }
            }
        }
    }
}