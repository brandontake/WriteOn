package com.ezpnix.writeon.presentation.screens.settings.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.screens.settings.SettingsScaffold
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.theme.appCornerShape

@Composable
fun GuideScreen(navController: NavController, settingsViewModel: SettingsViewModel) {

    val faqList = listOf(
        stringResource(R.string.faq_q_what_is_writeon) to stringResource(R.string.faq_a_what_is_writeon),
        stringResource(R.string.faq_q_delete_note) to stringResource(R.string.faq_a_delete_note),
        stringResource(R.string.faq_q_recover_deleted) to stringResource(R.string.faq_a_recover_deleted),
        stringResource(R.string.faq_q_update_frequency) to stringResource(R.string.faq_a_update_frequency),
        stringResource(R.string.faq_q_report_issue) to stringResource(R.string.faq_a_report_issue),
        stringResource(R.string.faq_q_lost_data) to stringResource(R.string.faq_a_lost_data),
        stringResource(R.string.faq_q_feature_requests) to stringResource(R.string.faq_a_feature_requests),
        stringResource(R.string.faq_q_auto_backup_files) to stringResource(R.string.faq_a_auto_backup_files),
        stringResource(R.string.faq_q_open_source) to stringResource(R.string.faq_a_open_source),
        stringResource(R.string.faq_q_developer) to stringResource(R.string.faq_a_developer)
    )

    var expandedStates by remember { mutableStateOf(List(faqList.size) { false }) }

    SettingsScaffold(
        settingsViewModel = settingsViewModel,
        title = stringResource(R.string.guide_faq_title),
        onBackNavClicked = { navController.navigateUp() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(faqList) { index, (question, answer) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedStates = expandedStates.toMutableList().apply {
                                this[index] = !this[index]
                            }
                        },
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = appCornerShape()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = question,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            androidx.compose.material3.Icon(
                                imageVector = if (expandedStates[index]) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = stringResource(R.string.expand_collapse),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        AnimatedVisibility(visible = expandedStates[index]) {
                            Text(
                                text = answer,
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}
