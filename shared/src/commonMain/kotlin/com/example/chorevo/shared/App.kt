package com.example.chorevo.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val BrandTeal = Color(0xFF00B8C8)
private val StageBackground = Color(0xFF0B0F14)

/**
 * Entry point of the shared UI, hosted by both the Android app and the iOS app.
 * For now it is a placeholder that proves the shared module builds and runs on
 * both platforms; real screens move here from `app/` step by step.
 */
@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme(primary = BrandTeal)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(StageBackground)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Chorevo", style = MaterialTheme.typography.displayMedium, color = BrandTeal)
            Text(
                "Running on ${platformName()}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
    }
}

/** "Android" or "iOS", provided by each platform. */
expect fun platformName(): String
