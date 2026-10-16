package com.example.chorevo.firebase

import com.example.chorevo.firebase.Repository.UsernameRepository
import com.example.chorevo.firebase.ViewModel.UsernameViewModel
import com.example.chorevo.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsernameViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: UsernameRepository
    private lateinit var vm: UsernameViewModel

    @Before
    fun setUp() {
        repo = mockk()
        coEvery { repo.suggest("Ryan Wong") } returns "ryan_wong"
        coEvery { repo.isAvailable(any()) } returns Result.success(true)
        coEvery { repo.isAvailable("taken") } returns Result.success(false)
        vm = UsernameViewModel(repo)
    }

    @Test
    fun start_withoutUsername_fillsInAFreeSuggestion() = runTest {
        vm.start(current = null, displayName = "Ryan Wong")
        advanceUntilIdle()
        assertEquals("ryan_wong", vm.uiState.value.input)
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun setInput_normalisesAndChecksAvailability() = runTest {
        vm.start(current = "old_name", displayName = null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canSave) // keeping the current name is fine

        vm.setInput("ab")
        assertNotNull(vm.uiState.value.problem)
        assertFalse(vm.uiState.value.canSave)

        vm.setInput("@Taken")
        advanceUntilIdle()
        assertEquals("taken", vm.uiState.value.input)
        assertEquals(false, vm.uiState.value.isAvailable)
        assertFalse(vm.uiState.value.canSave)
    }

    @Test
    fun save_claimsTheHandleAndReportsIt() = runTest {
        coEvery { repo.claim("uid1", "new_name", "old_name") } returns Result.success("new_name")
        vm.start(current = "old_name", displayName = null)
        vm.setInput("new_name")
        advanceUntilIdle()

        var saved: String? = null
        vm.save("uid1") { saved = it }
        advanceUntilIdle()

        coVerify { repo.claim("uid1", "new_name", "old_name") }
        assertEquals("new_name", saved)
    }

    @Test
    fun save_failureShowsTheError() = runTest {
        coEvery { repo.claim(any(), any(), any()) } returns Result.failure(IllegalStateException("@new_name is taken"))
        vm.start(current = null, displayName = "Ryan Wong")
        advanceUntilIdle()
        vm.setInput("new_name")
        advanceUntilIdle()

        vm.save("uid1") { }
        advanceUntilIdle()

        assertEquals("@new_name is taken", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isSaving)
    }
}
