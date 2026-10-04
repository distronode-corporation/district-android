package com.distronode.districtai.ui.settings.workspace.studio

/*
 * Stable test handles for the Voice Studio's controls; a literal duplicated in a test drifts
 * silently. ⚠️ ONE BUILDER AND A SET OF KINDS RATHER THAN A FUNCTION PER CONTROL: the Studio has
 * thirteen kinds of handle, and one function per kind is a detekt `TooManyFunctions` ceiling in
 * every file that declared them.
 */

const val HANDLE_TIER: String = "tier"
const val HANDLE_RECIPE: String = "recipe"
const val HANDLE_BLOCK: String = "block"
const val HANDLE_LEG: String = "leg"
const val HANDLE_PICKER: String = "picker"
const val HANDLE_OPTION: String = "option"
const val HANDLE_ADVANCED: String = "advanced"
const val HANDLE_TUNING: String = "tuning"
const val HANDLE_SLIDER: String = "slider"
const val HANDLE_VALUE: String = "value"
const val HANDLE_DEFAULT: String = "default"
const val HANDLE_CHECKBOX: String = "checkbox"
const val HANDLE_KEYTERMS: String = "keyterms"

const val VOICE_STUDIO_ROOT_DESCRIPTION: String = "district-voice-studio-root"
const val VOICE_STUDIO_SAVE_DESCRIPTION: String = "district-voice-studio-save"
const val VOICE_STUDIO_NOTICE_DESCRIPTION: String = "district-voice-studio-notice"
const val VOICE_STUDIO_DIRTY_DESCRIPTION: String = "district-voice-studio-dirty"
const val VOICE_STUDIO_BASED_ON_DESCRIPTION: String = "district-voice-studio-based-on"
const val VOICE_STUDIO_RESET_DESCRIPTION: String = "district-voice-studio-reset"
const val VOICE_STUDIO_METER_DESCRIPTION: String = "district-voice-studio-meter"
const val VOICE_STUDIO_RESIDENCY_DESCRIPTION: String = "district-voice-studio-residency"

/** `district-voice-studio-<kind>-<id>`, with `-selected` on the tile or block that is selected. */
fun studioHandle(kind: String, id: String, selected: Boolean = false): String =
    "district-voice-studio-$kind-$id" + if (selected) "-selected" else ""
