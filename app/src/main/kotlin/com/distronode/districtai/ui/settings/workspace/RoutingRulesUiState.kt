package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.RoutingRule
import com.distronode.districtai.core.model.routingRules

/**
 * The dynamic-persona routing editor.
 *
 * ⛔ THE SAME WHOLESALE-REPLACE STORY AS THE DIRECTORY, WITH ONE EXTRA TRAP. `POST
 * workspace/routing-rules` replaces the stored array with exactly what it receives, and an empty
 * array deletes every rule — but unlike the directory route it REFUSES a body with no array at all
 * (400 "Invalid payload") rather than treating it as empty. The request type makes that
 * unreachable; the destructive case here is the same one as everywhere on this surface, an array
 * built from something other than a successful load.
 *
 * ⛔ AND THE STORED ROWS ARE NOT THE SHAPE THE WEB BUILDER EDITS. The committed contract fixture
 * carries rules shaped `{id, match, action, target}` alongside builder-shaped ones, because the
 * column is `Json` and the route's schema is `.passthrough()`. [RoutingRule] is a value class over
 * the raw object for exactly that reason: a rule this editor cannot name is still rendered, still
 * counted and still saved byte-identically.
 *
 * ⚠️ NO CLIENT-SIDE VALIDATION AT ALL, WHICH MATCHES THE WEB BUILDER. It has none either — a rule
 * with an empty match value is addable there — and the only server-side rule is the per-workspace
 * voice/model allow-list, which this client cannot see. Inventing one here would refuse values a
 * workspace permits.
 */
data class RoutingRulesUiState(
    val load: ConfigState = ConfigState.Loading,
    /** ⛔ Null until a load succeeds AND until something is edited. */
    val draft: List<RoutingRule>? = null,
    val save: SaveState = SaveState.Idle,
) {

    /** The stored rules, or null when the array cannot be edited losslessly. */
    val baseline: List<RoutingRule>?
        get() = (load as? ConfigState.Ready)?.let { routingRules(it.config.routingRules) }

    val rules: List<RoutingRule> get() = draft ?: baseline ?: emptyList()

    val editable: Boolean get() = load is ConfigState.Ready && baseline != null

    /** ⛔ The read succeeded but the stored array is a shape this client cannot rebuild. */
    val unmodellable: Boolean get() = load is ConfigState.Ready && baseline == null

    /** ⚠️ Ordered: rules are evaluated in stored order, so a reorder changes which one wins. */
    val dirty: Boolean get() = draft != null && draft != baseline

    val canSave: Boolean get() = editable && !save.busy && dirty

    /** ⛔ Saving nothing deletes every rule. Its own flag so the confirmation can say that. */
    val savingEmptiesRules: Boolean get() = rules.isEmpty()

    val hasUnsavedChanges: Boolean get() = dirty
}
