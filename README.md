<div align="center">
  <img src="metadata/en-US/images/icon.png" width="192" height="192" alt="App Icon" />

# WriteOn: Simple Notepad ✅
A clean, intuitive note-taking app with *Material You* design — open source and privacy-respecting.

[<img src=".github/github.png" alt="Get it on GitHub" height="55">](https://github.com/Brandontake/Writeon/releases)
&nbsp;
[<img src=".github/fdroid.png" alt="Get it on F-Droid" height="55">](https://f-droid.org/en/packages/com.ezpnix.writeon/)
</div>

---

<div align="center">
  <img src="metadata/en-US/images/phoneScreenshots/1.png" width="30%" alt="Screenshot 1"/>
  <img src="metadata/en-US/images/phoneScreenshots/2.png" width="30%" alt="Screenshot 2"/>
  <img src="metadata/en-US/images/phoneScreenshots/3.png" width="30%" alt="Screenshot 3"/>
</div>

---

## ✨ About

WriteOn is an offline-first notepad for Android. Notes, tags and attachments live in a local Room
database — there is no account, no sync service and no analytics. The interface follows Material 3
and picks up the wallpaper colours on Android 12+, with optional AMOLED and "extreme AMOLED" themes
for people who want a true black background.

It is a small app by design: two APK variants of a few megabytes, no runtime permission prompts, no
foreground services, and only the permissions the features actually need.

---

## 👋 Features

**Writing**
- Markdown editing with an expanded 5-item-per-page formatting toolbar (headings `##`, hyperlinks `[](url)`, horizontal dividers `---`, quotes `>`, code blocks, checkboxes, bullet/ordered lists, bold / italic / underline / strikethrough, highlights, images, mic recording and timestamps)
- Note attachments, audio recording & in-app playback, audio auto-cleanup on deletion
- Dynamic theming support across notes and flashcards, customizable font size and persistent custom local font import (.ttf/.otf), grid or column layout, minimalistic mode, adjustable corner radius
- Diverse sorting dimensions (Creation Time, Last Modified Time, Title) with dedicated Ascending/Descending controls
- Note details dialog with Last Modified Time tracking and Word/Character stats
- Launcher App Shortcut for quick note creation

**Privacy**
- App lock with biometric authentication, PIN (PBKDF2-HMAC-SHA256, 600 000 iterations) or pattern, a 5-attempt lockout and screen protection
- Note titles and bodies can be encrypted with an Android Keystore AES-256-GCM key before they reach the database
- The optional note-vault password uses PBKDF2-HMAC-SHA256 with a per-note random salt (120 000 iterations), with read-only compatibility for notes written by earlier builds
- Home-screen widgets honour the app lock — with a lock configured they show a placeholder instead of note text

**Extras**
- Home-screen widgets (quick add + note list), built-in calendar, calculator, scratchpad and flashcards
- Save a note as an image, export to TXT/Markdown, and local backup / restore (with an optional password)

---

## 🔒 Privacy & permissions

- **No runtime permission prompts.** The app never asks for storage, notification or location access at runtime; the notification permission is only declared so that a user who grants it in system settings can pin a note to the notification shade.
- **Everything stays on the device.** Note data lives in a local database; the optional cloud backup is a user-configured WebDAV target and nothing is uploaded anywhere by default.
- **Encrypted at rest.** When encryption is enabled, note titles and bodies are sealed with a per-device Keystore key. The note id, pin flag, timestamps and tags stay readable so that ordering, pinning and the tag filter keep working without extra queries — which also means **a database file copied off the device without "Export backup" cannot be read back**.
- **Backups are replaced atomically.** A restore stages the new database next to the live one, verifies its SQLite header, renames it into place and keeps the previous file as `.bak`. An interrupted or corrupt restore leaves the existing notes intact instead of deleting them.
- **Archive entries cannot escape the target directory.** Backup archives are extracted through a canonical-path check, so an entry named `../../databases/note-list.db` is rejected rather than written.
- **Locked means locked.** With an app lock configured, the home-screen widgets render a "open the app to view this note" placeholder instead of note text, and pinned-note notifications are marked private.
- **Permanent deletion asks first.** Removing notes from the recycle bin — in bulk or via long-press — is confirmed in a dialog.

---

## 📥 Download

- **GitHub Releases:** <https://github.com/Brandontake/Writeon/releases>
- **F-Droid:** <https://f-droid.org/en/packages/com.ezpnix.writeon/>

Both APK variants are minified and shrunk, so the download stays in the low single-digit megabytes.

---

## 🧱 Build from source

**Requirements:** JDK 21, an Android SDK with the API 36 platform and build-tools 36.0.0, and the
Gradle wrapper that ships in this repository (Gradle 8.9, AGP 8.7.0).

```bash
./gradlew assembleDebug      # debug APK  (R8 + resource shrinking, debug keystore)
./gradlew assembleRelease    # release APK (upload keystore when keystore.properties exists)
```

The project produces **APK files only** — there is no App Bundle task in the release flow.

Release signing is optional for local builds: create `keystore.properties` in the repository root with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`, and the release build type picks it up.
Without that file the release APK is signed with the debug keystore so that local builds stay
installable — that fallback is still there, but **v1.10 was signed with a real key**
(`CN=Brandontake, OU=WriteOn, O=WriteOn`).

Two things to get right when you create that file:

- **Write it without a BOM.** Windows PowerShell 5.1's `Set-Content -Encoding UTF8` emits a BOM, which
  `Properties.load()` folds into the first key name, so `storeFile` comes back `null` and the build dies
  with `path may not be null or empty string. path='null'`. Use `-Encoding ascii`, or write it from an
  editor that saves UTF-8 without a BOM.
- **`storeFile` is relative to the repository root**, not to `app/`, because `app/build.gradle.kts`
  resolves it with `rootProject.file(...)`. For a keystore at the repo root, use `writeon-upload.jks`,
  not `../writeon-upload.jks`.

Both `*.jks` and `keystore.properties` are gitignored — keep them that way, and back the keystore up
somewhere safe. Without it you cannot publish an update that replaces a shipped build.

### Toolchain notes

- `compileSdk` and `targetSdk` are pinned to **36 (Android 16)**, `minSdk` 26. AGP 8.7 is only tested
  up to `compileSdk` 35, so the build carries `android.suppressUnsupportedCompileSdk=36`; the planned
  upgrade is **AGP 8.11.x + Gradle 8.13** (AGP 8.9's compatibility line still reports a maximum API
  level of 35, while 8.11 is the first release that states 36). Two upgrade constraints found while
  checking it: Hilt 2.52's Gradle plugin does not support AGP 9.x (wait for Hilt ≥ 2.59) and Room
  2.6.1 → 2.7.0 hits a KSP2 failure on suspend-returning-`Unit` DAO methods — keep those two moves
  separate.
- Kotlin 2.0.20 with the Compose compiler plugin, KSP for Room and Hilt.

### What keeps the APK small

- R8 code shrinking/optimisation **and** resource shrinking are enabled for **both** build types; the
  unused icons of `material-icons-extended`, unused AndroidX classes and unreferenced resources are
  all removed (icons and library classes are only kept when a source file actually uses them).
- The resource configuration list limits the app to the `en` and `zh` translations that exist, so no
  library locale resources are packaged.
- No Material 2 dependency, no unused libraries, and lossless WebP artwork for the widget previews.
- No foreground services, no extra permissions and no native libraries beyond two tiny `.so` files
  shipped by AndroidX.

### Android 16 behaviour changes

Edge-to-edge is enforced and `windowOptOutEdgeToEdgeEnforcement` is ignored (the app never declared
it and already draws edge-to-edge with explicit insets). Predictive back is enabled for `targetSdk`
36: the app declares `android:enableOnBackInvokedCallback="true"` and offers a Predictive Back
toggle in **Settings → Colors & styles** that scales/fades the UI during the gesture. Native
libraries must be 16 KB page aligned — all packaged `.so` files (2 libraries × 4 ABIs) already load
with `p_align = 16384`.

Background work: the JobScheduler quota optimisations can delay or stop background jobs, and
`JobInfo#setImportantWhileForeground` is ignored from Android 16 (its getter returns `false`).
Automatic backups are periodic WorkManager work, so this changes *when* they run, not whether the
feature works; a WorkManager 2.10.x upgrade is queued for its new diagnostics
(`JobScheduler#getPendingJobReasons*`).

Play deadlines: new apps and updates must target API 36 since 2026-08-31 (extension requests until
2026-11-01), and from 2027-02-01 updates must support 16 KB page sizes — WriteOn already does.

### How the build is verified

Both variants were built on **2026-10-18** with `:app:assembleDebug` and `:app:assembleRelease` (offline,
JDK 21.0.12.1, Gradle 8.9). The figures below are from the **latest** release build, incorporating the
extended Markdown toolbars and dynamic theme flashcards:

| Variant | Size | applicationId | Notes |
| --- | --- | --- | --- |
| Release | **3.68 MB** (3,862,886 bytes) | `com.ezpnix.writeon` | full R8 optimisation + obfuscation; ships two baseline profiles (API 28–30, 31+); signed with the project's upload key (`CN=Brandontake, OU=WriteOn, O=WriteOn, C=US`) |

SHA-256 (Release): `CEC2B4631A55289D4591EC62CD2533F79828A327A65DC1F635859CBC6ADEF4D0`.

Both report `versionCode 11` / `versionName 1.10`, `minSdk 26`, `targetSdk 36`, only the `zh` + default
locales, and four ABIs.
and caught and fixed six real compiler errors; it also produced `app/schemas/…/6.json` from the Room
schema export.

Beyond the build: `aapt2 dump badging` on both APKs, `apksigner verify` on both, `aapt2 dump resources`
to confirm the new strings actually landed in the packaged resources, and the R8 mapping files in
`app/build/outputs/mapping/{debug,release}/mapping.txt`.

`lintVitalRelease` ran and reported **No issues found.** (report:
`app/build/intermediates/lint_vital_intermediate_text_report/release/lintVitalReportRelease/lint-results-release.txt`).

**Published:** [Release v1.10](https://github.com/brandontake/WriteOn/releases/tag/v1.10) carries exactly
this release APK (SHA-256 verified and signed).

---

## 🧩 Tech stack

Kotlin · Jetpack Compose (Material 3) · Navigation Compose · Hilt · Room · DataStore Preferences ·
Coil · Glance widgets · WorkManager · AndroidX Biometric · Security-Crypto · Tink + OkHttp (cloud
backup) · core-splashscreen.

---

## 📝 Changelog

See GitHub Releases for historical changes and release assets.

---

## 📢 Announcements

- *2025-09-04:* Sophomore year is right around the corner for me, decided to give out an update before it starts.
- *2025-06-01:* Thankfully, I had time to continue the v1.6 update, had fun with this version, thank you!
- *2025-05-09:* Small v1.5 update released — life’s hectic with job & university, so expect some slow updates.
- *2025-02-25:* Found time for v1.3 update — future updates might be delayed due to part-time job.
- *2025-01-19:* Taking a break to focus on other projects. Still planning v1.3!
- *2024-12-07:* University workload delaying updates — sorry!
- *2024-12-03:* v1.2 will be mostly bug fixes + features.
- *2024-11-19:* Android Studio issues — delays expected.
- *2024-08-26:* Android release coming soon to GitHub and F-Droid!

---

## 💬 Contact

- *Fork maintainer:* [@Brandontake](https://github.com/Brandontake) — <https://github.com/Brandontake/Writeon>
- *Upstream author:* [@3zpnix](https://github.com/3zpnix) — <https://github.com/3zpnix/WriteOn> — 3zpnix@proton.me
- *Upstream Twitter:* [@3zpnix](https://twitter.com/3zpnix)

---

## 🙋 Credits & Fork Notice

WriteOn was created by [@3zpnix](https://github.com/3zpnix) — an electronic engineering university student who makes small open-source apps and games. **This build is maintained by [@Brandontake](https://github.com/Brandontake)**: it is the personal project of a vibecoding beginner who forked the app, adjusted it and improved it purely for their own needs. Upstream authorship is credited in full, here and in the app's About screen.

---

## 🙏 Credits & Third-Party Code

WriteOn is built on top of other open-source projects. This section records exactly which parts of them are used.

### App lock (biometric / PIN / pattern)
- **[AndroidX Biometric](https://developer.android.com/jetpack/androidx/releases/biometric)** (`androidx.biometric:biometric:1.2.0-alpha05`) — `BiometricPrompt` and `BiometricManager` provide the fingerprint / face prompt and the availability check, including the device-credential fallback older devices need.
- **[AndroidX Security-Crypto](https://developer.android.com/jetpack/androidx/releases/security)** (`androidx.security:security-crypto:1.1.0`) — `EncryptedSharedPreferences` + `MasterKey` (AES-256-GCM backed by the Android Keystore) store the PIN / pattern material, so no credential is ever written in plain text.
- The lock UI and its logic (`LockManager`, `LockGate`, `LockScreen`, `LockSetupScreen`, `PinKeypad`, `PatternGrid`) are written for WriteOn: PBKDF2-HMAC-SHA256 with 600 000 iterations (the current OWASP MASTG floor for this KDF) and a 16-byte random salt, constant-time comparison, a 5-attempt / 30-second lockout and a 20-second re-lock grace period. No third-party lock-screen implementation was copied.

### Encrypted notes
- **Android Keystore** `AES-256-GCM` (`javax.crypto`) seals the title and body of a note with a per-device key before Room writes it to disk. Notes in the vault keep their own password-based ciphertext instead, and are stored untouched.
- **[AndroidX Room](https://developer.android.com/jetpack/androidx/releases/room)** — the generated DAO is wrapped rather than the schema being changed, so the database version, the migrations and all existing queries stay as they are.
- **Save as image** uses Jetpack Compose's `GraphicsLayer` capture (`rememberGraphicsLayer` → `toImageBitmap`) plus `MediaStore`, and reuses WriteOn's own Markdown renderer.

### Backups
- A backup is produced by reading the notes back through the decrypting DAO and writing them into a throwaway database, so a restored archive stays readable on any device, not just the one that created it. The optional backup password keeps using PBKDF2-HMAC-SHA256 (65 536 iterations) with AES-CBC, as before.
- Restoring an archive goes through `core/util/DatabaseRestore.kt`: archive members are resolved against the destination directory with a canonical-path check (rejecting `..` escapes), capped at 64 MiB per member, and the new database is staged, header-verified (`SQLite format 3`), renamed into place and the previous file kept as `.bak`. A failed rename puts the old database back.

### Predictive back & image export
- **[IdeaMemo](https://github.com/ldlywt/IdeaMemo)** (GPL-3.0) — consulted as a *design reference only* for its share-picture page (a card layout finished with a signature footer). No code was copied and the project's GPL license was not adopted into WriteOn; the capture pipeline remains WriteOn's own Compose `GraphicsLayer` implementation, and the signature footer is WriteOn-styled.
- The predictive-back preview itself (`PredictiveBackHost`) is written for WriteOn on top of `androidx.activity.compose.PredictiveBackHandler`, deliberately composed after the navigation host so its callback wins the `OnBackPressedDispatcher` and the toggle can hand plain back behaviour back when switched off.

### Security notes
- Note titles and bodies are encrypted at rest. The note id, the pin flag, timestamps and tags stay in plain text so that ordering, pinning and the tag filter keep working without extra queries.
- The Keystore key never leaves the device, so **an encrypted database file copied off a device without using "Export backup" cannot be read back.**
- The optional note vault derives its AES key with PBKDF2-HMAC-SHA256 over a per-note 16-byte random salt (120 000 iterations) and prefixes new ciphertext with `v2:`. Notes written before that change derived the key with a single SHA-256 pass; they are still decrypted on read through the old derivation, so upgrading does not lock anyone out. The lower iteration count compared with the screen lock is deliberate — this key is derived per note, and a long note list would otherwise stall on every decryption; results are cached in a small per-salt LRU.

---

## ⚠️ License
    WriteOn: Simple Notepad

    Copyright (C)2024 3zpnix
    Modifications Copyright (C)2026 Brandontake
    
    This software is free to use, modify, and redistribute under 
    the terms of the GNU General Public License, as published by the 
    Free Software Foundation. You may choose to use either version 3 of the License 
    or, at your option, any later version. The software is provided with the hope 
    that it will be useful, but it comes as is with no warranties, including 
    implied warranties of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
    For more details, please refer to the GNU General Public License.

    The above copyright notice, this permission notice, and the license must be included in all copies or substantial portions of the software.

    You can find a copy of the GNU General Public License v3 [here](https://www.gnu.org/licenses/)</p>
