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
    // Off by default: `$...$` and `$$...$$` are ordinary prose until the user opts in from
    // Settings > Behavior, so an old note that happens to contain a dollar sign renders unchanged.
    var renderLatex: Boolean = false,
    // On by default since 2.0.2: the experimental gate is now the BuildConfig kill switch only.
    // Users who explicitly turned it off keep their stored value.
    var liveAttachmentPreview: Boolean = true,
    var screenProtection: Boolean = false,
    // SEC-03: new installs write encrypted database backups by default. A user who already turned
    // the switch off keeps the stored `false`; only a library with vault notes overrides it (see
    // BackupEncryptionPolicy), because a plaintext archive would expose decrypted vault media.
    var encryptBackup: Boolean = true,
    var sortDescending: Boolean = true,
    var sortBy: Int = 0, // 0: CreatedAt, 1: UpdatedAt, 2: Title
    var vaultSettingEnabled: Boolean = false,
    var vaultEnabled: Boolean = false,
    var showOnlyTitle: Boolean = false,
    var termsOfService: Boolean = false,
    var cornerRadius: Int = 32,
    var predictiveBackEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    val fontSize: Float = 16f,
    val columnsCount: Int = 2
)