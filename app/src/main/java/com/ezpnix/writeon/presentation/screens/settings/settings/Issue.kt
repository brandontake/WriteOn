package com.ezpnix.writeon.presentation.screens.settings.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.theme.appCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueScreen(navController: NavController, settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val items = (1..6).map { context.getString(R.string.issue_item, it) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.select_an_item)) })
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            items(items.size) { index ->
                GridItem(itemName = items[index], onClick = {
                })
            }
        }
    }
}

@Composable
fun GridItem(itemName: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clickable(
                // Bare `clickable` gives TalkBack no idea what this control does or that it is a
                // button; the role and the label come from the card's own text.
                role = Role.Button,
                onClickLabel = itemName,
                onClick = onClick
            ),
        shape = appCornerShape(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(16.dp)
                .height(100.dp)
        ) {
            Text(
                text = itemName,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                // The card is filled with colorScheme.primary, so the text has to use the colour the
                // theme designates as readable on top of it. A hardcoded Color.White looked fine in
                // light mode and became unreadable in dark mode, where primary is a pale mint and
                // white text on it sits around 1.2:1 contrast.
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
