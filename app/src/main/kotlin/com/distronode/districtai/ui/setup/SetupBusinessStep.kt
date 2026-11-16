package com.distronode.districtai.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.l10n.appLanguage
import com.distronode.districtai.ui.resolve

/** Step 1, "Find your business". See [SetupBusinessModel]. */
@Composable
internal fun SetupBusinessStep(model: SetupBusinessModel, actions: SetupActions) {
    BusinessWebsite(model)
    BusinessDetails(model)
    SetupCard(heading = stringResource(R.string.setup_business_email_heading)) {
        SetupNote(stringResource(R.string.setup_business_email_body))
        SetupHandOffButton(
            title = stringResource(R.string.setup_open_skills),
            page = SetupHandOff.SKILLS,
            actions = actions,
            tag = BusinessTags.OPEN_SKILLS,
        )
    }
}

@Composable
private fun BusinessWebsite(model: SetupBusinessModel) {
    SetupCard(heading = stringResource(R.string.setup_business_website_heading)) {
        SetupNote(stringResource(R.string.setup_business_website_lead))
        SetupField(
            label = stringResource(R.string.setup_business_website_label),
            value = model.websiteUrl,
            onValueChange = @DontMemoize { model.websiteUrl = it },
            tag = BusinessTags.WEBSITE,
            placeholder = stringResource(R.string.setup_business_website_placeholder),
            keyboard = KeyboardType.Uri,
        )
        DistrictButton(
            text = stringResource(
                if (model.isReading) R.string.setup_business_reading else R.string.setup_business_read_website,
            ),
            onClick = @DontMemoize { model.readWebsite() },
            variant = ButtonVariant.Secondary,
            enabled = !model.isBusy && model.websiteUrl.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(BusinessTags.READ_WEBSITE),
        )
        model.notice?.let { BusinessNotice(it) }
    }
}

@Composable
private fun BusinessNotice(notice: SetupBusinessNotice) {
    val modifier = Modifier.testTag(BusinessTags.NOTICE)
    when (notice) {
        is SetupBusinessNotice.Found -> SetupNote(foundSentence(notice, appLanguage()), modifier = modifier)
        SetupBusinessNotice.Nothing -> SetupNote(
            stringResource(R.string.setup_business_read_nothing),
            modifier = modifier
        )
        is SetupBusinessNotice.Failed -> SetupErrorLine(notice.failure.message.resolve(), modifier = modifier)
    }
}

/**
 * ⚠️ FRENCH TAKES THE SINGULAR FOR 0 AND 1, ENGLISH FOR 1 ONLY (the web's `<= 1` and `=== 1`).
 *
 * ⚠️ AN ADDRESS-ONLY ANSWER STILL SAYS SOMETHING: it filled the form, and an empty notice would leave
 * the owner unsure the read did anything.
 */
@Composable
internal fun foundSentence(found: SetupBusinessNotice.Found, language: AppLanguage): String {
    val singular = { count: Int -> if (language == AppLanguage.FR) count <= 1 else count == 1 }
    val parts = mutableListOf<String>()
    if (found.services > 0 || found.faqs > 0) {
        val servicesId = if (singular(found.services)) {
            R.string.setup_business_found_services_one
        } else {
            R.string.setup_business_found_services_other
        }
        val faqsId = if (singular(found.faqs)) {
            R.string.setup_business_found_faqs_one
        } else {
            R.string.setup_business_found_faqs_other
        }
        val services = stringResource(servicesId, found.services)
        val faqs = stringResource(faqsId, found.faqs)
        parts += stringResource(R.string.setup_business_read_result, services, faqs)
    }
    if (found.hours) parts += stringResource(R.string.setup_business_hours_found)
    if (parts.isEmpty() && found.address) parts += stringResource(R.string.setup_business_address_found)
    return parts.joinToString(" ")
}

@Composable
private fun BusinessDetails(model: SetupBusinessModel) {
    val busy = model.isSaving
    SetupCard(heading = stringResource(R.string.setup_business_details_heading)) {
        SetupField(
            label = stringResource(R.string.setup_business_name_label),
            value = model.businessName,
            onValueChange = @DontMemoize { model.businessName = it },
            tag = BusinessTags.NAME,
            error = stringResource(
                R.string.setup_business_name_required
            ).takeIf { model.showErrors && model.nameMissing },
            enabled = !busy,
        )
        SetupField(
            label = stringResource(R.string.setup_business_what_label),
            value = model.what,
            onValueChange = @DontMemoize { model.what = it },
            tag = BusinessTags.WHAT,
            placeholder = stringResource(R.string.setup_business_what_placeholder),
            enabled = !busy,
        )
        BusinessPlace(model)
        SetupField(
            label = stringResource(R.string.setup_business_street_label),
            value = model.street,
            onValueChange = @DontMemoize { model.street = it },
            tag = BusinessTags.STREET,
            hint = stringResource(R.string.setup_business_street_hint),
            enabled = !busy,
        )
        BusinessLanguage(model)
        Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
            Eyebrow(stringResource(R.string.setup_business_timezone_label))
            SetupNote(zoneSentence(model), modifier = Modifier.testTag(BusinessTags.TIMEZONE))
        }
        model.saveError?.let {
            SetupErrorLine(it.message.resolve(), modifier = Modifier.testTag(BusinessTags.SAVE_ERROR))
        }
        SetupPrimaryButton(
            title = stringResource(R.string.setup_save_and_continue),
            busy = busy,
            enabled = !model.isReading,
            tag = BusinessTags.SAVE,
            onClick = @DontMemoize { model.save() },
        )
    }
}

@Composable
private fun BusinessPlace(model: SetupBusinessModel) {
    val language = appLanguage()
    SetupPicker(
        label = stringResource(R.string.setup_business_country_label),
        placeholder = stringResource(R.string.setup_business_country_placeholder),
        selected = model.country,
        options = SetupPlaces.countries.map { SetupOption(it, SetupPlaces.countryName(it, language)) },
        tag = BusinessTags.COUNTRY,
        onSelect = @DontMemoize { model.chooseCountry(it) },
        error = stringResource(
            R.string.setup_business_country_required
        ).takeIf { model.showErrors && model.countryMissing },
    )
    val list = model.subdivisions
    if (list != null) {
        SetupPicker(
            label = stringResource(
                if (model.country == "CA") {
                    R.string.setup_business_subdivision_label_ca
                } else {
                    R.string.setup_business_subdivision_label_us
                },
            ),
            placeholder = stringResource(R.string.setup_business_subdivision_placeholder),
            selected = model.subdivision,
            options = list.map { SetupOption(it.code, it.name(language)) },
            tag = BusinessTags.SUBDIVISION,
            onSelect = @DontMemoize { model.subdivision = it },
            error = stringResource(R.string.setup_business_subdivision_required)
                .takeIf { model.showErrors && model.subdivisionMissing },
        )
    } else if (model.country.isNotEmpty()) {
        SetupField(
            label = stringResource(R.string.setup_business_subdivision_label_other),
            value = model.subdivision,
            onValueChange = @DontMemoize { model.subdivision = it },
            tag = BusinessTags.SUBDIVISION,
        )
    }
    SetupField(
        label = stringResource(R.string.setup_business_city_label),
        value = model.city,
        onValueChange = @DontMemoize { model.city = it },
        tag = BusinessTags.CITY,
        error = stringResource(R.string.setup_business_city_required).takeIf { model.showErrors && model.cityMissing },
    )
}

@Composable
private fun BusinessLanguage(model: SetupBusinessModel) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
        Eyebrow(stringResource(R.string.setup_business_language_label))
        SetupLanguage.entries.forEach { language ->
            SetupChoiceRow(
                title = stringResource(
                    if (language == SetupLanguage.FR) {
                        R.string.setup_business_language_fr
                    } else {
                        R.string.setup_business_language_en
                    },
                ),
                selected = model.language == language,
                tag = SetupTags.choice("language", language.wire),
                onClick = @DontMemoize { model.language = language },
            )
        }
    }
}

/** The zone a save writes ([SetupBusinessModel.timezone]), so a zone chosen in step 4 shows here as it is. */
@Composable
private fun zoneSentence(model: SetupBusinessModel): String {
    val timezone = model.timezone ?: return stringResource(R.string.setup_business_timezone_unknown)
    val zone = model.zone
    return if (zone != null && zone.timezone == timezone && zone.multiZone) {
        stringResource(R.string.setup_business_timezone_multi, timezone)
    } else {
        stringResource(R.string.setup_business_timezone_value, timezone)
    }
}

internal object BusinessTags {
    const val WEBSITE = "district-setup-business-website"
    const val READ_WEBSITE = "district-setup-business-read"
    const val NOTICE = "district-setup-business-notice"
    const val NAME = "district-setup-business-name"
    const val WHAT = "district-setup-business-what"
    const val COUNTRY = "district-setup-business-country"
    const val SUBDIVISION = "district-setup-business-subdivision"
    const val CITY = "district-setup-business-city"
    const val STREET = "district-setup-business-street"
    const val TIMEZONE = "district-setup-business-timezone"
    const val SAVE_ERROR = "district-setup-business-save-error"
    const val SAVE = "district-setup-business-save"
    const val OPEN_SKILLS = "district-setup-business-open-skills"
}
