package com.ezpnix.writeon.presentation.components.markdown

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ezpnix.writeon.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun formatTime(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
}

/** "0.5x", "1x", "1.25x" - no trailing zeros for the whole rates. */
private fun formatSpeed(value: Float): String =
    if (value == value.toInt().toFloat()) "${value.toInt()}x" else "${value}x"

private fun nextSpeed(current: Float): Float {
    val options = GlobalAudioPlayer.speedOptions
    val index = options.indexOfFirst { it == current }
    return options[if (index < 0) options.indexOf(1f) else (index + 1) % options.size]
}

@Composable
fun AudioPlayerCard(
    audioUri: String,
    radius: Int = 16,
    modifier: Modifier = Modifier,
    /**
     * Caption stored with the 2.0 attachment row. Shown when the user has not renamed the memo,
     * so a migrated recording reads "20261018-2245" instead of the generic "Voice Memo".
     */
    caption: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isCurrent = GlobalAudioPlayer.currentPlayingPath == audioUri
    val isPlaying = isCurrent && GlobalAudioPlayer.isPlaying
    val progress = if (isCurrent) GlobalAudioPlayer.progress else 0f
    val currentPositionMs = if (isCurrent) GlobalAudioPlayer.currentPositionMs else 0
    val durationMs = if (isCurrent) GlobalAudioPlayer.durationMs else 0
    val speed = GlobalAudioPlayer.speed

    val file = remember(audioUri) { File(audioUri) }
    val storedLabel = AudioLabelStore.label(context, audioUri)
    val displayName = storedLabel
        .ifBlank { caption.orEmpty().trim() }
        .ifBlank { stringResource(R.string.audio_recording_label) }


    // While the thumb is held the card must show the finger position, not the position the ticker
    // reports - otherwise every 250 ms update would yank the thumb back under the finger.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    // The name suggested to the system file picker follows the user's own label, so a renamed memo
    // is saved under the name they chose rather than the opaque recording timestamp. An unlabelled
    // memo falls back to its own file name instead of the localized "Voice Memo" default, because
    // that placeholder cannot tell two saved recordings apart. Characters that FAT/exFAT cards
    // reject are replaced, and the stem is capped so the picker never sees an over-long name.
    val exportName = remember(storedLabel, file) {
        val stem = storedLabel
            .ifBlank { file.nameWithoutExtension }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .trimEnd('.')
            .take(80)
            .trim()
            .ifBlank { "audio" }
        if (stem.endsWith(".m4a", ignoreCase = true)) stem else "$stem.m4a"
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("No output stream for $uri")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (saved) R.string.audio_saved else R.string.audio_save_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape((radius / 2).coerceAtLeast(12).dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = { GlobalAudioPlayer.playOrPause(audioUri) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) {
                        stringResource(R.string.audio_pause)
                    } else {
                        stringResource(R.string.audio_play)
                    },
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // The name yields to the elapsed time instead of the other way round: a long
                    // recording name used to push "00:03 / 01:12" onto a second line.
                    Text(
                        text = displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (durationMs > 0) {
                            "${formatTime(currentPositionMs)} / ${formatTime(durationMs)}"
                        } else {
                            formatTime(currentPositionMs)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = if (dragging) dragValue else progress,
                        onValueChange = {
                            dragging = true
                            dragValue = it
                        },
                        onValueChangeFinished = {
                            GlobalAudioPlayer.seekTo(dragValue)
                            dragging = false
                        },
                        enabled = isCurrent && durationMs > 0,
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    )

                    TextButton(
                        onClick = { GlobalAudioPlayer.setPlaybackSpeed(nextSpeed(speed)) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = formatSpeed(speed),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.audio_more),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.audio_save_to_folder)) },
                                onClick = {
                                    showMenu = false
                                    saveLauncher.launch(exportName)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.audio_rename)) },
                                onClick = {
                                    showMenu = false
                                    showRename = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRename) {
        var draft by remember { mutableStateOf(displayName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.audio_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.audio_rename_hint)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        AudioLabelStore.rename(context, audioUri, draft)
                        showRename = false
                    }
                ) {
                    Text(stringResource(R.string.audio_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
