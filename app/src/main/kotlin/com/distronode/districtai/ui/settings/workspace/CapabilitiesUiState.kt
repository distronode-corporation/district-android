package com.distronode.districtai.ui.settings.workspace

/**
 * The capabilities screen: what the agent may DO on a call, plus the enrichment consent flag.
 *
 * ⛔ TWO SECTIONS WITH TWO SAVE BUTTONS AND TWO ROUTES, AND THAT MIRRORS THE WEB DELIBERATELY. The
 * capability allowlist is `PATCH workspace/tools`; the enrichment opt-in is a persona field and
 * goes to `PATCH workspace/persona` — exactly as `EnrichmentSettingsForm` does inside the web's
 * capabilities tab. Folding them into one button would mean one tap writing through two routes
 * with different failure modes, and the wholesale-replace one would be the half nobody was
 * thinking about when they tapped it.
 *
 * ⛔ THE ALLOWLIST IS THE ONLY WHOLESALE-REPLACE VALUE THIS CLIENT WRITES. Everything reachable
 * from here about `toolConfig` — the calendar, the support number, the sender identity — is
 * displayed and never sent, because those fields are written when PRESENT and `""` clears them.
 *
 * ⚠️ [toolToggles] IS SPARSE ON PURPOSE: only ids the operator actually flipped. The effective
 * state of every row is derived from the LOADED baseline through [capabilityRows], so a row this
 * client cannot name still renders and still saves.
 */
data class CapabilitiesUiState(
    val load: ConfigState = ConfigState.Loading,
    /** Only what the operator toggled. Absent means "whatever the server holds". */
    val toolToggles: Map<String, Boolean> = emptyMap(),
    /**
     * The enrichment draft, or null when untouched.
     *
     * ⚠️ NULL IS NOT `false`. The stored flag is itself nullable (a workspace that has never
     * answered), and both render as off — but only an explicit toggle may put a boolean on the
     * wire, because the sub-processor list's promise is that the feature is off until someone
     * turns it on, and writing `false` for an untouched form would be a claim nobody made.
     */
    val enrichmentDraft: Boolean? = null,
    val toolsSave: SaveState = SaveState.Idle,
    val enrichmentSave: SaveState = SaveState.Idle,
) {

    /** The rows to draw, catalog first and unrecognised stored ids appended. Empty until loaded. */
    val rows: List<CapabilityRow>
        get() = (load as? ConfigState.Ready)?.let { capabilityRows(it.config, toolToggles) }
            ?: emptyList()

    /**
     * ⛔ THE COMPLETE LIST TO SEND. Built from what was LOADED with the toggles applied — never
     * from the catalog — so an id the catalog does not know survives, and the order the server is
     * storing is preserved. Null until the config has loaded, which is what makes "you cannot
     * save what you did not load" a type-level fact rather than a convention.
     */
    val pendingTools: List<String>?
        get() = (load as? ConfigState.Ready)
            ?.let { applyToggles(baselineTools(it.config), toolToggles) }

    /** ⚠️ Compared as an ORDERED list: a reorder is a change to a value stored verbatim. */
    val toolsDirty: Boolean
        get() = (load as? ConfigState.Ready)?.let { pendingTools != baselineTools(it.config) } == true

    /** True only when the draft disagrees with what is stored (null stored reads as off). */
    val enrichmentDirty: Boolean
        get() = enrichmentDraft != null &&
            enrichmentDraft != ((load as? ConfigState.Ready)?.config?.aiPersona?.dgiEnabled == true)

    /** What the enrichment switch shows: the draft, else the stored flag, else off. */
    val enrichmentEnabled: Boolean
        get() = enrichmentDraft
            ?: ((load as? ConfigState.Ready)?.config?.aiPersona?.dgiEnabled == true)

    /** ⚠️ Either section counts — back must confirm if anything at all is pending. */
    val hasUnsavedChanges: Boolean get() = toolsDirty || enrichmentDirty

    val canSaveTools: Boolean
        get() = load is ConfigState.Ready && !toolsSave.busy && !enrichmentSave.busy && toolsDirty

    val canSaveEnrichment: Boolean
        get() = load is ConfigState.Ready && !toolsSave.busy && !enrichmentSave.busy && enrichmentDirty
}
