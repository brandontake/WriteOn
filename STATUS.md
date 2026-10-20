# Write On: Simple Notepad ✅
- Last Updated: 10/20/2026

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
- [x] **Trilingual localization (English / 简体中文 / 日本語).** 439 string keys present in all three `values`, `values-zh` and `values-ja` resource sets, key-for-key identical, zero hardcoded strings. Japanese was added end-to-end: `res/values-ja/strings.xml`, `xml/locales_config.xml` and `resourceConfigurations` in `app/build.gradle.kts`, so the language also appears in the per-app language picker (Settings → Language & Text).
- [x] **Widget note text vertically centred.** The Glance note block is wrapped in a centring `Box`, so short notes sit in the middle of the widget cell instead of being pinned to the top edge.
- [x] **Audio player overhaul.**
  - Draggable seek bar replacing the read-only progress indicator; dragging suspends ticker updates so the thumb does not fight the playback position.
  - Playback speed cycling 0.5× / 0.75× / 1× / 1.25× / 1.5× / 2× through `MediaPlayer.PlaybackParams`, re-applied on every `start()`.
  - Per-recording rename, persisted by path in `AudioLabelStore` so the label survives note edits and app restarts.
  - Save to folder through the Storage Access Framework, copying the AAC out of `filesDir/audios`.
  - Long recording names now ellipsize on one line instead of pushing the `mm:ss / mm:ss` label onto a second line.
- [x] Cleaned up callback unregistration to `callback.isEnabled = false` matching `androidx.activity:activity:1.9.2` API contract.
- [x] Rebuilt with release keystore (`writeon-upload.jks`), `lintVitalRelease` passed with zero errors, SHA-256 verified.

## v1.10 Security & Performance Hardening (Audit Pass)
- [x] **Battery: audio progress ticker 10 Hz → 4 Hz.** `GlobalAudioPlayer` posts every 250 ms (`PROGRESS_INTERVAL_MS`), so the playing `AudioPlayerCard` recomposes roughly four times per second instead of ten, and `currentPositionMs` / `durationMs` use `mutableIntStateOf` to avoid a boxed value on every tick.
- [x] **Battery: runaway audio ticker after a playback error.** Added `MediaPlayer.setOnErrorListener`; a failed stream used to leave `isPlaying` true, so the main-looper ticker re-posted itself forever. Completion now stops immediately as well, releasing the codec and native buffers.
- [x] **Privacy + battery: MediaRecorder leaked with the microphone open.** `stopRecording()` and `cancelRecording()` now always `reset()` + `release()`; previously an exception from `stop()` (recording too short) skipped `release()`, leaving the mic hot and the privacy indicator lit. Recording is 64 kbps mono instead of 128 kbps stereo, and filenames carry milliseconds so two takes in the same second no longer overwrite each other.
- [x] **Battery: infinite pulse animation in the recorder dialog.** The ripple drove an `infiniteRepeatable` transition even when idle, forcing a redraw every frame for as long as the dialog was open; it is now only composed while recording.
- [x] **Memory: unbounded image export.** The capture is clamped to `MAX_EXPORT_PIXELS` (8 MP ≈ 32 MB), `writeJpeg` downscales defensively, and both the ARGB_8888 copy and the flatten buffer are recycled. A long note used to need 1080 × 100000 × 4 B twice over.
- [x] **Persistence: tags wiped and edits lost on save.** `EditModel.saveNote` re-reads the row when the database flow has not emitted yet, instead of writing `tags = emptyList()` over the stored ones; erasing every character of an existing note is now persisted instead of leaving the old text behind; the dead `isPinned` comparison was removed.
- [x] **Security: vault encryption with an empty passphrase.** `EncryptionHelper.encrypt` is guarded by `check`, and `NoteUseCase.encryptNote` refuses to store ciphertext when no passphrase is set — such a note could never be decrypted and would silently disappear from the list. The widget path (`NoteCrypto` + `EncryptedNoteDao`) is untouched.
- [x] **Crash: `Flow.first()` on a missing row.** `updatePinStatus`, `deleteNoteById`, `permanentlyDeleteNoteById` and `addTagToNote` now use `firstOrNull() ?: return`; a stale note id used to throw `NoSuchElementException` inside `viewModelScope` and kill the process.
- [x] **Persistence: automatic backup never ran.** `ExistingPeriodicWorkPolicy.REPLACE` → `KEEP`; the scheduler is re-invoked on every settings change, so the one-day periodic job was cancelled and re-queued before it could ever fire.
- [x] **Sync: duplicated WebDAV jobs.** Backup and restore share the unique work name `WebDAVSync` with `ExistingWorkPolicy.KEEP`, so a restore can no longer race a backup that is still reading the database, and a double tap cannot enqueue two uploads.
- [x] **Privacy: Android Auto Backup pulled in recordings.** `<application>` now declares `android:fullBackupContent="@xml/backup_rules"` (the file existed but was never referenced) and both rule sets exclude `audios/`, matching the already-excluded databases.
- [x] **Verified as already sound.** Widgets declare no `updatePeriodMillis`, so the launcher never wakes the app periodically; the lock-out countdown loops exit as soon as the deadline passes; the navigation drawer exposes a `contentDescription` on every icon button and relies on row labels for the drawer items themselves.

## Done in v2.0 (in progress)
- [x] **Bump version to v2.0** (`versionCode 12`, `versionName "2.0"`). 2.0 rather than 1.11: the body-format change below rewrites every stored note, which is a breaking change for anyone downgrading, and the plan was scoped as 2.0 from the start.
- [x] **Body format standardised (priority 1 of the 2.0 plan).** The private 1.x tokens `!(/data/.../pic.jpg)` and `?audio(/data/.../rec.m4a)` are gone from new writes:
  - New `attachments` table (`Attachment` entity: `attachment-id`, `note-id`, `type` image/audio, `path`, `caption`, `created_at`) with `MIGRATION_7_8` creating the table, its `note-id` index, **and** rewriting every existing body.
  - Bodies now carry standard Markdown links: `![](attachment://a1b2c3d4)` for images, `[Recording 20261018-2245](attachment://e5f6a7b8)` for recordings. The id, not the absolute file path, is what the body stores, so renaming or moving a file no longer silently breaks a note.
  - `AttachmentSyntax` owns the syntax (regexes, id generation, escaping of `[`/`]` in captions), `AttachmentRegistry` is the process-wide `id -> path` map, `AttachmentFiles` deletes files only inside `filesDir/audios` and the app's external files dir.
  - `LegacyBodyMigration.rewrite` is the single, idempotent upgrade pass: it skips fenced code blocks, reuses one id per path, and is used by the migration **and** by every note save. Saving is what upgrades a vault note: the 7 -> 8 migration cannot decrypt a password-protected note, so it leaves it alone and the editor rewrites it on first save (restored backups and pasted legacy text are caught by the same pass).
  - `NoteCrypto.openOrNull` was added so the migration can tell "not sealed" from "cannot decrypt" and leave an unreadable row untouched instead of writing garbage back.
  - Parser: `AttachmentImageProcessor` / `AttachmentAudioProcessor` run before the 1.x processors, which stay as compatibility extensions; the lists are now produced by one `defaultMarkdownLineProcessors()`, so the widget uses the same parsing as the editor instead of its own copy.
  - Rendering resolves ids through the registry in both `MarkdownText` and `WidgetText`; an unresolved id shows a localised "attachment unavailable" placeholder in the app and nothing in the widget. `AudioPlayerCard` takes a `caption` fallback from the attachment row, while a user-set name in `AudioLabelStore` still wins.
  - Insertion uses `insertImageAttachment` / `insertAudioAttachment`, which place the token on its own line (the line parser requires a whole-line token) and register the file for immediate preview. Saving reconciles three sets: pending rows that survived, rows the note owned but the text dropped (row + file deleted), and pending rows the user removed before the note was ever saved. Rows for a brand-new note are written under note id 0 and claimed by the note Room just created; the claim only touches unclaimed rows so it can never steal another note's attachment.
  - Deleting a note (trash or permanently) now removes its attachment rows and the files they point at, with the 1.x audio regex kept as a fallback for vault notes that were never opened.
- [x] **Portable backup copy includes attachments.** `writePortableDatabaseCopy` exports the `attachments` table alongside the notes it already exported.
- [x] **Multi-page long-image export (priority 2).** A note taller than the 8 MP budget used to be measured against it, so everything past the cut was silently dropped. \NoteImageCapture\ now takes \pageHeightPx\/\pageIndex\ and keeps the column at its natural height inside a fixed window, shifted by the page offset; a first pass lays the note out without recording anything and its height decides the page count. Pages are saved as \<name>_1of3\, \<name>_2of3\… one bitmap alive at a time, and the toast reports how many were written.
- [x] **Share-sheet intake (priority 3).** \MainActivity\ is \singleTask\ and accepts \ACTION_SEND\ (text, image) and \ACTION_SEND_MULTIPLE\ (images). \ShareIntake\ parks the payload: a cold start opens a new note directly, a warm one navigates through a counter, and \EditViewModel.init\ consumes it — text becomes the body, each image is copied into app storage and inserted as an attachment (claimed by the note on first save).
- [x] **Selection-aware formatting (priority 4).** \pplyFormat(prefix, suffix)\ wraps the selection when there is one and otherwise inserts the markers with the caret between them. Toolbar page two is now bold, italic, strikethrough, highlight and inline code, all going through it; \InlineCode\ renders monospace on a light grey run.
- [x] **Trash search and select-all semantics (priority 5).** \ilterNotes\ is shared with the home screen, the trash screen has a search field, and \llSelected\ is computed over the *visible* list. Select-all adds what is visible without dropping rows selected under another query, and an empty result says so instead of looking like an empty trash.
- [x] **WebDAV conflict detection (priority 6).** Uploads read the server's ETag with \HEAD\ and send \If-Match\; a changed ETag or a 412 keeps a timestamped \…conflict-yyyyMMdd-HHmmss.zip\ copy on the server and fails the run rather than overwriting the other device's backup. Restore now closes the live database first (the connection moved to a shared companion field, so a worker can actually close the one the app is using), and clears the stored ETag so the next upload does not see a false conflict. *Loose end:* the conflict copy is only recorded in SharedPreferences; the settings screen does not surface it yet.
- [x] **Undo the last save (priority 7).** Saving records the row it is replacing; a bar offers ten seconds to take it back. The undo writes the old row through \NoteUseCase.restoreNote\, which skips the re-encryption \ddNote\ performs, so a vault note comes back exactly as it was; a note the save created is deleted together with its attachments.

## Open / Future
- [ ] Unit tests for the vault KDF, the atomic restore and the DAO transaction
- [ ] detekt / ktlint / CI gating

Contact me if you have any suggestions or feedbacks about the current/upcoming changes. Upstream: @3zpnix <ezpnix@proton.me>. This fork: @Brandontake <https://github.com/Brandontake/Writeon>

⚠️ The release keystore lives only in this workspace. Back it up — without it you cannot publish an update that replaces v1.10.
