package com.example.chorevo.videouploads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.chorevo.dancelibrarylist.DanceLibraryModel

/** Icon for a video's visibility: a globe when public, a lock when private. */
internal fun visibilityIcon(visibility: String): ImageVector =
    if (visibility == DanceLibraryModel.VISIBILITY_PRIVATE) Icons.Outlined.Lock else Icons.Outlined.Public

/** Lets an uploader choose whether a video is public or private. */
@Composable
fun VideoVisibilitySelector(
    visibility: String,
    onVisibilityChange: (String) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isPrivate = visibility == DanceLibraryModel.VISIBILITY_PRIVATE

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Who can see this video?", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VisibilityChip(
                label = "Public",
                visibility = DanceLibraryModel.VISIBILITY_PUBLIC,
                selected = !isPrivate,
                enabled = enabled,
                accentColor = accentColor,
                onClick = onVisibilityChange
            )
            VisibilityChip(
                label = "Private",
                visibility = DanceLibraryModel.VISIBILITY_PRIVATE,
                selected = isPrivate,
                enabled = enabled,
                accentColor = accentColor,
                onClick = onVisibilityChange
            )
        }
        Text(
            text = if (isPrivate) {
                "Only you can see it, in your profile."
            } else {
                "Everyone can see it in the feed and via share links."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VisibilityChip(
    label: String,
    visibility: String,
    selected: Boolean,
    enabled: Boolean,
    accentColor: Color,
    onClick: (String) -> Unit
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = { onClick(visibility) },
        label = { Text(label) },
        leadingIcon = {
            Icon(visibilityIcon(visibility), contentDescription = null, modifier = Modifier.size(18.dp))
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accentColor.copy(alpha = 0.18f),
            selectedLabelColor = accentColor,
            selectedLeadingIconColor = accentColor
        ),
        modifier = Modifier.testTag("visibility_$visibility")
    )
}
