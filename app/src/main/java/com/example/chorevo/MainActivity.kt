package com.example.chorevo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.chorevo.core.ViewModel.AppViewModel
import com.example.chorevo.ui.theme.ChorevoTheme
import com.routes.AppRouter
import dagger.hilt.android.AndroidEntryPoint

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
            ChorevoTheme {
                Surface(modifier = Modifier) {
                    AppRouter(appViewModel = appViewModel)
                }
            }
        }
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
     * Parses https://chorevo.app/dance/<danceId> out of the incoming intent
     * and forwards the ID to [AppViewModel] so it can load and display the
     * correct dance once auth is confirmed.
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return

        // Expect exactly:  https://chorevo.app/dance/<danceId>
        val segments = uri.pathSegments          // ["dance", "<id>"]
        if (uri.host == "chorevo.app"
            && segments.size == 2
            && segments[0] == "dance"
        ) {
            val danceId = segments[1]
            if (danceId.isNotBlank()) {
                appViewModel.openDanceById(danceId)
            }
        }
    }
}