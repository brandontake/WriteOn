import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
}

// Optional release signing: create WriteOn/keystore.properties with
//   storeFile=../writeon-upload.jks
//   storePassword=...
//   keyAlias=upload
//   keyPassword=...
// A release artifact MUST be signed with the real key. CQ-02: when the file is absent,
// assembleRelease/bundleRelease fail instead of silently shipping a debug-signed APK - that build
// cannot overwrite an installed release and masks a broken release configuration. For local
// convenience the failure can be opted out of explicitly with -PallowDebugSigning=true, which
// signs with the debug key but also suffixes versionName with "-debugsigned" and logs a warning.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreAvailable = keystorePropertiesFile.exists()
val allowDebugSigning = providers.gradleProperty("allowDebugSigning")
    .map { it.toBoolean() }
    .orElse(false)
    .get()
val keystoreProperties = Properties().apply {
    if (releaseKeystoreAvailable) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

if (!releaseKeystoreAvailable && allowDebugSigning) {
    logger.warn(
        "WARNING: keystore.properties is missing and -PallowDebugSigning=true was passed. " +
            "The release artifact will be signed with the DEBUG key and its versionName will " +
            "end in -debugsigned. Do not distribute this artifact."
    )
}

android {
    namespace = "com.ezpnix.writeon"
    // Android 17 / API 37.2: the current Compose BOM declares minCompileSdk 37.1, and AGP 9
    // exposes the minor API level through the new compileSdk spec DSL. targetSdk stays 36 so the
    // runtime behaviour contract of the 2.1 release does not change with this toolchain bump.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.ezpnix.writeon"
        // Android 13 (API 33) is the floor from 2.1 on: it lets the app drop its pre-33
        // compatibility branches and rely on the Android 13 permission model (POST_NOTIFICATIONS,
        // per-app languages) being present on every supported device.
        minSdk = 33
        // Google Play requires new submissions to target API 36 from 2026-08-31.
        targetSdk = 36
        versionCode = 17
        versionName = "2.1.0"
        // TST-01: the device-test entry point. AGP uses this for connectedDebugAndroidTest; the
        // tests under app/src/androidTest cover the parts that need real hardware (Android
        // Keystore, media encryption), which Robolectric cannot model.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The segmented editor (attachment lines rendered as blocks) is still experimental. The
        // user-facing switch in Settings > Behaviour must be on as well; this flag is the
        // compile-time kill switch that lets a build ship without the code path at all.
        buildConfigField("boolean", "FEATURE_ATTACHMENT_LIVE_RENDER", "true")
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Ship only the locales the app actually has translations for (res/values + res/values-zh +
    // res/values-ja). AGP 8.13 deprecates `resourceConfigurations`; `localeFilters` is the
    // replacement and feeds the same per-language resource stripping.
    androidResources {
        localeFilters += listOf("en", "zh", "ja")
    }

    signingConfigs {
        if (releaseKeystoreAvailable) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CQ-02: never fall back to the debug key by accident. With keystore.properties the
            // real release key signs the artifact; without it the build fails below unless the
            // caller explicitly opted into -PallowDebugSigning=true, in which case the artifact is
            // deliberately marked in its versionName so it cannot be mistaken for a real release.
            signingConfig = if (releaseKeystoreAvailable) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            if (!releaseKeystoreAvailable) {
                versionNameSuffix = "-debugsigned"
            }
        }

        debug {
            // R8 runs for the debug variant as well. The debug APK is the one that gets
            // side-loaded for testing, and unminified it shipped every AndroidX/Compose class
            // plus all 5 000+ material-icons-extended icons (~21 MB against ~4 MB minified).
            // The app has no debug-only code path (`BuildConfig.DEBUG` is never read), so the
            // shipped behaviour is identical to release; only the build takes longer. The debug
            // mapping file lands in app/build/outputs/mapping/debug/mapping.txt.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                // Kotlin module metadata is only needed for reflective access to Kotlin
                // top-level declarations (kotlin-reflect), which this app does not use.
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin/**"
            )
        }
        // Keep native libraries uncompressed so the loader can map them straight from the APK,
        // and so they stay 16 KB page aligned (Android 15+ devices with 16 KB pages).
        jniLibs {
            useLegacyPackaging = false
        }
    }
    buildToolsVersion = "36.0.0"

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    bundle {
        language {
            // AAB language splits: Play delivers only the device locale.
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }

    lint {
        // AGP 9 introduced LocalContextGetResourceValueCall. The project has ~150 pre-existing
        // context.getString call sites in composables (mostly Toast/error text captured at
        // composition). They are configuration-aware concerns, not build errors; keep them visible
        // as warnings rather than failing the release gate. Migrating them to stringResource /
        // LocalResources is a separate, mechanical follow-up.
        warning += "LocalContextGetResourceValueCall"
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged resources/assets instead of a device's. Required by
            // the Room migration tests in both directions: MigrationTestHelper serves the
            // exported schema JSON from assets, and Robolectric needs the merged config to boot.
            isIncludeAndroidResources = true
            // Robolectric on JDK 17+ (this build runs on 21) needs these opened up to reach
            // internal JDK APIs; without them the sandbox fails before any test runs.
            all { test ->
                test.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
            }
        }
    }
}

// Kotlin 2.3 removed the string-based kotlinOptions.jvmTarget; the compilerOptions DSL is the
// supported form and keeps bytecode on Java 17.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// CQ-02: a release artifact without a real signing key is refused at execution time, not at
// configuration time - debug builds and unit tests must keep working on a clean checkout that has
// no keystore.properties. Adding the check as a `doFirst` on the packaging tasks covers both
// assembleRelease and bundleRelease (including invoking packageRelease directly); the action
// captures only constants so it stays configuration-cache compatible.
if (!releaseKeystoreAvailable && !allowDebugSigning) {
    val guardedReleaseTasks = setOf(
        "assembleRelease",
        "bundleRelease",
        "packageRelease",
        "packageReleaseBundle",
        "signReleaseBundle"
    )
    tasks.configureEach {
        if (name in guardedReleaseTasks) {
            val guardedTaskName = name
            doFirst {
                throw GradleException(
                    "Refusing to build '$guardedTaskName': keystore.properties is missing, so the " +
                        "release artifact would be signed with the debug key and could never " +
                        "replace an installed release. Create keystore.properties " +
                        "(storeFile/storePassword/keyAlias/keyPassword) in the project root, or " +
                        "pass -PallowDebugSigning=true to build a deliberately debug-signed " +
                        "artifact (its versionName gets the -debugsigned suffix)."
                )
            }
        }
    }
}

// Room's MigrationTestHelper reads "<database canonical name>/<version>.json" out of the assets
// Robolectric serves. For local tests AGP points those at the *main* merged assets and never
// merges unit-test source sets (there is no mergeDebugUnitTestAssets task - a test source set
// assets.srcDir is simply ignored), so app/schemas has to be staged into that directory from
// here. Hooking mergeDebugAssets keeps the JSON out of every shipped APK: release builds merge
// their own directory and never run this task. The schemas directory is declared as an input so
// the merge (and with it the staging) re-runs whenever the JSON changes; afterwards the staged
// files are part of the task's own output snapshot. AGP creates its variant tasks after the
// build script is evaluated, hence configureEach, and the action captures only plain Files
// because task actions may not reference the Project under the configuration cache.
tasks.configureEach {
    if (name == "mergeDebugAssets") {
        val schemasDir = file("$projectDir/schemas")
        val stagedAssetsDir = layout.buildDirectory
            .dir("intermediates/assets/debug/mergeDebugAssets").get().asFile
        inputs.dir(schemasDir).withPropertyName("migrationSchemaJson")
        doLast {
            val schemaRoot = schemasDir.toPath()
            schemasDir.walkTopDown().filter { it.isFile }.forEach { source ->
                val target = stagedAssetsDir.toPath().resolve(schemaRoot.relativize(source.toPath()))
                Files.createDirectories(target.parent)
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

// Room writes the generated schema DDL here instead of under build/. The location has to sit
// outside build/ so the JSON survives a clean and can be committed — that record is what makes an
// offline MigrationTest possible.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.glance)
    implementation(libs.coil.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.biometric.ktx)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compile)
    implementation(libs.hilt.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.calendar)
    implementation(libs.compose.core)
    implementation(libs.automatic.backup)
    implementation(libs.gson)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.security)
    implementation(libs.okhttp)
    implementation(libs.process.phoenix)
    implementation(libs.tink)
    testImplementation(libs.junit)
    // Room migration tests: schema fixtures (MigrationTestHelper), the Robolectric runtime that
    // hosts them, and androidx.test's InstrumentationRegistry the helper needs for its assets.
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // TST-01: device tests under app/src/androidTest. The runner and AndroidJUnit4 come from
    // androidx.test; ApplicationProvider (androidx.test:core) constructs the classes under test
    // directly, so no Hilt test graph is needed.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
}
