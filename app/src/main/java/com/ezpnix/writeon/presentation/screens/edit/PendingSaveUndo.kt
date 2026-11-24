package com.ezpnix.writeon.presentation.screens.edit

/**
 * The one save that can still be taken back.
 *
 * The editor's undo bar used to live inside the editor composition, so leaving the screen threw
 * away both the bar and the only way to undo the save. The global snackbar host outlives the
 * editor, but the action it invokes cannot depend on the editor's `viewModelScope` either - that
 * scope is cancelled when the destination is popped. This holder keeps the database half of the
 * undo alive for exactly as long as the snackbar is on screen, and the editor registers a fresh
 * action after every successful save.
 *
 * R2-04: the slot carries the note it belongs to. A Vault eviction used to clear whatever action
 * happened to be registered - possibly another editor's plain note - and a later failed save could
 * leave the Vault note's own action un-clearable because the save point was already gone.
 */
object PendingSaveUndo {

    private var action: (suspend () -> Unit)? = null
    private var ownerNoteId: Int? = null
    private var generation = 0L

    /**
     * Registers [action] and returns the generation that owns it.
     *
     * R4-06: the snackbar's timeout or manual dismissal has no other way to tell "this bar is
     * gone, so the action is unreachable" from "a newer save replaced it". The generation lets the
     * dismissal clear only the slot it was shown for; without it, a late callback for an old bar
     * could clear a fresh registration and leave a Vault plaintext closure alive the other way.
     */
    fun register(noteId: Int, action: suspend () -> Unit): Long {
        this.action = action
        this.ownerNoteId = noteId
        return ++generation
    }

    /** Runs and consumes the pending action. False when there was nothing left to undo. */
    suspend fun undo(): Boolean {
        val pending = action ?: return false
        action = null
        ownerNoteId = null
        pending()
        return true
    }

    /**
     * Clears the slot only when it belongs to [noteId].
     *
     * Used by a Vault eviction and by the editor's own "undo save" button, so neither can cancel
     * another note's snackbar action.
     */
    fun clearOwnedBy(noteId: Int) {
        if (ownerNoteId == noteId) clear()
    }

    /**
     * R4-06: clears the slot only when [token] is still the registered generation.
     *
     * The snackbar dismisses itself by timeout or by a newer message. Once it is gone the action
     * can never be invoked, and the closure's `SavePoint.saved` is the only remaining reference to
     * the saved Vault plaintext - so it must not outlive the bar. A token from an older bar cannot
     * clear the action a newer save just registered.
     */
    fun clearIf(token: Long) {
        if (generation == token) clear()
    }

    fun clear() {
        action = null
        ownerNoteId = null
    }
}
