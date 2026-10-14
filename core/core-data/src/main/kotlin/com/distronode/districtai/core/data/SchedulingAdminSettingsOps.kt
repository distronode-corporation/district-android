package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingBranding
import com.distronode.districtai.core.model.SchedulingLlmSettings
import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonPrimitive

// The caller's own profile (`me.*`, viewer-level) and the tenancy's settings (branding and the
// booking assistant, client-level).

suspend fun SchedulingAdminRepository.me(workspaceId: String): SchedulingAdminResult<SchedulingMe> =
    perform(workspaceId, SchedulingAdminOp.ME_GET, schedulingParams(), SchedulingMe.serializer())

suspend fun SchedulingAdminRepository.updateMe(
    workspaceId: String,
    update: SchedulingMeUpdate,
): SchedulingAdminResult<SchedulingMe> = perform(
    workspaceId,
    SchedulingAdminOp.ME_PATCH,
    schedulingParams(
        "name" to update.name.jsonOrNull(),
        "timezone" to update.timezone.jsonOrNull(),
        "time_format" to update.timeFormat.jsonOrNull(),
        "week_start" to update.weekStart.jsonOrNull(),
        "date_format" to update.dateFormat.jsonOrNull(),
        "notify_confirmation" to update.notifyConfirmation.jsonOrNull(),
        "notify_cancellation" to update.notifyCancellation.jsonOrNull(),
        "notify_reschedule" to update.notifyReschedule.jsonOrNull(),
        "notify_reminder" to update.notifyReminder.jsonOrNull(),
        "notify_host_booking" to update.notifyHostBooking.jsonOrNull(),
        "notify_host_cancel" to update.notifyHostCancel.jsonOrNull(),
        "notify_host_reschedule" to update.notifyHostReschedule.jsonOrNull(),
    ),
    SchedulingMe.serializer(),
)

suspend fun SchedulingAdminRepository.deleteAvatar(
    workspaceId: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.ME_AVATAR_DELETE,
    schedulingParams(),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.branding(
    workspaceId: String,
): SchedulingAdminResult<SchedulingBranding> = perform(
    workspaceId,
    SchedulingAdminOp.SETTINGS_BRANDING_GET,
    schedulingParams(),
    SchedulingBranding.serializer(),
)

suspend fun SchedulingAdminRepository.updateBranding(
    workspaceId: String,
    update: SchedulingBrandingUpdate,
): SchedulingAdminResult<SchedulingBranding> = perform(
    workspaceId,
    SchedulingAdminOp.SETTINGS_BRANDING_PATCH,
    schedulingParams(
        "business_name" to JsonPrimitive(update.businessName),
        "logo_height" to JsonPrimitive(update.logoHeight),
        "logo_opacity" to JsonPrimitive(update.logoOpacity),
        "banner_opacity" to JsonPrimitive(update.bannerOpacity),
        "privacy_url" to JsonPrimitive(update.privacyUrl),
        "terms_url" to JsonPrimitive(update.termsUrl),
        "fallback_locale" to JsonPrimitive(update.fallbackLocale),
    ),
    SchedulingBranding.serializer(),
)

suspend fun SchedulingAdminRepository.deleteBrandingLogo(
    workspaceId: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.SETTINGS_BRANDING_LOGO_DELETE,
    schedulingParams(),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.deleteBrandingBanner(
    workspaceId: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.SETTINGS_BRANDING_BANNER_DELETE,
    schedulingParams(),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.llmSettings(
    workspaceId: String,
): SchedulingAdminResult<SchedulingLlmSettings> = perform(
    workspaceId,
    SchedulingAdminOp.SETTINGS_LLM_GET,
    schedulingParams(),
    SchedulingLlmSettings.serializer(),
)

suspend fun SchedulingAdminRepository.updateLlmSettings(
    workspaceId: String,
    enabled: Boolean? = null,
    extraInstructions: String? = null,
): SchedulingAdminResult<SchedulingLlmSettings> = perform(
    workspaceId,
    SchedulingAdminOp.SETTINGS_LLM_PATCH,
    schedulingParams("enabled" to enabled.jsonOrNull(), "extra_instructions" to extraInstructions.jsonOrNull()),
    SchedulingLlmSettings.serializer(),
)
