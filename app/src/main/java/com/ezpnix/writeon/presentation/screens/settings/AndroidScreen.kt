package com.ezpnix.writeon.presentation.screens.settings

import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.ezpnix.writeon.R
import com.ezpnix.writeon.presentation.screens.settings.model.SettingsViewModel
import com.ezpnix.writeon.presentation.screens.settings.settings.shapeManager
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel
) {
    val stat = StatFs(Environment.getDataDirectory().absolutePath)
    val totalStorage = formatStorage(stat.totalBytes)
    val availableStorage = formatStorage(stat.availableBytes)

    val deviceInfo = listOf(
        stringResource(R.string.device_manufacturer) to Build.MANUFACTURER,
        stringResource(R.string.device_model) to Build.MODEL,
        stringResource(R.string.device_brand) to Build.BRAND,
        stringResource(R.string.device_device) to Build.DEVICE,
        stringResource(R.string.device_board) to Build.BOARD,
        stringResource(R.string.device_hardware) to Build.HARDWARE,
        stringResource(R.string.device_cpu_abi) to Build.SUPPORTED_ABIS.joinToString(),
        stringResource(R.string.device_android_version) to Build.VERSION.RELEASE,
        stringResource(R.string.device_sdk_level) to Build.VERSION.SDK_INT.toString(),
        stringResource(R.string.device_security_patch) to (Build.VERSION.SECURITY_PATCH ?: stringResource(R.string.unknown)),
        stringResource(R.string.device_storage) to stringResource(
            R.string.storage_free_of_total,
            availableStorage,
            totalStorage
        ),
        stringResource(R.string.device_display) to Build.DISPLAY,
        stringResource(R.string.device_host) to Build.HOST,
        stringResource(R.string.device_bootloader) to Build.BOOTLOADER,
        stringResource(R.string.device_fingerprint) to Build.FINGERPRINT,
        stringResource(R.string.device_build_id) to Build.ID,
        stringResource(R.string.device_build_time) to formatBuildTime(Build.TIME),
        stringResource(R.string.device_radio_version) to (Build.getRadioVersion() ?: stringResource(R.string.unknown))
    )

    SettingsScaffold(
        settingsViewModel = settingsViewModel,
        title = stringResource(id = R.string.android),
        onBackNavClicked = { navController.navigateUp() }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = shapeManager(
                    isBoth = true,
                    radius = settingsViewModel.settings.value.cornerRadius
                ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    deviceInfo.forEach { (label, value) ->
                        Text(
                            text = "$label: $value",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

fun formatStorage(bytes: Long): String {
    val df = DecimalFormat("#.##")
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "${df.format(gb)} GB"
        mb >= 1 -> "${df.format(mb)} MB"
        else -> "${df.format(kb)} KB"
    }
}

fun formatBuildTime(time: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(time))
}