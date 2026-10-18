# Write On: Simple Notepad ✅
- Last Updated: 10/18/2026

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
- [x] Font Size Changeable
- [x] Custom Font Import & Application
- [ ] Cross Platform Client

## Done in v1.10
- [x] **Bump version to v1.10** (`versionCode 11`, `versionName "1.10"`).
- [x] **Modernized Desktop Widgets (Android 12+ & HyperOS).**
  - Upgraded widget provider declarations (`notes.xml`, `add_note.xml`) with `targetCellWidth`/`targetCellHeight`, `minResizeWidth`/`minResizeHeight`, and `maxResizeWidth`/`maxResizeHeight` enabling free-form arbitrary resizing.
  - Implemented 24dp rounded corners (`cornerRadius(24.dp)`) with `appWidgetBackground()` and Material 3 `surface`/`primaryContainer` background colors for seamless launcher card integration.
  - Resolved `viewMode` persistence and isolation so placing or configuring widgets never alters the main app's list/grid layout.
- [x] **Custom Font Selection & Navigation Flow.** Added interactive confirmation dialog upon font import, allowing users to return immediately to Home or remain in settings, ensuring imported typography takes effect smoothly.
- [x] **Home Note Grid Initial Scroll Fix.** Enforced viewport to anchor at the top (`scrollToItem(0)`) upon initial notes loading or data-source hydration, preventing inverted or bottom-anchored offsets when notes are populated.
- [x] **Synchronized GitHub Root Directory Tree Commits.** Updated CI workflow, build configurations, and catalog descriptors across root items (`.github`, `gradle`, `metadata`, `build.gradle.kts`, `settings.gradle.kts`) to ensure repository code view reflects the latest v1.10 releases.
- [x] **Custom Font Lifecycle & Real-time Reactivity Fixed.** Managed via `NoteFontManager._fontState` reactive Compose state; custom typography now persists seamlessly across app navigation and configuration changes without reverting to default.
- [x] **Audio Deletion & Garbage Collection.** Automatically cleans up referenced AAC recording files from `filesDir/audios/` when notes are permanently deleted from Trash, or when recordings are removed during note editing.
- [x] **Last Modified Timestamp.** Added `updatedAt` tracking in `Note` entity with Room migration `MIGRATION_6_7`, displayed alongside creation date in note details modal.
- [x] **Diverse Note Sorting.** Independent sorting dimensions (Creation Time, Last Modified Time, Title) with dedicated Ascending/Descending toggle in Settings -> Tools.
- [x] **App Shortcuts Support.** Added Android App Shortcut to instantly create a new note directly from launcher long-press.
- [x] **Glance Desktop Widgets Stability & Grid Fix.**
  - Switched Glance widget rendering from RemoteViews `LazyColumn` to bounded `Column` with full scrollable fallback and robust try-catch protection to eliminate "Can't show content" RemoteViews failures.
  - Fixed `NotesWidgetActivity` overriding user's home grid/list view mode.
- [x] **Expanded Markdown Syntaxes & 5-item-per-page Formatting Toolbar.**
  - Added native support for Headings (`##`), Hyperlinks (`[title](url)` with in-app click-to-open and safe URL handling), Horizontal Dividers (`---`), Quotes (`>`), and Code Blocks (```` ````).
  - Redesigned the formatting toolbar pagination to strictly display 5 functional buttons per page between navigation arrows, preventing visual overflow and button congestion.
- [x] **Dynamic Color Theming for Flashcards.** Flashcard cards, input textfields and completion cards now dynamically sample MaterialTheme semantic tokens (`surfaceVariant`, `onSurfaceVariant`, `primary`), adapting naturally to wallpaper colors (Android 12+) and dark/light mode shifts.
- [x] **Save-as-image hardware bitmap conversion completed.** Hardware bitmaps safely converted to CPU ARGB_8888 via `raw.toArgb8888()` preventing `Software rendering doesn't support hardware bitmaps` crash.
- [x] **Back gesture collapse behavior fixed.** When Drawer, FAB, or selection was active, swipe back was ineffective or exiting. Handled via dynamic `OnBackPressedCallback` in `overlayBackHandler` within `HomeView` and `MainActivity`, ensuring Drawer closes first, then FAB collapses, before exiting to home.
- [x] **Dark theme cold-start bug fixed.** Theme initialization now correctly follows system dark theme immediately on first frame without flickering or needing toggle cycles.
- [x] **Full Material 3 Design Alignment.** Navigation Drawer, Settings screens, Cards, OutlinedCards (removed unknown elevation drop shadows in FlashcardScreen), TextButtons, and colorScheme tokens (primaryContainer/onPrimaryContainer) strictly MD3 compliant.
- [x] **Audio note insertion and in-app player.** Zero-overhead AAC recording and playback directly within note Markdown text using `?audio(filepath)`.
- [x] **Custom Font Support.** Import `.ttf`/`.otf` via system SAF, robust fallback preventing crashes, synced note rendering and image export.
- [x] **100% Bilingual localization.** 413 strings strictly paired between English and Simplified Chinese, zero hardcoded strings.
- [x] Cleaned up callback unregistration to `callback.isEnabled = false` matching `androidx.activity:activity:1.9.2` API contract.
- [x] Rebuilt with release keystore (`writeon-upload.jks`), `lintVitalRelease` passed with zero errors, SHA-256 verified.

## Open / Future
- [ ] Unit tests for the vault KDF, the atomic restore and the DAO transaction
- [ ] detekt / ktlint / CI gating

Contact me if you have any suggestions or feedbacks about the current/upcoming changes. Upstream: @3zpnix <ezpnix@proton.me>. This fork: @Brandontake <https://github.com/Brandontake/Writeon>

⚠️ The release keystore lives only in this workspace. Back it up — without it you cannot publish an update that replaces v1.10.
