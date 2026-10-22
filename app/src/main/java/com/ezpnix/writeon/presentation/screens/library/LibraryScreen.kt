package com.ezpnix.writeon.presentation.screens.library

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.ezpnix.writeon.R
import com.ezpnix.writeon.domain.model.Attachment
import com.ezpnix.writeon.presentation.components.markdown.AttachmentRegistry
import com.ezpnix.writeon.presentation.components.markdown.AudioPlayerCard
import com.ezpnix.writeon.presentation.components.markdown.MissingAttachment
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.theme.appCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The drawer's Library section: every live image or recording across the notes, by type.
 *
 * The same screen serves both rows - the route argument picks the type. It is the management
 * surface the notes themselves cannot offer: a grid of pictures and a stack of recordings, each
 * with the actions the rendered versions have (save a copy, delete to the recycle bin). Deleting
 * never edits only this screen: the owning note loses the line at the same moment and the file
 * moves to the trash with it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    settingsViewModel: SettingsViewModel,
    navController: NavController,
    type: String,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cornerRadius = settingsViewModel.settings.value.cornerRadius
    val isImage = type != Attachment.TYPE_AUDIO
    val attachments by remember(type) { viewModel.attachmentsFlow(type) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var sheetAttachment by remember { mutableStateOf<Attachment?>(null) }
    var pendingDelete by remember { mutableStateOf<Attachment?>(null) }
    var pendingSave by remember { mutableStateOf<Attachment?>(null) }
    var fullScreenAttachment by remember { mutableStateOf<Attachment?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/*")
    ) { uri ->
        val attachment = pendingSave
        pendingSave = null
        if (uri == null || attachment == null) return@rememberLauncherForActivityResult
        val source = attachment.path
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        File(source).inputStream().use { input -> input.copyTo(output) }
                    } ?: error("No output stream for $uri")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (saved) R.string.attachment_saved_to_folder else R.string.attachment_save_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isImage) R.string.library_images else R.string.library_audio
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.mediumTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        when {
            attachments.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(
                        if (isImage) R.string.library_empty_images else R.string.library_empty_audio
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            isImage -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(attachments, key = { it.id }) { attachment ->
                    LibraryImageCell(
                        attachment = attachment,
                        cornerRadius = cornerRadius,
                        onOpen = { fullScreenAttachment = attachment },
                        onLongPress = { sheetAttachment = attachment }
                    )
                }
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                items(attachments, key = { it.id }) { attachment ->
                    LibraryAudioCell(
                        attachment = attachment,
                        cornerRadius = cornerRadius,
                        onDelete = { pendingDelete = attachment }
                    )
                }
            }
        }
    }

    // Long-press actions for a picture; the recording card carries its own overflow menu.
    sheetAttachment?.let { attachment ->
        ModalBottomSheet(onDismissRequest = { sheetAttachment = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.attachment_actions),
                    style = MaterialTheme.typography.titleMedium
                )
                Button(
                    onClick = {
                        sheetAttachment = null
                        pendingSave = attachment
                        saveLauncher.launch(File(attachment.path).name.ifBlank { "image.jpg" })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = appCornerShape()
                ) {
                    Text(stringResource(R.string.audio_save_to_folder))
                }
                OutlinedButton(
                    onClick = {
                        sheetAttachment = null
                        pendingDelete = attachment
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = appCornerShape(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }

    pendingDelete?.let { attachment ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.attachment_delete)) },
            text = { Text(stringResource(R.string.attachment_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.trashAttachment(attachment.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    fullScreenAttachment?.let { attachment ->
        val path = AttachmentRegistry.path(attachment.id)
        if (path != null) {
            Dialog(
                onDismissRequest = { fullScreenAttachment = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                onClick = { fullScreenAttachment = null },
                                onLongClick = {}
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = path,
                            contentDescription = stringResource(R.string.attachment_open_image),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryImageCell(
    attachment: Attachment,
    cornerRadius: Int,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val path = AttachmentRegistry.path(attachment.id)
    val shape = RoundedCornerShape((cornerRadius / 2).coerceAtLeast(8).dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        if (path == null) {
            MissingAttachment(radius = cornerRadius / 2, modifier = Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = path,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun LibraryAudioCell(
    attachment: Attachment,
    cornerRadius: Int,
    onDelete: () -> Unit
) {
    val path = AttachmentRegistry.path(attachment.id)
    if (path == null) {
        MissingAttachment(
            radius = cornerRadius,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
    } else {
        AudioPlayerCard(
            audioUri = path,
            radius = cornerRadius,
            caption = attachment.caption.takeIf { it.isNotBlank() },
            onDelete = onDelete
        )
    }
}
