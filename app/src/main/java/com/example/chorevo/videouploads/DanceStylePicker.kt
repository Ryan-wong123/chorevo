package com.example.chorevo.videouploads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.chorevo.dancelibrarylist.STANDARD_DANCE_STYLES
import com.example.chorevo.dancelibrarylist.closestStandardStyle
import com.example.chorevo.dancelibrarylist.danceTypeKey

private const val OTHER_STYLE = "Other…"

/**
 * Dance style chooser: a list of standard styles, plus "Other…" for a custom
 * style. A custom entry that is a near-miss of a standard style ("Hiphp")
 * offers the standard spelling, so the feed's style chips stay tidy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanceStylePicker(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "danceTypeField",
    enabled: Boolean = true
) {
    val isStandard = STANDARD_DANCE_STYLES.any { danceTypeKey(it) == danceTypeKey(value) }
    var custom by rememberSaveable { mutableStateOf(value.isNotBlank() && !isStandard) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (!custom) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (enabled) expanded = it }
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text("Dance Type") },
                    placeholder = { Text("Choose a style") },
                    leadingIcon = { Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    shape = MaterialTheme.shapes.small,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                        .testTag(testTag)
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    STANDARD_DANCE_STYLES.forEach { style ->
                        DropdownMenuItem(
                            text = { Text(style) },
                            onClick = {
                                onValueChange(style)
                                expanded = false
                            },
                            modifier = Modifier.testTag("danceStyle_${danceTypeKey(style)}")
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(OTHER_STYLE) },
                        onClick = {
                            custom = true
                            expanded = false
                            onValueChange("")
                        },
                        modifier = Modifier.testTag("danceStyle_other")
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                label = { Text("Dance Type") },
                placeholder = { Text("e.g. Voguing") },
                leadingIcon = { Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(20.dp)) },
                shape = MaterialTheme.shapes.small,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(testTag)
            )
            val suggestion = closestStandardStyle(value)?.takeIf { it != value }
            if (suggestion != null) {
                AssistChip(
                    onClick = {
                        onValueChange(suggestion)
                        custom = false
                    },
                    label = { Text("Did you mean $suggestion?") },
                    modifier = Modifier.testTag("danceStyleSuggestion")
                )
            }
            TextButton(onClick = { custom = false; onValueChange("") }) {
                Text("Pick from the list instead")
            }
        }
    }
}
