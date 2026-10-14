package com.distronode.districtai.ui.scheduling.admin.settings

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.SchedulingBrandingUpdate
import com.distronode.districtai.core.data.updateBranding
import com.distronode.districtai.core.data.updateLlmSettings
import com.distronode.districtai.core.data.updateMe
import com.distronode.districtai.core.model.SchedulingBranding
import com.distronode.districtai.core.model.SchedulingLlmSettings
import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.core.network.SchedulingAdminUploadTarget
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The settings dialog that is open, if any. */
sealed interface SettingsDialog {
    val state: WriteState

    /** True while any request this dialog started is on its way: it cannot close under one. */
    val busy: Boolean get() = state.isWorking

    /**
     * The member's own profile and picture (`me.patch`, `me.avatar.delete`): `viewer`-level.
     *
     * @property confirmingRemove the picture's Remove is waiting for its answer.
     */
    data class Profile(
        val me: SchedulingMe,
        val draft: SchedulingProfileDraft,
        val rejected: Int? = null,
        override val state: WriteState = WriteState.Idle,
        val avatar: WriteState = WriteState.Idle,
        val confirmingRemove: Boolean = false,
    ) : SettingsDialog {
        override val busy: Boolean get() = state.isWorking || avatar.isWorking
    }

    /** The seven email switches: the same `me.patch`, a separate form, as on the web. */
    data class Notifications(
        val values: Map<SchedulingNotifySwitch, Boolean>,
        override val state: WriteState = WriteState.Idle,
    ) : SettingsDialog

    /**
     * The public booking page: `client`-level.
     *
     * ⛔ OPENED ONLY FROM A LOADED [branding]: the patch REPLACES all seven fields, so a form seeded
     * from nothing would blank a business name and two legal links on a customer-facing page.
     *
     * @property confirmingRemove the image whose Remove is waiting for its answer.
     */
    data class Branding(
        val branding: SchedulingBranding,
        val draft: SchedulingBrandingUpdate,
        val rejected: Int? = null,
        override val state: WriteState = WriteState.Idle,
        val logo: WriteState = WriteState.Idle,
        val banner: WriteState = WriteState.Idle,
        val confirmingRemove: SchedulingAdminUploadTarget? = null,
    ) : SettingsDialog {
        override val busy: Boolean get() = state.isWorking || logo.isWorking || banner.isWorking
    }

    /** The booking assistant: its switch and its extra instructions. `client`-level. */
    data class Assistant(
        val llm: SchedulingLlmSettings,
        val enabled: Boolean,
        val instructions: String,
        val tooLong: Boolean = false,
        override val state: WriteState = WriteState.Idle,
    ) : SettingsDialog {
        /** ⛔ Nothing is sent when nothing moved: an unchanged patch still spends a write. */
        val isDirty: Boolean get() = enabled != llm.enabled || instructions != llm.extraInstructions
    }
}

/** What a settings write stored, for the read behind it to adopt. */
sealed interface SettingsSaved {
    data class Profile(val me: SchedulingMe) : SettingsSaved
    data class Branding(val branding: SchedulingBranding) : SettingsSaved
    data class Assistant(val llm: SchedulingLlmSettings) : SettingsSaved
}

/**
 * The settings screen's five writes, ported from district-ios's `SchedulingProfileModel`,
 * `SchedulingNotificationsModel`, `SchedulingBrandingModel` and `SchedulingAutomationModel`.
 *
 * ⛔ THE ROLE RULE DIFFERS BETWEEN THEM, AND THE SCREEN APPLIES IT PER TAB: the booking page and
 * the assistant are `client`-level; the profile, the picture and the switches are the member's
 * own and are `viewer`-level. Nothing here gates; the screen draws the buttons.
 *
 * ⛔ EVERY SUBMIT IS ONE REQUEST: a press while one is on its way is ignored, and the dialog cannot
 * close under it. ⚠️ Saves happen on a button, never per keystroke: `me.*` writes spend the
 * MEMBER's 30-an-hour budget.
 *
 * @param onSaved told what each write stored, so the tab behind shows it without a re-read.
 */
class SchedulingSettingsWrites(
    scope: CoroutineScope,
    admin: SchedulingAdminRepository,
    workspaceId: String,
    onSaved: (SettingsSaved) -> Unit,
) {
    private val host = SettingsDialogHost()
    val dialog: StateFlow<SettingsDialog?> = host.dialog

    val profile = SchedulingProfileFlow(host, scope, admin, workspaceId, onSaved)
    val notifications = SchedulingNotificationsFlow(host, scope, admin, workspaceId, onSaved)
    val branding = SchedulingBrandingFlow(host, scope, admin, workspaceId, onSaved)
    val assistant = SchedulingAssistantFlow(host, scope, admin, workspaceId, onSaved)
    val images = SchedulingImageFlow(host, scope, admin, workspaceId, onSaved)

    /** Close whatever is open. Ignored while a request is on its way. */
    fun close() = host.close()
}

/** The one open settings dialog. */
class SettingsDialogHost {
    private val _dialog = MutableStateFlow<SettingsDialog?>(null)
    val dialog: StateFlow<SettingsDialog?> = _dialog.asStateFlow()

    val current: SettingsDialog? get() = _dialog.value

    fun show(dialog: SettingsDialog) {
        _dialog.value = dialog
    }

    fun close() {
        if (_dialog.value?.busy == true) return
        _dialog.value = null
    }

    /** Change the open dialog if it is still a [D]. */
    inline fun <reified D : SettingsDialog> update(change: (D) -> SettingsDialog) {
        val open = current as? D ?: return
        show(change(open))
    }
}

/** The member's own name, zone and formats. */
class SchedulingProfileFlow(
    private val host: SettingsDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onSaved: (SettingsSaved) -> Unit,
) {
    fun open(me: SchedulingMe) = host.show(SettingsDialog.Profile(me, SchedulingProfileDraft.from(me)))

    fun edit(change: (SchedulingProfileDraft) -> SchedulingProfileDraft) {
        val dialog = host.current as? SettingsDialog.Profile ?: return
        if (dialog.busy) return
        host.show(dialog.copy(draft = change(dialog.draft), rejected = null, state = idleAfterFailure(dialog.state)))
    }

    fun save() {
        val dialog = host.current as? SettingsDialog.Profile ?: return
        if (dialog.busy) return
        val rejected = dialog.draft.rejection(storedZone = dialog.me.timezone)
        if (rejected != null) {
            host.show(dialog.copy(rejected = rejected))
            return
        }
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            when (val result = admin.updateMe(workspaceId, dialog.draft.toUpdate())) {
                is SchedulingAdminResult.Ok -> {
                    host.update<SettingsDialog.Profile> {
                        it.copy(me = result.value, state = done(R.string.scheduling_admin_settings_profile_done))
                    }
                    onSaved(SettingsSaved.Profile(result.value))
                }
                is SchedulingAdminResult.Failed -> host.update<SettingsDialog.Profile> {
                    it.copy(state = WriteState.Failed(result.error.toFailureText()))
                }
            }
        }
    }
}

/** The seven email switches. */
class SchedulingNotificationsFlow(
    private val host: SettingsDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onSaved: (SettingsSaved) -> Unit,
) {
    fun open(me: SchedulingMe) =
        host.show(SettingsDialog.Notifications(SchedulingNotifySwitch.entries.associateWith { it.read(me) }))

    fun toggle(switch: SchedulingNotifySwitch, on: Boolean) {
        val dialog = host.current as? SettingsDialog.Notifications ?: return
        if (dialog.busy) return
        host.show(dialog.copy(values = dialog.values + (switch to on), state = idleAfterFailure(dialog.state)))
    }

    fun save() {
        val dialog = host.current as? SettingsDialog.Notifications ?: return
        if (dialog.busy) return
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            when (val result = admin.updateMe(workspaceId, dialog.values.toUpdate())) {
                is SchedulingAdminResult.Ok -> {
                    host.update<SettingsDialog.Notifications> {
                        it.copy(state = done(R.string.scheduling_admin_settings_notifications_done))
                    }
                    onSaved(SettingsSaved.Profile(result.value))
                }
                is SchedulingAdminResult.Failed -> host.update<SettingsDialog.Notifications> {
                    it.copy(state = WriteState.Failed(result.error.toFailureText()))
                }
            }
        }
    }
}

/** The booking page's seven fields. The images are [SchedulingImageFlow]'s. */
class SchedulingBrandingFlow(
    private val host: SettingsDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onSaved: (SettingsSaved) -> Unit,
) {
    fun open(branding: SchedulingBranding) =
        host.show(SettingsDialog.Branding(branding, SchedulingBrandingUpdate.from(branding)))

    fun edit(change: (SchedulingBrandingUpdate) -> SchedulingBrandingUpdate) {
        val dialog = host.current as? SettingsDialog.Branding ?: return
        if (dialog.busy) return
        host.show(dialog.copy(draft = change(dialog.draft), rejected = null, state = idleAfterFailure(dialog.state)))
    }

    /** ⚠️ The name and links are trimmed; the fallback locale is sent as chosen. */
    fun save() {
        val dialog = host.current as? SettingsDialog.Branding ?: return
        if (dialog.busy) return
        val rejected = dialog.draft.rejection()
        if (rejected != null) {
            host.show(dialog.copy(rejected = rejected))
            return
        }
        val update = dialog.draft.copy(
            businessName = dialog.draft.businessName.trim(),
            privacyUrl = dialog.draft.privacyUrl.trim(),
            termsUrl = dialog.draft.termsUrl.trim(),
        )
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            when (val result = admin.updateBranding(workspaceId, update)) {
                is SchedulingAdminResult.Ok -> {
                    host.update<SettingsDialog.Branding> {
                        it.copy(branding = result.value, state = done(R.string.scheduling_admin_settings_branding_done))
                    }
                    onSaved(SettingsSaved.Branding(result.value))
                }
                is SchedulingAdminResult.Failed -> host.update<SettingsDialog.Branding> {
                    it.copy(state = WriteState.Failed(result.error.toFailureText()))
                }
            }
        }
    }
}

/**
 * The booking assistant.
 *
 * ⛔ NO API-KEY FIELD, AND THERE MUST NEVER BE ONE: the catalog's schema is strict, and a
 * credential a customer believes they set but the server dropped is the worse failure.
 */
class SchedulingAssistantFlow(
    private val host: SettingsDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onSaved: (SettingsSaved) -> Unit,
) {
    fun open(llm: SchedulingLlmSettings) = host.show(SettingsDialog.Assistant(llm, llm.enabled, llm.extraInstructions))

    fun editEnabled(value: Boolean) = edit { it.copy(enabled = value) }

    fun editInstructions(value: String) = edit { it.copy(instructions = value, tooLong = false) }

    /**
     * ⚠️ THE 4000 LIMIT IS ENFORCED HERE, though the web only states it: the server's refusal names
     * a field and nothing a member can act on. The saved values are adopted from the answer, so a
     * value the server normalised is what the dialog shows.
     */
    fun save() {
        val dialog = host.current as? SettingsDialog.Assistant ?: return
        if (dialog.busy || !dialog.isDirty) return
        if (dialog.instructions.length > ASSISTANT_INSTRUCTIONS_LIMIT) {
            host.show(dialog.copy(tooLong = true))
            return
        }
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            when (val result = admin.updateLlmSettings(workspaceId, dialog.enabled, dialog.instructions)) {
                is SchedulingAdminResult.Ok -> {
                    host.update<SettingsDialog.Assistant> {
                        SettingsDialog.Assistant(
                            llm = result.value,
                            enabled = result.value.enabled,
                            instructions = result.value.extraInstructions,
                            state = done(R.string.scheduling_admin_settings_assistant_done),
                        )
                    }
                    onSaved(SettingsSaved.Assistant(result.value))
                }
                is SchedulingAdminResult.Failed -> host.update<SettingsDialog.Assistant> {
                    it.copy(state = WriteState.Failed(result.error.toFailureText()))
                }
            }
        }
    }

    private inline fun edit(change: (SettingsDialog.Assistant) -> SettingsDialog.Assistant) {
        val dialog = host.current as? SettingsDialog.Assistant ?: return
        if (dialog.busy) return
        val changed = change(dialog)
        host.show(changed.copy(state = idleAfterFailure(changed.state)))
    }
}

internal fun done(id: Int): WriteState = WriteState.Done(UiText.Resource(id))

/** Editing after a failure clears it, so the next press is a fresh attempt. */
internal fun idleAfterFailure(state: WriteState): WriteState =
    if (state is WriteState.Failed) WriteState.Idle else state
