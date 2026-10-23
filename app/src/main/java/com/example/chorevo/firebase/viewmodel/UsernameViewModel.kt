package com.example.chorevo.firebase.viewmodel

import androidx.lifecycle.viewmodel
import androidx.lifecycle.viewModelScope
import com.example.chorevo.firebase.repository.UsernameRepository
import com.example.chorevo.firebase.Usernames
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UsernameUiState(
    val input: String = "",
    /** Format problem with [input], or null. */
    val problem: String? = null,
    val isChecking: Boolean = false,
    /** Whether [input] is free (or already the user's); null while unknown. */
    val isAvailable: Boolean? = null,
    val isSaving: Boolean = false,
    val error: String? = null
) {
    val canSave: Boolean get() = problem == null && isAvailable == true && !isChecking && !isSaving
}

/** Picking or changing the user's unique username. */
@HiltViewModel
class UsernameViewModel @Inject constructor(
    private val repository: UsernameRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsernameUiState())
    val uiState: StateFlow<UsernameUiState> = _uiState.asStateFlow()

    private var current: String? = null
    private var checkJob: Job? = null

    /** Fills in the current username, or a free suggestion from [displayName]. */
    fun start(current: String?, displayName: String?) {
        this.current = current
        _uiState.value = UsernameUiState(isChecking = current == null)
        if (current != null) {
            setInput(current)
            return
        }
        viewModelScope.launch {
            val suggestion = repository.suggest(displayName)
            if (_uiState.value.input.isEmpty()) setInput(suggestion)
        }
    }

    fun setInput(raw: String) {
        val handle = Usernames.normalize(raw)
        val problem = Usernames.problem(handle)
        _uiState.update { it.copy(input = handle, problem = problem, isAvailable = null, error = null, isChecking = problem == null) }
        checkJob?.cancel()
        if (problem != null) return
        if (handle == current) {
            _uiState.update { it.copy(isAvailable = true, isChecking = false) }
            return
        }
        checkJob = viewModelScope.launch {
            delay(CHECK_DEBOUNCE_MS)
            val available = repository.isAvailable(handle)
            _uiState.update { s ->
                if (s.input != handle) s else s.copy(
                    isChecking = false,
                    isAvailable = available.getOrNull(),
                    error = available.exceptionOrNull()?.let { "Couldn't check: ${it.message}" }
                )
            }
        }
    }

    fun save(uid: String, onSaved: (String) -> Unit) {
        val state = _uiState.value
        if (!state.canSave) return
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            repository.claim(uid, state.input, current)
                .onSuccess { handle ->
                    current = handle
                    _uiState.update { it.copy(isSaving = false) }
                    onSaved(handle)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isSaving = false, isAvailable = false, error = e.message ?: "Couldn't save") }
                }
        }
    }

    private companion object {
        const val CHECK_DEBOUNCE_MS = 400L
    }
}
