package com.ezpnix.writeon.domain.model

data class Settings(
    val viewMode: Boolean = false,
    val automaticTheme: Boolean = true,
    val darkTheme: Boolean = false,
    var dynamicTheme: Boolean = false,
    var amoledTheme: Boolean = false,
    var minimalisticMode: Boolean = false,
    var extremeAmoledMode: Boolean = false,
    var isMarkdownEnabled: Boolean = true,
    var liveAttachmentPreview: Boolean = false,
    var screenProtection: Boolean = false,
    var encryptBackup: Boolean = false,
    var sortDescending: Boolean = true,
    var sortBy: Int = 0, // 0: CreatedAt, 1: UpdatedAt, 2: Title
    var vaultSettingEnabled: Boolean = false,
    var vaultEnabled: Boolean = false,
    var editMode: Boolean = false,
    var showOnlyTitle: Boolean = false,
    var termsOfService: Boolean = false,
    var cornerRadius: Int = 32,
    var predictiveBackEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    val fontSize: Float = 16f,
    val columnsCount: Int = 2,
    val hideVisibilityButtonWhenKeyboard: Boolean = true
)