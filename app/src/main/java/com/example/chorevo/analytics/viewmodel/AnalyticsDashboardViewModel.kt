package com.example.chorevo.analytics.viewmodel

import androidx.lifecycle.viewmodel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.analytics.DanceDashboardArgs
import com.example.chorevo.analytics.DanceSessionDetail
import com.example.chorevo.analytics.DanceSessionSummary
import com.example.chorevo.analytics.repository.AnalyticsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * ViewModel for managing analytics dashboard session list and selected session detail.
 * Connects UI state with repository data streams.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsDashboardViewModel @Inject constructor(
    private val repo: AnalyticsRepository
) : ViewModel() {

    private val _args = MutableStateFlow<DanceDashboardArgs?>(null)
    private val _selectedSessionKey = MutableStateFlow<String?>(null)
    /**
     * Sets the dashboard arguments for the current screen.
     * Also clears the currently selected session.
     */
    fun setArgs(args: DanceDashboardArgs) {
        _args.value = args
        _selectedSessionKey.value = null
    }
    /**
     * Updates the currently selected session key.
     * Triggers loading of the selected session detail.
     */
    fun selectSession(sessionKey: String) {
        _selectedSessionKey.value = sessionKey
    }
    /**
     * StateFlow of session summaries for the current dashboard mode.
     * Updates automatically when dashboard arguments change.
     */
    val sessions: StateFlow<List<DanceSessionSummary>> =
        _args
            .flatMapLatest { args ->
                if (args == null) {
                    flowOf(emptyList())
                } else {
                    repo.observeSessions(args = args)
                        .onStart { emit(emptyList()) }
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    val selectedDetail: StateFlow<DanceSessionDetail?> =
        combine(_args, _selectedSessionKey) { args, key -> args to key }
            .flatMapLatest { (args, key) ->
                if (args == null || key.isNullOrBlank()) {
                    flowOf(null)
                } else {
                    repo.observeDetail(args = args, sessionKey = key)
                        .onStart { emit(null) }
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                null
            )
}
