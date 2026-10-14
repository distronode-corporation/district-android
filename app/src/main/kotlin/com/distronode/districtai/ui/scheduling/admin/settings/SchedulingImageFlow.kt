package com.distronode.districtai.ui.scheduling.admin.settings

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.branding
import com.distronode.districtai.core.data.deleteAvatar
import com.distronode.districtai.core.data.deleteBrandingBanner
import com.distronode.districtai.core.data.deleteBrandingLogo
import com.distronode.districtai.core.data.me
import com.distronode.districtai.core.data.valueOrNull
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.inbox.PickedAttachment
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The three images: the member's picture in the profile dialog, the logo and the banner in the
 * booking page dialog; district-ios's image halves of `SchedulingProfileModel` and
 * `SchedulingBrandingModel`.
 *
 * ⚠️ AN IMAGE WRITE IS IMMEDIATE and closing the dialog does not undo it, which the dialog says.
 * ⚠️ After a write the row is re-read (`me.get`, `settings.branding.get`) rather than trusted from
 * the answer, except an avatar upload that answered its URL: an upload's URL may be `""`, "uploaded,
 * address unreadable". A failed re-read leaves the old row alone; the image IS changed.
 * ⛔ The re-read moves only the stored row, never the drafts: adopting it over a form the member
 * is part-way through would discard their typing.
 */
class SchedulingImageFlow(
    private val host: SettingsDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onSaved: (SettingsSaved) -> Unit,
) {
    private var choosing: SchedulingAdminUploadTarget? = null

    /** Record which image the system picker is choosing; the screen opens the picker. */
    fun choose(target: SchedulingAdminUploadTarget) {
        choosing = target
    }

    /**
     * The picker's answer, as bytes, a name and a type (null when nothing could be read).
     *
     * ⛔ CHECKED BEFORE A BYTE IS SENT: an unreadable pick, one over 5 MiB, or one not JPEG, PNG,
     * GIF or WebP is refused here with its own sentence and spends no request.
     */
    fun upload(picked: PickedAttachment?) {
        val target = choosing ?: return
        choosing = null
        val dialog = host.current ?: return
        if (dialog.busy) return
        when (val check = picked.imageCheck()) {
            is SchedulingImageCheck.Refuse -> dialog.withImage(target, refused(check.reason))?.let(host::show)
            is SchedulingImageCheck.Send -> {
                val working = dialog.withImage(target, WriteState.Working) ?: return
                host.show(working)
                scope.launch { send(target, check.file) }
            }
        }
    }

    /** Ask before removing [target]. Ignored while a request is on its way. */
    fun askRemove(target: SchedulingAdminUploadTarget) {
        val dialog = host.current ?: return
        if (dialog.busy) return
        dialog.confirmingRemove(target)?.let(host::show)
    }

    fun cancelRemove() {
        host.current?.confirmingRemove(null)?.let(host::show)
    }

    /** ⛔ Clears the question before it sends, so a second tap cannot send a second delete. */
    fun confirmRemove() {
        val (target, working) = when (val dialog = host.current) {
            is SettingsDialog.Profile -> {
                if (!dialog.confirmingRemove) return
                SchedulingAdminUploadTarget.AVATAR to dialog.copy(confirmingRemove = false, avatar = WriteState.Working)
            }
            is SettingsDialog.Branding -> when (dialog.confirmingRemove) {
                SchedulingAdminUploadTarget.LOGO ->
                    SchedulingAdminUploadTarget.LOGO to dialog.copy(confirmingRemove = null, logo = WriteState.Working)
                SchedulingAdminUploadTarget.BANNER ->
                    SchedulingAdminUploadTarget.BANNER to
                        dialog.copy(confirmingRemove = null, banner = WriteState.Working)
                else -> return
            }
            else -> return
        }
        host.show(working)
        scope.launch {
            val result = when (target) {
                SchedulingAdminUploadTarget.AVATAR -> admin.deleteAvatar(workspaceId)
                SchedulingAdminUploadTarget.LOGO -> admin.deleteBrandingLogo(workspaceId)
                SchedulingAdminUploadTarget.BANNER -> admin.deleteBrandingBanner(workspaceId)
            }
            when (result) {
                is SchedulingAdminResult.Ok -> {
                    reread(target)
                    setImage(target, WriteState.Done(UiText.Resource(target.removedText())))
                }
                is SchedulingAdminResult.Failed -> setImage(target, WriteState.Failed(result.error.toFailureText()))
            }
        }
    }

    private suspend fun send(target: SchedulingAdminUploadTarget, file: PickedAttachment) {
        when (val result = admin.upload(workspaceId, target, file.fileName, file.mimeType, file.bytes)) {
            is SchedulingAdminResult.Ok -> {
                val url = result.value.avatarUrl.orEmpty()
                val profile = host.current as? SettingsDialog.Profile
                if (target == SchedulingAdminUploadTarget.AVATAR && url.isNotEmpty() && profile != null) {
                    val me = profile.me.copy(avatarUrl = url)
                    host.show(profile.copy(me = me))
                    onSaved(SettingsSaved.Profile(me))
                } else {
                    reread(target)
                }
                setImage(target, WriteState.Done(UiText.Resource(target.uploadedText())))
            }
            is SchedulingAdminResult.Failed -> setImage(target, WriteState.Failed(result.error.toFailureText()))
        }
    }

    private suspend fun reread(target: SchedulingAdminUploadTarget) {
        if (target == SchedulingAdminUploadTarget.AVATAR) {
            val me = admin.me(workspaceId).valueOrNull() ?: return
            host.update<SettingsDialog.Profile> { it.copy(me = me) }
            onSaved(SettingsSaved.Profile(me))
        } else {
            val branding = admin.branding(workspaceId).valueOrNull() ?: return
            host.update<SettingsDialog.Branding> { it.copy(branding = branding) }
            onSaved(SettingsSaved.Branding(branding))
        }
    }

    /** ⚠️ A dialog closed or swapped while the request was out is left alone. */
    private fun setImage(target: SchedulingAdminUploadTarget, state: WriteState) {
        host.current?.withImage(target, state)?.let(host::show)
    }
}

/** The dialog with [target]'s state set, or null when this dialog does not hold that image. */
internal fun SettingsDialog.withImage(target: SchedulingAdminUploadTarget, state: WriteState): SettingsDialog? =
    when {
        this is SettingsDialog.Profile && target == SchedulingAdminUploadTarget.AVATAR -> copy(avatar = state)
        this is SettingsDialog.Branding && target == SchedulingAdminUploadTarget.LOGO -> copy(logo = state)
        this is SettingsDialog.Branding && target == SchedulingAdminUploadTarget.BANNER -> copy(banner = state)
        else -> null
    }

/** The dialog asking about removing [target] (or no longer asking, for null), or null. */
internal fun SettingsDialog.confirmingRemove(target: SchedulingAdminUploadTarget?): SettingsDialog? = when {
    this is SettingsDialog.Profile && target != SchedulingAdminUploadTarget.LOGO &&
        target != SchedulingAdminUploadTarget.BANNER -> copy(confirmingRemove = target != null)
    this is SettingsDialog.Branding && target != SchedulingAdminUploadTarget.AVATAR -> copy(confirmingRemove = target)
    else -> null
}

internal fun SchedulingAdminUploadTarget.uploadedText(): Int = when (this) {
    SchedulingAdminUploadTarget.LOGO -> R.string.scheduling_admin_settings_logo_uploaded
    SchedulingAdminUploadTarget.BANNER -> R.string.scheduling_admin_settings_banner_uploaded
    SchedulingAdminUploadTarget.AVATAR -> R.string.scheduling_admin_settings_avatar_uploaded
}

internal fun SchedulingAdminUploadTarget.removedText(): Int = when (this) {
    SchedulingAdminUploadTarget.LOGO -> R.string.scheduling_admin_settings_logo_removed
    SchedulingAdminUploadTarget.BANNER -> R.string.scheduling_admin_settings_banner_removed
    SchedulingAdminUploadTarget.AVATAR -> R.string.scheduling_admin_settings_avatar_removed
}

/** ⚠️ A refusal made before sending: retrying the same file cannot help. */
private fun refused(reason: Int): WriteState =
    WriteState.Failed(FailureText(UiText.Resource(reason), retryable = false))
