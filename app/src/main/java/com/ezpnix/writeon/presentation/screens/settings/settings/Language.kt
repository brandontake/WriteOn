package com.ezpnix.writeon.presentation.screens.settings.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavController
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.navigation.NavRoutes
import com.ezpnix.writeon.presentation.screens.settings.SettingsScaffold
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.screens.settings.widgets.ActionType
import com.ezpnix.writeon.presentation.screens.settings.widgets.ListDialog
import com.ezpnix.writeon.presentation.screens.settings.widgets.SettingsBox
import com.ezpnix.writeon.presentation.theme.NoteFontManager

@Composable
fun LanguageScreen(navController: NavController, settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var currentFontName by remember {
        mutableStateOf(NoteFontManager.getFontName(context) ?: context.getString(R.string.default_font_name))
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val fontName = NoteFontManager.importFont(context, uri)
                currentFontName = fontName
                Toast.makeText(
                    context,
                    context.getString(R.string.font_loaded_success, fontName),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.font_load_failed, e.message ?: "Invalid font"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    SettingsScaffold(
        settingsViewModel = settingsViewModel,
        title = stringResource(id = R.string.language),
        onBackNavClicked = { navController.navigateUp() }
    ) {
        LazyColumn {
            item {
                SettingsBox(
                    title = stringResource(id = R.string.language),
                    description = stringResource(id = R.string.language_description),
                    icon = Icons.Rounded.Translate,
                    radius = shapeManager(radius = settingsViewModel.settings.value.cornerRadius, isFirst = true),
                    actionType = ActionType.CUSTOM,
                    customAction = { onExit -> OnLanguageClicked(settingsViewModel) { onExit() } }
                )
            }
            item {
                SettingsBox(
                    isBig = true,
                    title = stringResource(id = R.string.font_size),
                    description = stringResource(id = R.string.enter_font_size_description),
                    icon = Icons.Rounded.FormatSize,
                    actionType = ActionType.CUSTOM,
                    radius = shapeManager(
                        radius = settingsViewModel.settings.value.cornerRadius
                    ),
                    customAction = {
                        showFontSizeDialog = true
                    }
                )
            }
            item {
                SettingsBox(
                    isBig = true,
                    title = stringResource(id = R.string.custom_font_title),
                    description = stringResource(id = R.string.current_font_label, currentFontName),
                    icon = Icons.Rounded.FontDownload,
                    actionType = ActionType.CUSTOM,
                    radius = shapeManager(
                        isLast = true,
                        radius = settingsViewModel.settings.value.cornerRadius
                    ),
                    customAction = {
                        if (currentFontName != context.getString(R.string.default_font_name)) {
                            // Already has custom font: offer reset or replace
                            NoteFontManager.resetDefaultFont(context)
                            currentFontName = context.getString(R.string.default_font_name)
                            Toast.makeText(context, context.getString(R.string.font_reset_success), Toast.LENGTH_SHORT).show()
                        } else {
                            fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "*/*"))
                        }
                    }
                )
                Spacer(modifier = Modifier.height(18.dp))
            }
        }

        if (showFontSizeDialog) {
            AlertDialog(
                onDismissRequest = { showFontSizeDialog = false },
                title = { Text(stringResource(id = R.string.select_font_size)) },
                text = {
                    Column {
                        val fontSizeOptions =
                            listOf(12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f)

                        fontSizeOptions.forEach { size ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        settingsViewModel.updateFontSize(size)
                                        showFontSizeDialog = false
                                    }
                                    .padding(1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = settingsViewModel.settings.value.fontSize == size,
                                    onCheckedChange = { settingsViewModel.updateFontSize(size) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "${size.toInt()} sp", fontSize = size.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showFontSizeDialog = false
                        repeat(2) {
                            navController.popBackStack(NavRoutes.Home.route, inclusive = false)
                        }
                        Toast.makeText(context, context.getString(R.string.tools_saved_exclaim), Toast.LENGTH_SHORT)
                            .show()
                    }) {
                        Text(stringResource(id = R.string.save_and_exit))
                    }
                }
            )
        }
    }
}

@Composable
private fun OnLanguageClicked(settingsViewModel: SettingsViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    val languages = settingsViewModel.getSupportedLanguages(context).toList()
    ListDialog(
        text = stringResource(R.string.language),
        list = languages,
        settingsViewModel = settingsViewModel,
        onExit = onExit,
        extractDisplayData = { it },
        initialItem = Pair(context.getString(R.string.system_language), second = ""),
        setting = { isFirstItem, isLastItem, displayData ->
            SettingsBox(
                isBig = false,
                title = displayData.first,
                radius = shapeManager(isFirst = isFirstItem, isLast = isLastItem, radius = settingsViewModel.settings.value.cornerRadius),
                actionType = ActionType.RADIOBUTTON,
                variable = if (displayData.second.isNotBlank()) {
                    AppCompatDelegate.getApplicationLocales()[0]?.language == displayData.second
                } else {
                    AppCompatDelegate.getApplicationLocales().isEmpty
                },
                switchEnabled = { if (displayData.second.isNotBlank()) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(displayData.second))
                    } else {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                    }
                }
            )
        }
    )
}