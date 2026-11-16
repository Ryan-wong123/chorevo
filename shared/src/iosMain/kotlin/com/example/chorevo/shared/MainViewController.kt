package com.example.chorevo.shared

import androidx.compose.ui.window.ComposeUIViewController

/** Called from the iOS app's SwiftUI wrapper to host [App]. */
fun MainViewController() = ComposeUIViewController { App() }
