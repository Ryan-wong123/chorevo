package com.example.chorevo.core

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.chorevo.ui.theme.ChorevoTheme
import com.example.chorevo.ui.theme.PillShape

enum class BottomNavItem(val label: String) {
    HOME("Home"),
    UPLOAD("Upload"),
    COMMUNITY("Groups"),
    LEADERBOARD("Score"),
    PROFILE("Profile")
}

/** Height of the floating bar itself. */
val FloatingBarHeight: Dp = 64.dp

/** Gap between the floating bar and the bottom of the screen. */
val FloatingBarBottomMargin: Dp = 12.dp

/**
 * Space a full-bleed screen should leave at the bottom so its content sits
 * above the floating bar. Zero on screens without the bar.
 */
val LocalFloatingBarInset = staticCompositionLocalOf { 0.dp }

/** Lets immersive screens (the video feed) tuck the bar away while scrolling. */
@Stable
class FloatingBarState {
    var hidden by mutableStateOf(false)
}

val LocalFloatingBarState = staticCompositionLocalOf { FloatingBarState() }

/**
 * Floating, translucent pill navigation bar with a raised gradient upload
 * button in the centre (TikTok style).
 */
@Composable
fun AppBottomNavBar(
    selected: BottomNavItem,
    onSelect: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ChorevoTheme.colors

    Surface(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(FloatingBarHeight)
            .shadow(elevation = 18.dp, shape = PillShape, clip = false)
            .testTag("floatingBottomBar"),
        shape = PillShape,
        color = colors.glass,
        border = BorderStroke(1.dp, colors.glassBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            NavTab(BottomNavItem.HOME, selected, Icons.Filled.Home, Icons.Outlined.Home, "Home", onSelect)
            NavTab(
                BottomNavItem.LEADERBOARD, selected,
                Icons.Filled.EmojiEvents, Icons.Outlined.EmojiEvents, "Leaderboard", onSelect
            )
            UploadButton(
                selected = selected == BottomNavItem.UPLOAD,
                onClick = { onSelect(BottomNavItem.UPLOAD) }
            )
            NavTab(BottomNavItem.COMMUNITY, selected, Icons.Filled.Groups, Icons.Outlined.Groups, "Groups", onSelect)
            NavTab(BottomNavItem.PROFILE, selected, Icons.Filled.Person, Icons.Outlined.Person, "Profile", onSelect)
        }
    }
}

@Composable
private fun RowScope.NavTab(
    item: BottomNavItem,
    selected: BottomNavItem,
    selectedIcon: ImageVector,
    icon: ImageVector,
    contentDescription: String,
    onSelect: (BottomNavItem) -> Unit
) {
    val isSelected = item == selected
    val accent = ChorevoTheme.colors.accent
    val tint by animateColorAsState(
        targetValue = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navTint"
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(PillShape)
            .clickable(role = Role.Tab, onClick = { onSelect(item) })
            .testTag("bottomNav_${item.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(PillShape)
                .background(if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSelected) selectedIcon else icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Raised circular gradient "+" button for creating an upload. */
@Composable
private fun RowScope.UploadButton(selected: Boolean, onClick: () -> Unit) {
    val colors = ChorevoTheme.colors

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 44.dp)
                .shadow(elevation = if (selected) 2.dp else 10.dp, shape = PillShape, clip = false)
                .clip(PillShape)
                .background(colors.brandGradient)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("bottomNav_${BottomNavItem.UPLOAD.name}"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Upload",
                tint = colors.onGradient,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
