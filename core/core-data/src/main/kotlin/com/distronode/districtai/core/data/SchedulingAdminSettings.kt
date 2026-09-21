@file:Suppress("TooManyFunctions")

package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingBranding
import com.distronode.districtai.core.model.SchedulingLlmSettings
import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingNotetakerSettings
import com.distronode.districtai.core.model.SchedulingStorageSettings
import com.distronode.districtai.core.network.SchedulingAdminOp

/*
 * The three `me.*` ops and the ten `settings.*` ops, typed.
 *
 * ⛔ TWO SCOPES IN ONE FILE, AND THE SPLIT IS THE ROLE BAR RATHER THAN THE NAMESPACE. `me.*` is the
 * CALLER'S own scheduler user and is `viewer` even where it writes; `settings.*` is the TENANCY's
 * configuration and is `client`. They live together because iOS's `+Settings` extension does, and
 * because a settings screen reads both — but a control drawn from one bar and wired to the other
 * is a 403 the user cannot act on.
 *
 * ⛔ `@file:Suppress("TooManyFunctions")` FOR THE REASON `SchedulingAdminEventTypes.kt` STATES:
 * thirteen functions against detekt's eleven, and the only split available would cut a namespace
 * in half to satisfy a count.
 */

suspend fun SchedulingAdminRepository.me(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingMe> = perform(
    SchedulingAdminOp.ME_GET,
    workspaceId,
    schedulingParams(),
    SchedulingMe.serializer(),
)

/**
 * ⚠️ ANSWERS THE WHOLE UPDATED USER, not an acknowledgement, so a screen should render the answer
 * rather than the values it sent — the fork normalises the timezone and can refuse a format.
 */
suspend fun SchedulingAdminRepository.updateMe(
    workspaceId: String,
    update: SchedulingMeUpdate,
): SchedulingAdminOutcome<SchedulingMe> = perform(
    SchedulingAdminOp.ME_PATCH,
    workspaceId,
    schedulingParams(
        "name" to textParam(update.name),
        "timezone" to textParam(update.timezone),
        "time_format" to textParam(update.timeFormat),
        "week_start" to intParam(update.weekStart),
        "date_format" to textParam(update.dateFormat),
        "notify_confirmation" to boolParam(update.notifyConfirmation),
        "notify_cancellation" to boolParam(update.notifyCancellation),
        "notify_reschedule" to boolParam(update.notifyReschedule),
        "notify_reminder" to boolParam(update.notifyReminder),
        "notify_host_booking" to boolParam(update.notifyHostBooking),
        "notify_host_cancel" to boolParam(update.notifyHostCancel),
        "notify_host_reschedule" to boolParam(update.notifyHostReschedule),
    ),
    SchedulingMe.serializer(),
)

suspend fun SchedulingAdminRepository.deleteAvatar(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.ME_AVATAR_DELETE,
    workspaceId,
    schedulingParams(),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.branding(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingBranding> = perform(
    SchedulingAdminOp.SETTINGS_BRANDING_GET,
    workspaceId,
    schedulingParams(),
    SchedulingBranding.serializer(),
)

/**
 * ⛔ THE WHOLE SETTINGS OBJECT, NOT A PATCH, DESPITE THE OP'S NAME. All seven keys are declared
 * required by the catalog, so a caller that sent three would blank the rest. Seed the update with
 * [SchedulingBrandingUpdate.from] and change only what the form changed.
 */
suspend fun SchedulingAdminRepository.updateBranding(
    workspaceId: String,
    update: SchedulingBrandingUpdate,
): SchedulingAdminOutcome<SchedulingBranding> = perform(
    SchedulingAdminOp.SETTINGS_BRANDING_PATCH,
    workspaceId,
    schedulingParams(
        "business_name" to textParam(update.businessName),
        "logo_height" to intParam(update.logoHeight),
        "logo_opacity" to intParam(update.logoOpacity),
        "banner_opacity" to intParam(update.bannerOpacity),
        "privacy_url" to textParam(update.privacyUrl),
        "terms_url" to textParam(update.termsUrl),
        "fallback_locale" to textParam(update.fallbackLocale),
    ),
    SchedulingBranding.serializer(),
)

suspend fun SchedulingAdminRepository.deleteBrandingLogo(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.SETTINGS_BRANDING_LOGO_DELETE,
    workspaceId,
    schedulingParams(),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.deleteBrandingBanner(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.SETTINGS_BRANDING_BANNER_DELETE,
    workspaceId,
    schedulingParams(),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.storageSettings(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingStorageSettings> = perform(
    SchedulingAdminOp.SETTINGS_STORAGE_GET,
    workspaceId,
    schedulingParams(),
    SchedulingStorageSettings.serializer(),
)

/**
 * ⚠️ ENABLING THIS WITH NO STORAGE RECORDS NOTHING AND REPORTS NO ERROR. Read
 * [SchedulingStorageSettings.recordingsStorageReady] from the answer before telling an operator
 * their calls are being recorded.
 */
suspend fun SchedulingAdminRepository.setRecordingsEnabled(
    workspaceId: String,
    enabled: Boolean,
): SchedulingAdminOutcome<SchedulingStorageSettings> = perform(
    SchedulingAdminOp.SETTINGS_STORAGE_PATCH,
    workspaceId,
    schedulingParams("recordings_enabled" to boolParam(enabled)),
    SchedulingStorageSettings.serializer(),
)

suspend fun SchedulingAdminRepository.notetakerSettings(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingNotetakerSettings> = perform(
    SchedulingAdminOp.SETTINGS_NOTETAKER_GET,
    workspaceId,
    schedulingParams(),
    SchedulingNotetakerSettings.serializer(),
)

/**
 * ⛔ THE CATALOG DECLARES THIS ONE `z.strictObject`, so an extra key is a **400** rather than an
 * ignored field. It is one of only two ops on the surface that are strict about params; the other
 * is [updateLlmSettings].
 */
suspend fun SchedulingAdminRepository.setNotetakerEnabled(
    workspaceId: String,
    enabled: Boolean,
): SchedulingAdminOutcome<SchedulingNotetakerSettings> = perform(
    SchedulingAdminOp.SETTINGS_NOTETAKER_PATCH,
    workspaceId,
    schedulingParams("enabled" to boolParam(enabled)),
    SchedulingNotetakerSettings.serializer(),
)

suspend fun SchedulingAdminRepository.llmSettings(
    workspaceId: String,
): SchedulingAdminOutcome<SchedulingLlmSettings> = perform(
    SchedulingAdminOp.SETTINGS_LLM_GET,
    workspaceId,
    schedulingParams(),
    SchedulingLlmSettings.serializer(),
)

/**
 * ⛔ `z.strictObject` AGAIN, and [extraInstructions] is OPERATOR-AUTHORED TEXT THAT REACHES A MODEL
 * PROMPT. Passing null leaves the stored instructions alone; passing `""` clears them. The two are
 * different requests and a screen that sent `""` for "unchanged" would silently drop whatever
 * somebody wrote.
 */
suspend fun SchedulingAdminRepository.updateLlmSettings(
    workspaceId: String,
    enabled: Boolean? = null,
    extraInstructions: String? = null,
): SchedulingAdminOutcome<SchedulingLlmSettings> = perform(
    SchedulingAdminOp.SETTINGS_LLM_PATCH,
    workspaceId,
    schedulingParams(
        "enabled" to boolParam(enabled),
        "extra_instructions" to textParam(extraInstructions),
    ),
    SchedulingLlmSettings.serializer(),
)
