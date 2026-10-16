package com.example.chorevo.leaderboard.ViewModel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.leaderboard.LeaderboardEntry
import com.example.chorevo.leaderboard.LeaderboardMetric
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.leaderboard.LeaderboardScope
import com.example.chorevo.leaderboard.LeaderboardUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val repo: LeaderboardRepository
) : ViewModel() {

    private data class UiInputs(
        val scope: LeaderboardScope,
        val metric: LeaderboardMetric,
        val myUid: String?,
        val addingFriendUid: String?,
        val removingFriendUid: String?

    )

    private val _scope = MutableStateFlow(LeaderboardScope.GLOBAL)
    private val _metric = MutableStateFlow(LeaderboardMetric.TOTAL_SESSIONS)
    private val _myUid = MutableStateFlow<String?>(null)
    private val _addingFriendUid = MutableStateFlow<String?>(null)

    private val _removingFriendUid = MutableStateFlow<String?>(null)

    val uiState: StateFlow<LeaderboardUiState> =
        combine(_scope, _metric, _myUid, _addingFriendUid,_removingFriendUid ) { scope, metric, myUid, addingFriendUid,removingFriendUid  ->
            UiInputs(scope, metric, myUid, addingFriendUid,removingFriendUid )
        }.flatMapLatest { inputs ->
            val myUid = inputs.myUid

            if (myUid.isNullOrBlank()) {
                flowOf(
                    LeaderboardUiState(
                        scope = inputs.scope,
                        metric = inputs.metric,
                        entries = emptyList(),
                        myEntry = null,
                        friendIds = emptySet(),
                        addingFriendUid = inputs.addingFriendUid,
                        removingFriendUid = inputs.removingFriendUid,
                        isLoading = false
                    )
                )
            } else {
                // On the global board, "Your position" still shows when you're
                // outside the loaded top 100.
                val myGlobalEntry = if (inputs.scope == LeaderboardScope.GLOBAL) {
                    repo.observeMyGlobalEntry(inputs.metric, myUid).onStart { emit(null) }
                } else {
                    flowOf(null)
                }
                combine(
                    repo.observeLeaderboard(inputs.scope, inputs.metric, myUid),
                    repo.observeFriendIds(myUid),
                    myGlobalEntry
                ) { entries, friendIds, outsideEntry ->
                    LeaderboardUiState(
                        scope = inputs.scope,
                        metric = inputs.metric,
                        entries = entries,
                        myEntry = entries.firstOrNull { it.isMe } ?: outsideEntry,
                        friendIds = friendIds,
                        addingFriendUid = inputs.addingFriendUid,
                        removingFriendUid = inputs.removingFriendUid,
                        isLoading = false
                    )
                }.onStart {
                    emit(
                        LeaderboardUiState(
                            scope = inputs.scope,
                            metric = inputs.metric,
                            entries = emptyList(),
                            myEntry = null,
                            friendIds = emptySet(),
                            addingFriendUid = inputs.addingFriendUid,
                            removingFriendUid = inputs.removingFriendUid,
                            isLoading = true
                        )
                    )
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            LeaderboardUiState()
        )

    /** Updates scope. */
    fun setScope(scope: LeaderboardScope) {
        _scope.value = scope
    }

    /** Binds user. */
    fun bindUser(uid: String) {
        _myUid.value = uid
    }

    /** Adds friend. */
    fun addFriend(entry: LeaderboardEntry) {
        val myUid = _myUid.value ?: return
        if (entry.uid == myUid) return
        if (uiState.value.friendIds.contains(entry.uid)) return
        if (_addingFriendUid.value == entry.uid) return

        viewModelScope.launch {
            _addingFriendUid.value = entry.uid
            try {
                repo.addFriend(
                    myUid = myUid,
                    friendUid = entry.uid,
                    displayName = entry.displayName,
                    photoUrl = entry.photoUrl
                )
            } catch (e: Exception) {
                Log.e("LeaderboardViewModel", "addFriend failed", e)
            } finally {
                _addingFriendUid.value = null
            }
        }
    }
    /** Removes friend. */
    fun removeFriend(entry: LeaderboardEntry) {
        val myUid = _myUid.value ?: return
        if (entry.uid == myUid) return
        if (_removingFriendUid.value == entry.uid) return

        viewModelScope.launch {
            _removingFriendUid.value = entry.uid
            try {
                repo.removeFriend(
                    myUid = myUid,
                    friendUid = entry.uid
                )
            } catch (e: Exception) {
                Log.e("LeaderboardViewModel", "removeFriend failed", e)
            } finally {
                _removingFriendUid.value = null
            }
        }
    }
    /** Updates metric. */
    fun setMetric(metric: LeaderboardMetric) {
        _metric.value = metric
    }
}
