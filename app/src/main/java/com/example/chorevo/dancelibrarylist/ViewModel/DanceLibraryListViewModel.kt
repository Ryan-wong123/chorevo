package com.example.chorevo.dancelibrarylist.ViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.danceactivity.DanceSessionEntity
import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.dancelibrarylist.DanceComment
import com.example.chorevo.dancelibrarylist.DanceCategory
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.DiscoveryFilter
import com.example.chorevo.dancelibrarylist.FeedCursor
import com.example.chorevo.dancelibrarylist.FeedTab
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.posedetection.PoseTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

data class DanceLibraryUiState(
    /** Dances loaded so far for [filter], in feed order (pages are appended). */
    val items: List<DanceLibraryModel> = emptyList(),
    /** True while the first page for the current filter is loading. */
    val isLoading: Boolean = false,
    val message: String? = null,
    /** Discovery bar: tab, category chip and search text. */
    val filter: DiscoveryFilter = DiscoveryFilter(),
    /** Category chips (from meta/danceCategories). */
    val categories: List<DanceCategory> = emptyList(),
    /** True while a further page is loading. */
    val isLoadingMore: Boolean = false,
    /** True once the last page for [filter] has been loaded. */
    val endReached: Boolean = false,
) {
    /** What the feed shows. */
    val visibleItems: List<DanceLibraryModel> get() = items
}

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

private const val SEARCH_DEBOUNCE_MS = 300L
/** Pages fetched in a row when they come back empty after on-device search filtering. */
private const val MAX_EMPTY_PAGES = 5

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

    // ── Feed paging and discovery bar ─────────────────────────────────────

    /** Random start point for the For You feed, fresh each app session. */
    internal var forYouSeed: Double = Random.nextDouble()
    private var nextCursor: FeedCursor? = null
    private var feedJob: Job? = null
    private var searchJob: Job? = null

    /** Reloads chips and the first page for the current filter. */
    fun refresh() {
        viewModelScope.launch {
            repo.loadCategories().onSuccess { cats -> _uiState.update { it.copy(categories = cats) } }
        }
        reloadFeed()
    }

    /** Starts the feed for the current filter from its first page. */
    private fun reloadFeed() {
        feedJob?.cancel()
        nextCursor = null
        _uiState.update {
            it.copy(
                items = emptyList(),
                isLoading = true,
                isLoadingMore = false,
                endReached = false,
                message = "Loading dances…"
            )
        }
        feedJob = viewModelScope.launch { loadPages() }
    }

    /** Loads the next page when the user nears the end of what is loaded. */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.endReached) return
        _uiState.update { it.copy(isLoadingMore = true) }
        feedJob = viewModelScope.launch { loadPages() }
    }

    /**
     * Fetches pages until something new is shown or the feed ends (a page can
     * come back empty when extra search words filter all of it out).
     */
    private suspend fun loadPages() {
        val filter = _uiState.value.filter
        var attempts = 0
        while (true) {
            val page = repo.loadFeedPage(filter, forYouSeed, nextCursor).getOrElse { e ->
                _uiState.update {
                    it.copy(isLoading = false, isLoadingMore = false, message = "Failed: ${e.message}")
                }
                return
            }
            nextCursor = page.next
            val ended = page.next == null
            _uiState.update { state ->
                val seen = state.items.map { it.danceId }.toSet()
                val merged = state.items + page.items.filter { it.danceId !in seen }
                state.copy(
                    items = merged,
                    endReached = ended,
                    message = if (merged.isEmpty() && ended) "No dances available." else null
                )
            }
            attempts++
            if (page.items.isNotEmpty() || ended || attempts >= MAX_EMPTY_PAGES) break
        }
        _uiState.update { it.copy(isLoading = false, isLoadingMore = false) }
    }

    /** Switches between For You, Trending and New. */
    fun selectTab(tab: FeedTab) {
        if (_uiState.value.filter.tab == tab) return
        _uiState.update { it.copy(filter = it.filter.copy(tab = tab)) }
        reloadFeed()
    }

    /** Selects a category chip; selecting the active chip again clears it. */
    fun selectCategory(key: String?) {
        _uiState.update {
            it.copy(filter = it.filter.copy(categoryKey = if (it.filter.categoryKey == key) null else key))
        }
        reloadFeed()
    }

    /** Updates the search text; the feed reloads once typing pauses. */
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(filter = it.filter.copy(query = query)) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            reloadFeed()
        }
    }

    /** Clears the category and search, keeping the tab. */
    fun clearDiscoveryFilters() {
        searchJob?.cancel()
        _uiState.update { it.copy(filter = it.filter.copy(categoryKey = null, query = "")) }
        reloadFeed()
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
                    // The leaderboard is recomputed server-side from the session and streak.
                    runCatching { streakRepository.tickToday(uid) }
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