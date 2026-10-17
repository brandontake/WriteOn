# WriteOn v1.9

> versionName `1.9` · versionCode `10` · baseline v1.8 · built 2026-10-17
> Full change list (Chinese): [`v1.9变动.md`](https://github.com/brandontake/WriteOn/blob/main/v1.9变动.md)
> Upstream project: [3zpnix/WriteOn](https://github.com/3zpnix/WriteOn) · this fork: [brandontake/WriteOn](https://github.com/brandontake/WriteOn)

## New features

- **App lock** — biometric / device credential / 3-digit PIN, backed by
  PBKDF2-HMAC-SHA256 (100,000 iterations + 16-byte random salt) with the derived
  key held in an Android Keystore AES-256-GCM `EncryptedSharedPreferences`.
  5 consecutive failures locks the app for 30 minutes; after 20 minutes past
  expiry the PIN must be re-verified so a timeout can't silently unlock it.
- **Encrypted notebook (vault)** — note titles and bodies are encrypted
  per-note with AES-256-GCM before they reach the database; packaged DAO
  wrappers keep search, ordering and tagging logic intact. Vault entries use a
  separate password-locked database so the plaintext of a locked note is never
  written twice.
- **Save as image** — editor overflow action replaced "share as text" with a
  Compose `GraphicsLayer` render of the whole note card, written to
  `Pictures/WriteOn` via `MediaStore` (Android 10+ scoped storage +
  `MediaScannerConnection`). 3,600 px wide JPEG, styled like the in-app card.
- **Predictive back** — opt-in edge swipe now follows the gesture with a
  follow + slide preview and can be cancelled before release.

## Interaction and layout fixes

- **App lock can silently vanish after an update** — `SettingsModel`'s init
  coroutine collected `termsOfService` in a scope that was never returned, so
  the lock gate could sit off the bottom button and a stale checkbox could
  re-enable it. Split into three independent coroutines, each fetching once.
- **Settings page could fail to save entirely** — `SettingsUseCase` threw
  `IllegalArgumentException` on one field's unmarshalled type, aborting the
  whole batch. Now warns per field and skips only that one.
- **Back gesture from the expanded FAB exited the app** — screen-local
  `BackHandler` never sees the system gesture; only `PredictiveBackHandler`
  does, and the navigation host registered after `MainActivity` won the
  `OnBackPressedDispatcher`. A dedicated `overlayBackHandler` now wins over the
  navigation handler whenever a home overlay is expanded.
- **Hardware-bitmap crash on image save** — the RenderNode-backed capture
  produced a hardware bitmap, which a software `Canvas` cannot draw
  (`IllegalArgumentException: Software rendering doesn't support hardware
  bitmaps`). The capture layer now uses `CompositingStrategy.Offscreen`, and
  the JPEG writer falls back to an ARGB_8888 copy.
- Vault note icons in the list are measured without infinite height
  constraints, which previously threw `IllegalStateException`.
- Search placeholder no longer stretches; the field actually expands when
  activated, and text is vertically centered in both states.
- Round corners follow the app-wide corner setting everywhere.
- Floating action button shows **pin** and **delete** when trash is open;
  clear-all selection added.
- Note cards, bottom bar and toolbar margins were measured against real button
  widths instead of fixed dp, so icons no longer sit in the margin.
- Back button clears the selection when items are selected, instead of
  exiting.
- Theme follows the system dark mode by default.

## Verification

- `compileDebugKotlin`, `assembleDebug`, `assembleRelease` — all pass.
- `lintVitalRelease` — **No issues found.**
- Release APK is signed with a dedicated upload key
  (`CN=Brandontake, OU=WriteOn, O=WriteOn`), **not** a debug key.
- 4 ABIs: arm64-v8a, armeabi-v7a, x86, x86_64 · minSdk 26 · targetSdk 36.
- Note: the interaction and image-export fixes in this release have been
  build-verified but **not yet verified on a physical device**.

## Install

Download `app-release.apk` below, allow installation from your file manager,
then install. This is a fork, so it installs side by side with the upstream
app — your notes stay separate.

## License

GPL-3.0, same as upstream. Modifications © 2026 Brandontake.