package com.ezpnix.writeon.core.util

import kotlinx.coroutines.sync.Mutex

/**
 * R3-04/R4-04: the one ordering lock for "read the latest note, change one part, write it back".
 *
 * Home cards, the launcher widget and the editor's split preview all mutate the same row from
 * different compositions, and each used to hold its own mutex (or none). Two quick checkbox taps -
 * or a tap racing a card title edit - could both read before either write, so the last full-row
 * write silently dropped the other change. Sharing one process-wide mutex makes the read-modify-
 * write sequences serial regardless of which UI started them.
 *
 * R4-04: the flag-only statements (pin/tag/trash are single-column SQL) take the same lock. A card
 * read that ran before such a statement would otherwise write the whole row back with the old
 * flag; serialising the two makes the flag write either visible to the card's read or applied after
 * its write, and both orders keep it.
 *
 * Lives in `core` because the domain layer's note writes take it too; a presentation package would
 * make `NoteUseCase` depend on a screen.
 *
 * R5-04: the editor's save commit takes it as well (inside the Vault lock, preserving the
 * Vault -> note-body order). Without that, a card read that started before a save could write its
 * old body back after the save landed, dropping the text just typed.
 */
internal object NoteBodyEditLock {
    val mutex = Mutex()
}
