package com.example.chorevo.core

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.outlined.EmojiEvents

enum class BottomNavItem(val label: String) {
    HOME("Home"),
    UPLOAD("Upload"),
    COMMUNITY("Forum"),
    LEADERBOARD("Score"),
    PROFILE("Profile")
}

/** Displays the main app sections so users can quickly switch between core features. */
@Composable
fun AppBottomNavBar(
    selected: BottomNavItem,
    onSelect: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
) {
    val pillShape = RoundedCornerShape(24.dp)

    Surface(
        modifier = modifier
            .padding(contentPadding)
            .clip(pillShape),
        shape = pillShape,
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        NavigationBar(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = selected == BottomNavItem.HOME,
                onClick = { onSelect(BottomNavItem.HOME) },
                icon = {
                    Icon(
                        imageVector = if (selected == BottomNavItem.HOME) Icons.Filled.Home else Icons.Outlined.Home,
                        contentDescription = "Home"
                    )
                },
                label = { BottomNavLabel(BottomNavItem.HOME.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            NavigationBarItem(
                selected = selected == BottomNavItem.UPLOAD,
                onClick = { onSelect(BottomNavItem.UPLOAD) },
                icon = {
                    Icon(
                        imageVector = if (selected == BottomNavItem.UPLOAD) Icons.Filled.CloudUpload else Icons.Outlined.CloudUpload,
                        contentDescription = "Upload"
                    )
                },
                label = { BottomNavLabel(BottomNavItem.UPLOAD.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            NavigationBarItem(
                selected = selected == BottomNavItem.LEADERBOARD,
                onClick = { onSelect(BottomNavItem.LEADERBOARD) },
                icon = {
                    Icon(
                        imageVector = if (selected == BottomNavItem.LEADERBOARD)
                            Icons.Filled.EmojiEvents
                        else
                            Icons.Outlined.EmojiEvents,
                        contentDescription = "Leaderboard"
                    )
                },
                label = { BottomNavLabel(BottomNavItem.LEADERBOARD.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            NavigationBarItem(
                selected = selected == BottomNavItem.COMMUNITY,
                onClick = { onSelect(BottomNavItem.COMMUNITY) },
                icon = {
                    Icon(
                        imageVector = if (selected == BottomNavItem.COMMUNITY) Icons.Filled.Forum else Icons.Outlined.Forum,
                        contentDescription = "Community"
                    )
                },
                label = { BottomNavLabel(BottomNavItem.COMMUNITY.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            NavigationBarItem(
                selected = selected == BottomNavItem.PROFILE,
                onClick = { onSelect(BottomNavItem.PROFILE) },
                icon = {
                    Icon(
                        imageVector = if (selected == BottomNavItem.PROFILE) Icons.Filled.Person else Icons.Outlined.Person,
                        contentDescription = "Profile"
                    )
                },
                label = { BottomNavLabel(BottomNavItem.PROFILE.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun BottomNavLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontSize = 11.sp
    )
}
