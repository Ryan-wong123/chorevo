package com.example.chorevo.dancelibrarylist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.chorevo.ui.theme.ChorevoTheme
import com.example.chorevo.ui.theme.PillShape

/**
 * TikTok-style discovery bar drawn over the top of the feed: For You /
 * Trending / New tabs with a search button, and a row of dance-type chips.
 * The search button swaps the tabs for a search field.
 */
@Composable
fun DiscoveryBar(
    filter: DiscoveryFilter,
    categories: List<DanceCategory>,
    /** Shows a small spinner on the selected tab while its first page loads. */
    isLoading: Boolean,
    onSelectTab: (FeedTab) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchOpen by rememberSaveable { mutableStateOf(filter.query.isNotEmpty()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(top = 4.dp, bottom = 12.dp)
            .testTag("discoveryBar"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedContent(
            targetState = searchOpen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "discoverySearch"
        ) { open ->
            if (open) {
                SearchField(
                    query = filter.query,
                    onQueryChange = onQueryChange,
                    onClose = {
                        onQueryChange("")
                        searchOpen = false
                    }
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width(48.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FeedTab.entries.forEach { tab ->
                            FeedTabLabel(
                                tab = tab,
                                selected = filter.tab == tab,
                                loading = filter.tab == tab && isLoading,
                                onClick = { onSelectTab(tab) }
                            )
                        }
                    }
                    IconButton(
                        onClick = { searchOpen = true },
                        modifier = Modifier.testTag("searchButton")
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
                    }
                }
            }
        }

        if (categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "all") {
                    CategoryChip(
                        label = "All",
                        selected = filter.categoryKey == null,
                        onClick = { onSelectCategory(null) },
                        tag = "categoryChip_all"
                    )
                }
                items(categories, key = { it.key }) { category ->
                    CategoryChip(
                        label = category.label,
                        selected = filter.categoryKey == category.key,
                        onClick = { onSelectCategory(category.key) },
                        tag = "categoryChip_${category.key}"
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedTabLabel(tab: FeedTab, selected: Boolean, loading: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        label = "tabColor"
    )
    val underline by animateDpAsState(if (selected) 22.dp else 0.dp, label = "tabUnderline")

    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("feedTab_${tab.name}"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tab.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = color
            )
            if (loading) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = Color.White
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(underline)
                .height(3.dp)
                .clip(PillShape)
                .background(ChorevoTheme.colors.accent)
        )
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    val colors = ChorevoTheme.colors
    val background by animateColorAsState(
        if (selected) colors.accent else Color.Black.copy(alpha = 0.35f),
        label = "chipBackground"
    )
    Box(
        modifier = Modifier
            .clip(PillShape)
            .background(background)
            .border(1.dp, if (selected) Color.Transparent else Color.White.copy(alpha = 0.25f), PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.onAccent else Color.White
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(PillShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), PillShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        "Search dances, styles, creators",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                    cursorBrush = SolidColor(ChorevoTheme.colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("searchField")
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag("searchClose")) {
            Icon(Icons.Filled.Close, contentDescription = "Close search", tint = Color.White)
        }
    }
}
