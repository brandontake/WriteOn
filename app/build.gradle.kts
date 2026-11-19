import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
}

// Optional release signing: create WriteOn/keystore.properties with
//   storeFile=../writeon-upload.jks
//   storePassword=...
//   keyAlias=upload
//   keyPassword=...
// When the file is absent the release build falls back to the debug keystore so that
// `assembleRelease`/`bundleRelease` keep working locally.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.ezpnix.writeon"
    // Pinned on purpose (no dynamic versions): Android 16 = API 36.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ezpnix.writeon"
        minSdk = 26
        // Google Play requires new submissions to target API 36 from 2026-08-31.
        targetSdk = 36
        versionCode = 16
        versionName = "2.0.4"
        // The segmented editor (attachment lines rendered as blocks) is still experimental. The
        // user-facing switch in Settings > Behaviour must be on as well; this flag is the
        // compile-time kill switch that lets a build ship without the code path at all.
        buildConfigField("boolean", "FEATURE_ATTACHMENT_LIVE_RENDER", "true")
        vectorDrawables {
            useSupportLibrary = true
        }

        // https://developer.android.com/guide/topics/resources/app-languages#gradle-config
        // Ship only the locales the app actually has translations for (res/values + res/values-zh
        // + res/values-ja). NOTE: the previous `resourceConfigurations.plus(listOf("en"))` was a
        // no-op - `plus` on a List returns a new list that was discarded, so no resource stripping
        // happened at all.
        resourceConfigurations += setOf("en", "zh", "ja")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
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
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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
    kotlinOptions {
        jvmTarget = "17"
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
}
