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
- [ ] Push `a5d2017` and `c1ffe93` to `origin` — both are still local only

## Open since 10/17/2026
- [ ] A real release keystore — without `keystore.properties` the release APK falls back to the debug certificate, so it cannot be published or replace a shipped build
- [ ] Unit tests for the vault KDF, the atomic restore and the DAO transaction
- [ ] detekt / ktlint / CI gating
- [ ] Device or emulator regression pass (lock screen, widgets, image export, media writes, back gesture)

Contact me if you have any suggestions or feedbacks about the current/upcoming changes. Upstream: @3zpnix <ezpnix@proton.me>. This fork: @Brandontake <https://github.com/Brandontake/Writeon>
