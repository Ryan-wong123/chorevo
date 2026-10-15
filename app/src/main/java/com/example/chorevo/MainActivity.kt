package com.example.chorevo

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import com.example.chorevo.core.ViewModel.AppViewModel
import com.example.chorevo.core.ViewModel.ThemeMode
import com.example.chorevo.core.share.ShareLinks
import com.example.chorevo.ui.theme.ChorevoTheme
import com.routes.AppRouter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Obtain the same AppViewModel instance that AppRouter uses so we can
    // forward the deep-link danceId before the Compose tree is built.
    private val appViewModel: AppViewModel by viewModels()

    /** Sets up the app UI when the main activity is created. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle a deep link that cold-started the app.
        // (Warm-start / already-running case is handled in onNewIntent below.)
        handleDeepLinkIntent(intent)

        setContent {
            val themeModeFlow = remember {
                appViewModel.uiState.map { it.themeMode }.distinctUntilChanged()
            }
            val themeMode by themeModeFlow
                .collectAsStateWithLifecycle(initialValue = appViewModel.uiState.value.themeMode)
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            // Status/navigation bar icons follow the app theme, not the system one.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        AndroidColor.TRANSPARENT,
                        AndroidColor.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        LIGHT_NAV_SCRIM,
                        DARK_NAV_SCRIM
                    ) { darkTheme }
                )
                onDispose {}
            }

            ChorevoTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier) {
                    AppRouter(appViewModel = appViewModel)
                }
            }
        }
    }

    private companion object {
        // Same scrims the platform uses for 3-button navigation.
        val LIGHT_NAV_SCRIM = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_NAV_SCRIM = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    /**
     * Called when the app is already running (launchMode="singleTask") and a
     * share link is tapped again.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a dance share link (see [ShareLinks.parseDanceId]) out of the
     * incoming intent and forwards the ID to [AppViewModel] so it can load and
     * display the correct dance once auth is confirmed.
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return

        ShareLinks.parseDanceId(uri)?.let(appViewModel::openDanceById)
    }
}
