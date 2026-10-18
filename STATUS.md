# Write On: Simple Notepad ✅
- Last Updated: 10/17/2026

## In Progress:
- [x] More markdown formats
- [x] Quick note export to txt
- [x] Updated auto-backup logic
- [x] Direct to translate button
- [x] Dropdown share button
- [x] Fixed partial image bug
- [x] Updated user interface
- [x] Internet search button
- [x] Updated resource strings
- [x] More optimizations
- [ ] Font Size Changeable
- [ ] Cross Platform Client

## Done since 10/16/2026 (v1.9 security audit batch)
- [x] Atomic database restore (staged + header-verified rename, `.bak` rollback) instead of delete-then-write
- [x] Zip Slip closed (canonical-path check + 64 MiB per-entry cap on both archive paths)
- [x] Home-screen widgets respect the app lock instead of rendering note text
- [x] Recycle-bin permanent deletion confirmed with a dialog
- [x] Note vault moved from a single SHA-256 pass to PBKDF2-HMAC-SHA256 + per-note salt (legacy notes still readable)
- [x] Pinned-note notifications marked private
- [x] Bulk note updates wrapped in a single Room transaction
- [x] WebDAV backup wired to the user's stored settings (was dead code)
- [x] Theme updates no longer write to disk / reschedule backups every frame
- [x] Lifecycle-aware state collection everywhere; scroll position survives Home ↔ Recycle bin
- [x] Markdown rendering merged per-character spans into styled ranges; parse results memoized
- [x] Accessibility roles added (Role.Button / Role.Checkbox) and a 1.2:1 contrast fix
- [x] Verified by a real build: `:app:compileDebugKotlin`, `:app:assembleDebug` (7.47 MB) **and** `:app:assembleRelease` (3.73 MB) — both APKs checked with `aapt2 dump badging` and `apksigner verify`
## Done since 10/17/2026 (interaction + attribution batch, `c1ffe93`)
- [x] Save-as-image no longer fails silently — the export layer was measured with empty `Constraints()` (0×0) and the resulting exception was swallowed, leaving the editor stuck in the saving state; it now measures real constraints, reports the reason, and deletes half-written `MediaStore` rows
- [x] Bottom-bar pill given room — padding 8 → 18 dp, button gap 12 → 16 dp
- [x] Focusing the search field activates the bar itself; the magnifier is no longer required
- [x] Back *gesture* with the quick-action bar expanded collapses the bar instead of finishing the activity (`HomeOverlayState` + `PredictiveBackHost.isOverlayOpen`)
- [x] Project moved to `Brandontake/Writeon` — repository, source-code, issue, terms and support links all updated
- [x] About screen credits both the upstream author (@3zpnix) and this fork's maintainer (@Brandontake, vibecoding beginner)
- [x] `lintVitalRelease` ran for the first time: **No issues found.**
- [x] Rebuilt both variants: debug 8.58 MB, release 3.73 MB, badging and signature re-checked
- [x] Pushed to `origin`: `7b4eb05..deee1f6`

## Done since 10/17/2026 (correction batch + release, `deee1f6`)
- [x] **Save-as-image root cause corrected.** It was never the 0×0 measurement — it was a hardware bitmap. The RenderNode-backed capture layer produces a hardware bitmap that a software `Canvas` cannot draw, throwing `IllegalArgumentException: Software rendering doesn't support hardware bitmaps`. Fixed with `CompositingStrategy.Offscreen` on the capture layer plus an ARGB_8888 software copy in the JPEG writer.
- [x] **Back gesture root cause corrected.** `OnBackPressedDispatcher` runs the *last* registered enabled callback, and the navigation host registers after `PredictiveBackHost` — so the navigation handler always won and the root route finished. A new `overlayBackHandler` now registers from `HomeView` while an overlay is expanded and takes precedence.
- [x] "Check for updates" pointed at the original author; now `ConnectionConst.RELEASES`. Issue link moved to `ConnectionConst.ISSUES`.
- [x] Generated a dedicated release keystore (`writeon-upload.jks`, RSA 4096, 10950 days, not committed); release APK is signed with `CN=Brandontake, OU=WriteOn, O=WriteOn` instead of the debug certificate
- [x] Published **GitHub Release v1.9** — <https://github.com/brandontake/WriteOn/releases/tag/v1.9>, asset `app-release.apk` 3,911,482 bytes, SHA-256 re-downloaded and matched

## Done since 10/18/2026 (v1.10 fix batch + release)
- [x] **Bump version to v1.10** (`versionCode 11`, `versionName "1.10"`).
- [x] **Save-as-image hardware bitmap conversion completed.** `raw.toSoftwareBitmapOrSelf()` previously caught exceptions inside its software `Canvas` copy, which caught the very same `IllegalArgumentException: Software rendering doesn't support hardware bitmaps` and returned the raw hardware bitmap, causing `writeJpeg` to immediately fail with identical error. Replaced with `Bitmap.copy(Bitmap.Config.ARGB_8888, false)` via `toArgb8888()`, the only valid public Android API to read hardware bitmaps on CPU.
- [x] **Back gesture collapse behavior fixed.** When TAB/overlay or selection was active, swipe back was ineffective or exiting. Replaced `PredictiveBackHost`'s overlay handling with direct `OnBackPressedCallback` in `overlayBackHandler` within `HomeView` registered dynamically on `LocalOnBackPressedDispatcherOwner`, ensuring it is the latest registered callback and collapses the FAB overlay (`fabExpanded = false; showDimBackground = false`) before clearing selection, while `PredictiveBackHost` safely yields.
- [x] Cleaned up callback unregistration to `callback.isEnabled = false` matching `androidx.activity:activity:1.9.2` API contract.
- [x] Rebuilt with release keystore (`writeon-upload.jks`), `lintVitalRelease` passed with zero errors, SHA-256 verified.

## Open since 10/17/2026
- [ ] **Device regression pass** — the three fixes above are build-verified only. Two earlier rounds failed precisely by treating "it compiles" as "it works", so these need a real device before v1.10.
- [ ] Unit tests for the vault KDF, the atomic restore and the DAO transaction
- [ ] detekt / ktlint / CI gating

Contact me if you have any suggestions or feedbacks about the current/upcoming changes. Upstream: @3zpnix <ezpnix@proton.me>. This fork: @Brandontake <https://github.com/Brandontake/Writeon>

⚠️ The release keystore lives only in this workspace. Back it up — without it you cannot publish an update that replaces v1.9.
