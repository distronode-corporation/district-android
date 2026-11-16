@file:Suppress("TooManyFunctions")

package com.distronode.districtai.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.resolve

/** Step 4, "What callers should know". See [SetupCallersModel]. */
@Composable
internal fun SetupCallersStep(model: SetupCallersModel) {
    SetupNote(stringResource(R.string.setup_callers_intro))
    if (model.hasDrafts) {
        SetupNote(
            stringResource(R.string.setup_callers_draft_note),
            modifier = Modifier.testTag(CallersTags.DRAFT_NOTE)
        )
    }
    SetupCard {
        SetupPicker(
            label = stringResource(R.string.setup_business_timezone_label),
            placeholder = stringResource(R.string.setup_callers_timezone_placeholder),
            selected = model.timezone,
            options = model.zones.map { SetupOption(it, it) },
            tag = CallersTags.TIMEZONE,
            onSelect = @DontMemoize { model.timezone = it },
        )
        SetupNote(stringResource(R.string.setup_callers_timezone_description), small = true)
    }
    CallersHours(model)
    CallersAddress(model)
    CallersServices(model)
    CallersFaqs(model)
    CallersActions(model)
}

@Composable
private fun CallersAddress(model: SetupCallersModel) {
    val address = model.address
    SetupCard(heading = stringResource(R.string.setup_callers_address_header)) {
        SetupField(
            label = stringResource(R.string.setup_business_street_label),
            value = address.line1,
            onValueChange = @DontMemoize { model.address = model.address.copy(line1 = it) },
            tag = CallersTags.ADDRESS_LINE,
        )
        SetupField(
            label = stringResource(R.string.setup_business_city_label),
            value = address.city,
            onValueChange = @DontMemoize { model.address = model.address.copy(city = it) },
            tag = CallersTags.ADDRESS_CITY,
        )
        SetupField(
            label = stringResource(R.string.setup_callers_address_region),
            value = address.region,
            onValueChange = @DontMemoize { model.address = model.address.copy(region = it) },
            tag = CallersTags.ADDRESS_REGION,
        )
        SetupField(
            label = stringResource(R.string.setup_callers_address_postal),
            value = address.postalCode,
            onValueChange = @DontMemoize { model.address = model.address.copy(postalCode = it) },
            tag = CallersTags.ADDRESS_POSTAL,
        )
        SetupField(
            label = stringResource(R.string.setup_callers_address_country),
            value = address.country,
            onValueChange = @DontMemoize { model.address = model.address.copy(country = it) },
            tag = CallersTags.ADDRESS_COUNTRY,
            hint = stringResource(R.string.setup_callers_address_country_hint),
            error = stringResource(R.string.setup_callers_address_incomplete)
                .takeIf { model.showErrors && !SetupFacts.isValid(address) },
            keyboard = KeyboardType.Ascii,
        )
    }
}

@Composable
private fun CallersActions(model: SetupCallersModel) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        val error = model.saveError
        if (error != null) {
            SetupErrorLine(error.resolve(), modifier = Modifier.testTag(CallersTags.SAVE_ERROR))
        } else if (model.saved) {
            SetupNote(stringResource(R.string.setup_saved), modifier = Modifier.testTag(CallersTags.SAVED))
        }
        DistrictButton(
            text = stringResource(R.string.setup_save),
            onClick = @DontMemoize { model.save() },
            variant = ButtonVariant.Secondary,
            enabled = !model.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CallersTags.SAVE),
        )
        SetupPrimaryButton(
            title = stringResource(R.string.setup_save_and_continue),
            busy = model.isSaving,
            tag = CallersTags.SAVE_AND_CONTINUE,
            onClick = @DontMemoize { model.saveAndContinue() },
        )
    }
}

/** The week, one row per day: Not set, Closed or Open with up to four periods. */
@Composable
private fun CallersHours(model: SetupCallersModel) {
    SetupCard(heading = stringResource(R.string.setup_callers_hours_header)) {
        SetupNote(stringResource(R.string.setup_callers_hours_description), small = true)
        HoursFromWebsite(model)
        SetupWeekday.entries.forEach { day ->
            DayRow(model, day)
            if (day != SetupWeekday.SUN) DistrictRowDivider()
        }
    }
}

@Composable
private fun HoursFromWebsite(model: SetupCallersModel) {
    if (model.hoursDraftPending) {
        SetupNote(
            stringResource(R.string.setup_callers_hours_draft_note),
            modifier = Modifier.testTag(CallersTags.HOURS_DRAFT)
        )
        DistrictButton(
            text = stringResource(R.string.setup_callers_use_hours),
            onClick = @DontMemoize { model.useDraftHours() },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CallersTags.USE_HOURS),
        )
        DistrictButton(
            text = stringResource(R.string.setup_callers_discard_hours),
            onClick = @DontMemoize { model.discardDraftHours() },
            variant = ButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CallersTags.DISCARD_HOURS),
        )
    } else {
        SetupField(
            label = stringResource(R.string.setup_business_website_label),
            value = model.websiteUrl,
            onValueChange = @DontMemoize { model.websiteUrl = it },
            tag = CallersTags.WEBSITE,
            placeholder = stringResource(R.string.setup_business_website_placeholder),
            keyboard = KeyboardType.Uri,
        )
        DistrictButton(
            text = stringResource(
                if (model.isReading) R.string.setup_business_reading else R.string.setup_callers_fill_from_website,
            ),
            onClick = @DontMemoize { model.fillHoursFromWebsite() },
            variant = ButtonVariant.Secondary,
            enabled = !model.isReading && model.websiteUrl.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CallersTags.FILL),
        )
    }
    model.hoursNotice?.let {
        SetupNote(
            it.resolve(),
            small = true,
            modifier = Modifier.testTag(CallersTags.HOURS_NOTICE)
        )
    }
}

@Composable
private fun DayRow(model: SetupCallersModel, day: SetupWeekday) {
    val draft = model.day(day)
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        SetupPicker(
            label = stringResource(dayName(day)),
            placeholder = stringResource(R.string.setup_callers_day_not_set),
            selected = draft.state.name,
            options = SetupDayState.entries.map { SetupOption(it.name, stringResource(dayStateLabel(it))) },
            tag = CallersTags.day(day),
            onSelect = @DontMemoize { model.setState(SetupDayState.valueOf(it), day) },
        )
        if (draft.state == SetupDayState.OPEN) {
            draft.intervals.forEachIndexed { index, interval ->
                PeriodRow(model, day, index, interval, removable = draft.intervals.size > 1)
            }
            if (!SetupFacts.isValid(draft)) {
                SetupErrorLine(
                    stringResource(R.string.setup_callers_period_error),
                    modifier = Modifier.testTag(CallersTags.periodError(day))
                )
            }
            if (draft.intervals.size < SetupFacts.INTERVALS_PER_DAY) {
                DistrictButton(
                    text = stringResource(R.string.setup_callers_add_period),
                    onClick = @DontMemoize { model.addPeriod(day) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag(CallersTags.addPeriod(day)),
                )
            }
        }
    }
}

@Composable
private fun PeriodRow(
    model: SetupCallersModel,
    day: SetupWeekday,
    index: Int,
    interval: SetupInterval,
    removable: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SetupPicker(
                label = stringResource(R.string.setup_callers_opens),
                placeholder = SetupInterval.text(interval.open),
                selected = SetupInterval.text(interval.open),
                options = times(interval.open, allowMidnight = false),
                tag = CallersTags.period(day, index, "open"),
                onSelect = @DontMemoize { model.setPeriod(day, interval.id, open = SetupInterval.minutes(it)) },
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            SetupPicker(
                label = stringResource(R.string.setup_callers_closes),
                placeholder = SetupInterval.text(interval.close),
                selected = SetupInterval.text(interval.close),
                options = times(interval.close, allowMidnight = true),
                tag = CallersTags.period(day, index, "close"),
                onSelect = @DontMemoize { model.setPeriod(day, interval.id, close = SetupInterval.minutes(it)) },
            )
        }
        if (removable) {
            DistrictButton(
                text = stringResource(R.string.setup_callers_remove),
                onClick = @DontMemoize { model.removePeriod(interval.id, day) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag(CallersTags.period(day, index, "remove")),
            )
        }
    }
}

/** Every half hour, plus the stored value when it falls between, plus "24:00" for a close. */
internal fun times(stored: Int, allowMidnight: Boolean): List<SetupOption> {
    val half = SetupInterval.MINUTES_PER_HOUR / 2
    val minutes = (0 until SetupInterval.MIDNIGHT step half).toMutableList()
    if (allowMidnight) minutes += SetupInterval.MIDNIGHT
    if (stored !in minutes) minutes += stored
    return minutes.sorted().map { SetupOption(SetupInterval.text(it), SetupInterval.text(it)) }
}

/** The services, each with its "Use on calls" switch. */
@Composable
private fun CallersServices(model: SetupCallersModel) {
    SetupCard(heading = stringResource(R.string.setup_callers_services_header)) {
        SetupNote(stringResource(R.string.setup_callers_services_description), small = true)
        if (model.services.isEmpty()) SetupNote(stringResource(R.string.setup_callers_no_services))
        model.services.forEachIndexed { index, service ->
            SetupField(
                label = stringResource(R.string.setup_callers_service_name),
                value = service.name,
                onValueChange = @DontMemoize { text -> model.updateService(service.id) { it.copy(name = text) } },
                tag = CallersTags.item("service", index, "name"),
                error = stringResource(R.string.setup_callers_service_error)
                    .takeIf { model.showErrors && !SetupFacts.isValid(service) },
            )
            SetupField(
                label = stringResource(R.string.setup_callers_service_price),
                value = service.price,
                onValueChange = @DontMemoize { text -> model.updateService(service.id) { it.copy(price = text) } },
                tag = CallersTags.item("service", index, "price"),
            )
            ItemSwitch(
                on = service.enabled,
                tag = CallersTags.item("service", index, "use"),
                onChange = @DontMemoize { on -> model.updateService(service.id) { it.copy(enabled = on) } },
                onRemove = @DontMemoize { model.removeService(service.id) },
            )
            DistrictRowDivider()
        }
        DistrictButton(
            text = stringResource(R.string.setup_callers_add_service),
            onClick = @DontMemoize { model.addService() },
            variant = ButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CallersTags.ADD_SERVICE),
        )
    }
}

/** The common questions, each with its "Use on calls" switch. */
@Composable
private fun CallersFaqs(model: SetupCallersModel) {
    SetupCard(heading = stringResource(R.string.setup_callers_faqs_header)) {
        SetupNote(stringResource(R.string.setup_callers_faqs_description), small = true)
        if (model.faqs.isEmpty()) SetupNote(stringResource(R.string.setup_callers_no_faqs))
        model.faqs.forEachIndexed { index, faq ->
            SetupField(
                label = stringResource(R.string.setup_callers_faq_question),
                value = faq.question,
                onValueChange = @DontMemoize { text -> model.updateFaq(faq.id) { it.copy(question = text) } },
                tag = CallersTags.item("faq", index, "question"),
                error = stringResource(
                    R.string.setup_callers_faq_error
                ).takeIf { model.showErrors && !SetupFacts.isValid(faq) },
            )
            SetupField(
                label = stringResource(R.string.setup_callers_faq_answer),
                value = faq.answer,
                onValueChange = @DontMemoize { text -> model.updateFaq(faq.id) { it.copy(answer = text) } },
                tag = CallersTags.item("faq", index, "answer"),
                multiline = true,
            )
            ItemSwitch(
                on = faq.enabled,
                tag = CallersTags.item("faq", index, "use"),
                onChange = @DontMemoize { on -> model.updateFaq(faq.id) { it.copy(enabled = on) } },
                onRemove = @DontMemoize { model.removeFaq(faq.id) },
            )
            DistrictRowDivider()
        }
        DistrictButton(
            text = stringResource(R.string.setup_callers_add_faq),
            onClick = @DontMemoize { model.addFaq() },
            variant = ButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CallersTags.ADD_FAQ),
        )
    }
}

/** "Use on calls", the off hint, and Remove. */
@Composable
private fun ItemSwitch(on: Boolean, tag: String, onChange: (Boolean) -> Unit, onRemove: () -> Unit) {
    SetupSwitchRow(
        label = stringResource(R.string.setup_callers_use_on_calls),
        checked = on,
        tag = tag,
        onChange = onChange
    )
    if (!on) SetupNote(stringResource(R.string.setup_callers_off_hint), small = true)
    DistrictButton(
        text = stringResource(R.string.setup_callers_remove),
        onClick = onRemove,
        variant = ButtonVariant.Ghost,
        size = ButtonSize.Sm,
        modifier = Modifier.testTag("$tag-remove"),
    )
}

private fun dayName(day: SetupWeekday): Int = when (day) {
    SetupWeekday.MON -> R.string.setup_callers_day_mon
    SetupWeekday.TUE -> R.string.setup_callers_day_tue
    SetupWeekday.WED -> R.string.setup_callers_day_wed
    SetupWeekday.THU -> R.string.setup_callers_day_thu
    SetupWeekday.FRI -> R.string.setup_callers_day_fri
    SetupWeekday.SAT -> R.string.setup_callers_day_sat
    SetupWeekday.SUN -> R.string.setup_callers_day_sun
}

private fun dayStateLabel(state: SetupDayState): Int = when (state) {
    SetupDayState.NOT_SET -> R.string.setup_callers_day_not_set
    SetupDayState.CLOSED -> R.string.setup_callers_day_closed
    SetupDayState.OPEN -> R.string.setup_callers_day_open
}

internal object CallersTags {
    const val DRAFT_NOTE = "district-setup-callers-draft-note"
    const val TIMEZONE = "district-setup-callers-timezone"
    const val WEBSITE = "district-setup-callers-website"
    const val FILL = "district-setup-callers-fill"
    const val HOURS_NOTICE = "district-setup-callers-hours-notice"
    const val HOURS_DRAFT = "district-setup-callers-hours-draft"
    const val USE_HOURS = "district-setup-callers-use-hours"
    const val DISCARD_HOURS = "district-setup-callers-discard-hours"
    const val ADDRESS_LINE = "district-setup-callers-address-line1"
    const val ADDRESS_CITY = "district-setup-callers-address-city"
    const val ADDRESS_REGION = "district-setup-callers-address-region"
    const val ADDRESS_POSTAL = "district-setup-callers-address-postal"
    const val ADDRESS_COUNTRY = "district-setup-callers-address-country"
    const val ADD_SERVICE = "district-setup-callers-add-service"
    const val ADD_FAQ = "district-setup-callers-add-faq"
    const val SAVE_ERROR = "district-setup-callers-save-error"
    const val SAVED = "district-setup-callers-saved"
    const val SAVE = "district-setup-callers-save"
    const val SAVE_AND_CONTINUE = "district-setup-callers-save-continue"

    fun day(day: SetupWeekday): String = "district-setup-callers-day-${day.wire}"

    fun period(day: SetupWeekday, index: Int, part: String): String = "${day(day)}-$part-$index"

    fun periodError(day: SetupWeekday): String = "${day(day)}-error"

    fun addPeriod(day: SetupWeekday): String = "${day(day)}-add"

    fun item(kind: String, index: Int, part: String): String = "district-setup-callers-$kind-$index-$part"
}
