package com.example.chorevo.core.routes

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.chorevo.dancelibrarylist.DanceLibraryListView
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel

/** Renders the home feed where users browse dance routines. */
@Composable
fun HomeRoute(
    uid: String,
    onPickDance: (DanceLibraryModel) -> Unit,
    onBack: () -> Unit,
    vm: DanceLibraryListViewModel
) {
    DanceLibraryListView(
        onPickDance = onPickDance,
        onBack = onBack,
        vm = vm,
        uid = uid
    )
}
