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
 */
object PendingSaveUndo {

    private var action: (suspend () -> Unit)? = null

    fun register(action: suspend () -> Unit) {
        this.action = action
    }

    /** Runs and consumes the pending action. False when there was nothing left to undo. */
    suspend fun undo(): Boolean {
        val pending = action ?: return false
        action = null
        pending()
        return true
    }

    fun clear() {
        action = null
    }
}
