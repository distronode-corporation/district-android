package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.DirectoryEntry
import com.distronode.districtai.core.model.directoryEntries

/**
 * The call transfer directory editor.
 *
 * ⛔ THIS IS THE SCREEN THAT CAN TAKE A BUSINESS OFF THE PHONE. `PATCH workspace/directory` writes
 * `callDirectory: (callDirectory || [])`, so the array this state produces becomes the complete
 * list of humans the voice agent will transfer a live caller to — and an empty one removes every
 * transfer target while answering `{success:true}`. Three properties carry the safety:
 *
 *  1. [draft] IS NULL UNTIL A LOAD SUCCEEDS, so [entries] can only ever be the stored list or an
 *     edit of it. There is no path from a failed read to a savable array.
 *  2. [baseline] IS NULL WHEN THE STORED VALUE CANNOT BE MODELLED LOSSLESSLY — see
 *     `directoryEntries`. The column was a bare `Json` write before the route gained validation, so
 *     a row that is not an object of strings genuinely exists; editing around it would delete it.
 *     [editable] is false in that case and the screen offers no controls at all.
 *  3. [dirty] COMPARES THE RAW OBJECTS AS AN ORDERED LIST, because order is stored verbatim and a
 *     reorder is a real change to the value the agent reads.
 *
 * ⚠️ VALIDATION MATCHES THE WEB FORM AND DELIBERATELY GOES NO FURTHER. `CallDirectorySettings`
 * refuses to ADD a row with a blank name or number and validates nothing else — no E.164 check, no
 * uniqueness — and the server's schema has both fields `.nullish()`, so half-filled rows are legal
 * and already exist. This state therefore blocks the ADD ([canAdd]) and never blocks the SAVE. The
 * gap that leaves is real and is surfaced rather than enforced: [incompleteCount] drives a warning,
 * because the web has no edit control at all and so has no edit-time rule to copy. A stricter rule
 * here would refuse to save data the server is already storing.
 */
data class DirectoryEditorUiState(
    val load: ConfigState = ConfigState.Loading,
    /** ⛔ Null until a load succeeds AND until something is edited. See the ⛔ on the class. */
    val draft: List<DirectoryEntry>? = null,
    val newName: String = "",
    val newPhoneNumber: String = "",
    /** ⚠️ Set only by a rejected add, cleared by the next keystroke. Never blocks a save. */
    val addRejected: Boolean = false,
    val save: SaveState = SaveState.Idle,
) {

    /**
     * The stored list, or null when it cannot be edited losslessly.
     *
     * ⚠️ AN ABSENT COLUMN IS AN EMPTY LIST, NOT AN UNEDITABLE ONE. The save route writes
     * `callDirectory || []`, so "never configured" and "explicitly empty" are the same stored value
     * and there is nothing to lose by treating them alike.
     */
    val baseline: List<DirectoryEntry>?
        get() = (load as? ConfigState.Ready)?.let { directoryEntries(it.config.callDirectory) }

    /** What the list shows: the draft if one exists, else what was loaded. */
    val entries: List<DirectoryEntry> get() = draft ?: baseline ?: emptyList()

    /** ⛔ False for a failed load AND for a stored value this client cannot represent. */
    val editable: Boolean get() = load is ConfigState.Ready && baseline != null

    /**
     * ⛔ TRUE ONLY WHEN THE STORED VALUE IS AN ARRAY THIS CLIENT CANNOT MODEL — which is a
     * different thing from a failed load and gets its own explanation on screen. Retrying will not
     * help; the fix is on the web.
     */
    val unmodellable: Boolean get() = load is ConfigState.Ready && baseline == null

    /** ⚠️ Ordered comparison of the RAW objects: a reorder is a change to a verbatim-stored value. */
    val dirty: Boolean get() = draft != null && draft != baseline

    val canAdd: Boolean
        get() = editable && !save.busy &&
            newName.isNotBlank() && newPhoneNumber.isNotBlank()

    val canSave: Boolean get() = editable && !save.busy && dirty

    /** ⚠️ Rows the server accepts but the agent cannot use. Warned about, never refused. */
    val incompleteCount: Int get() = entries.count { it.incomplete }

    /**
     * ⛔ THE SAVE IS A DELETION OF EVERY TRANSFER TARGET. Distinguished from an ordinary save so the
     * confirmation can name the consequence rather than a count — "0 entries" is a number, "no one
     * to transfer a caller to" is what actually happens.
     */
    val savingEmptiesDirectory: Boolean get() = entries.isEmpty()

    val hasUnsavedChanges: Boolean get() = dirty
}
