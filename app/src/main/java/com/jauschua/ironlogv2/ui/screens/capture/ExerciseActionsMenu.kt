package com.jauschua.ironlogv2.ui.screens.capture

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Small overflow (⋮) menu shown next to an exercise name in [CaptureScreen], offering
 * Swap/Skip for that exercise's remaining sets. Only rendered by the caller when the
 * exercise has at least one not-yet-logged, not-yet-skipped set (nothing to act on
 * otherwise) -- this composable itself does not gate visibility.
 */
@Composable
fun ExerciseActionsMenu(
    onSwap: () -> Unit,
    onSkip: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Exercise actions")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("Swap exercise") }, onClick = { expanded = false; onSwap() })
        DropdownMenuItem(text = { Text("Skip remaining sets") }, onClick = { expanded = false; onSkip() })
    }
}
