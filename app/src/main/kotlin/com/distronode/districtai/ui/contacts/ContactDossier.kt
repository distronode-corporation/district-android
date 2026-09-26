package com.distronode.districtai.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.Contact

/**
 * The District Global Intelligence dossier, and the two controls that manage it.
 *
 * ⛔ SPLIT OUT OF ContactDetailScreen.kt FOR detekt's 11-FUNCTION FILE CEILING, the same way
 * AnalyticsCards.kt was split from AnalyticsScreen.kt. Raising the threshold would be the shorter
 * change and the worse one — the ceiling is what keeps a screen file readable in one sitting.
 *
 * ⛔ AND THE STATE MACHINE HERE HAS FOUR OUTCOMES, NOT THREE. `dgiStatus` NULL means "no dossier
 * AND none queued" — `clear-intel` resets it to null deliberately so nothing re-crawls — while
 * "pending"/"crawling"/"synthesizing" mean one is running and "failed" means one finished badly.
 * Rendering null as pending would show a spinner for a job that does not exist and would leave
 * the enrich control disabled forever.
 */
@Composable
internal fun DossierSection(
    contact: Contact,
    canMutate: Boolean,
    busy: Boolean,
    onEnrich: () -> Unit,
    onClearIntel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        DossierStatus(contact)
        CompanyCard(contact)

        // ⚠️ The dossier body renders whatever arrived, whatever shape it is. See [dossierFields]:
        // this column can never be the thing that crashes the screen, because the fallback for an
        // unrecognised shape is the raw JSON rather than an exception.
        dossierFields(contact.intelligence).forEach { DossierFieldCard(it) }

        DossierActions(
            contact = contact,
            canMutate = canMutate,
            busy = busy,
            onEnrich = onEnrich,
            onClearIntel = onClearIntel,
        )
    }
}

/**
 * The one-line state of the dossier.
 *
 * ⚠️ A BADGE WHILE RUNNING, PLAIN TEXT OTHERWISE. The running state is the only one worth
 * drawing attention to — it is the one that will change on its own while the operator watches.
 */
@Composable
private fun DossierStatus(contact: Contact) {
    when {
        contact.dgiInProgress -> DistrictBadge(
            text = stringResource(R.string.contact_detail_dossier_pending),
            tone = Tone.Warning,
            modifier = Modifier.semantics {
                contentDescription = CONTACT_DETAIL_DOSSIER_PENDING_DESCRIPTION
            },
        )
        // ⚠️ The server's own message, shown verbatim. It names what the crawl could not do, and
        // a generic "enrichment failed" would throw that away — this is the only diagnostic an
        // operator ever gets for a pipeline that ran somewhere else.
        contact.dgiError != null -> LabelledCard(
            label = stringResource(R.string.contact_detail_dossier),
            value = stringResource(R.string.contact_detail_dossier_failed, contact.dgiError!!),
            modifier = Modifier.semantics { contentDescription = CONTACT_DETAIL_DOSSIER_FAILED_DESCRIPTION },
        )
        contact.intelligence == null -> LabelledCard(
            label = stringResource(R.string.contact_detail_dossier),
            value = stringResource(R.string.contact_detail_dossier_none),
            modifier = Modifier.semantics { contentDescription = CONTACT_DETAIL_DOSSIER_DESCRIPTION },
        )
        else -> LabelledCard(
            label = stringResource(R.string.contact_detail_dossier),
            value = stringResource(R.string.contact_detail_dossier_ready),
            modifier = Modifier.semantics { contentDescription = CONTACT_DETAIL_DOSSIER_DESCRIPTION },
        )
    }
}

/**
 * Firmographics.
 *
 * ⚠️ RENDERED HERE RATHER THAN BESIDE THE CONTACT'S OWN ATTRIBUTES, because it is not the
 * contact's own: `company` is written by the SAME enrichment pipeline that writes `intelligence`,
 * and `clear-intel` nulls both together. Showing it above with the phone number implied it was
 * something the operator had typed.
 */
@Composable
private fun CompanyCard(contact: Contact) {
    val company = contact.company ?: return
    val children = listOfNotNull(
        company.name?.takeIf { it.isNotBlank() }
            ?.let { DossierEntry(stringResource(R.string.contact_detail_company_name), it) },
        company.domain?.takeIf { it.isNotBlank() }
            ?.let { DossierEntry(stringResource(R.string.contact_detail_company_domain), it) },
        company.industry?.takeIf { it.isNotBlank() }
            ?.let { DossierEntry(stringResource(R.string.contact_detail_company_industry), it) },
    )
    // Every field empty is the same as no company at all — the column is `Json?` and an empty
    // object is a shape the pipeline genuinely writes.
    if (children.isEmpty()) return

    DossierFieldCard(
        field = DossierField(
            label = stringResource(R.string.contact_detail_company),
            children = children,
        ),
        modifier = Modifier.semantics { contentDescription = CONTACT_DETAIL_DOSSIER_COMPANY_DESCRIPTION },
    )
}

/** One dossier row: a scalar, a joined list, a heading with children, or raw JSON. */
@Composable
private fun DossierFieldCard(field: DossierField, modifier: Modifier = Modifier) {
    DistrictCard {
        Column(modifier = modifier.padding(DistrictTheme.spacing.gutter)) {
            Text(text = field.label, style = MaterialTheme.typography.labelSmall)
            field.value?.let {
                Text(
                    text = it,
                    // ⚠️ A DIFFERENT STYLE FOR RAW JSON, so a reader can tell "this is a shape we
                    // did not recognise" from "this is what the model wrote". The content is the
                    // same either way; the distinction is the point.
                    style = if (field.raw) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (field.raw) {
                        DistrictTheme.colors.mutedForeground
                    } else {
                        DistrictTheme.colors.foreground
                    },
                    modifier = Modifier
                        .padding(top = DistrictTheme.spacing.hairline)
                        .then(
                            if (field.raw) {
                                Modifier.semantics {
                                    contentDescription = CONTACT_DETAIL_DOSSIER_RAW_DESCRIPTION
                                }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
            field.children.forEach { child -> DossierChildRow(child) }
        }
    }
}

/** ⚠️ One level deep only — see [dossierFields]. A grandchild arrives already flattened to JSON. */
@Composable
private fun DossierChildRow(child: DossierEntry) {
    Column(modifier = Modifier.padding(top = DistrictTheme.spacing.tight)) {
        Text(text = child.label, style = MaterialTheme.typography.labelSmall)
        Text(
            text = child.value,
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
        )
    }
}

/**
 * The enrich and clear controls.
 *
 * ⛔ ENRICH IS OFFERED ONLY WHEN [Contact.dgiOfferable], WHICH IS NULL-OR-FAILED. Offering it
 * while a crawl is running would let one impatient tap buy a second external crawl and a second
 * LLM synthesis for a contact already being enriched — the endpoint is not idempotent and the
 * server's rate limit is fail-open, so nothing behind this button would stop it.
 *
 * ⛔ AND BOTH ARE GATED ON [canMutate]. Both routes exclude `viewer` server-side, so a viewer
 * would only ever earn a 403 they cannot act on.
 */
@Composable
private fun DossierActions(
    contact: Contact,
    canMutate: Boolean,
    busy: Boolean,
    onEnrich: () -> Unit,
    onClearIntel: () -> Unit,
) {
    if (!canMutate) return

    if (contact.dgiOfferable) {
        DistrictButton(
            text = stringResource(R.string.contact_detail_dossier_enrich),
            onClick = onEnrich,
            variant = ButtonVariant.Secondary,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = CONTACT_DETAIL_ENRICH_DESCRIPTION },
        )
    }

    // ⚠️ Offered whenever there is something to clear, INCLUDING a failed run — clearing is how a
    // failed dossier's error is dismissed and the contact returned to an enrichable state.
    if (contact.intelligence != null || contact.company != null || contact.dgiStatus != null) {
        TextButton(
            onClick = onClearIntel,
            enabled = !busy,
            modifier = Modifier.semantics {
                contentDescription = CONTACT_DETAIL_CLEAR_INTEL_DESCRIPTION
            },
        ) {
            Text(stringResource(R.string.contact_detail_dossier_clear))
        }
    }
}

/**
 * ⛔ CONFIRMED, BECAUSE CLEARING IS NOT RECOVERABLE AND IS NOT FREE TO UNDO. The dossier is
 * deleted from the row; getting it back means paying for another crawl and another model run.
 * The dialog says so rather than asking a bare "are you sure".
 */
@Composable
internal fun ClearIntelDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.contact_detail_clear_intel_confirm)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = CONTACT_DETAIL_CLEAR_INTEL_CONFIRM_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.contact_detail_clear_intel_confirmed))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.contact_detail_cancel))
            }
        },
    )
}

/**
 * A label over a value, in a card.
 *
 * ⚠️ Lives here rather than in ContactDetailScreen.kt only because that file is at its function
 * ceiling; it is still used by the contact's own attributes.
 */
@Composable
internal fun LabelledCard(label: String, value: String, modifier: Modifier = Modifier) {
    if (value.isBlank()) return
    DistrictCard {
        Column(modifier = modifier.padding(DistrictTheme.spacing.gutter)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
            )
        }
    }
}

/** Stable handles for tests; see the note on the analytics screen's constants. */
const val CONTACT_DETAIL_DOSSIER_PENDING_DESCRIPTION: String = "district-contact-detail-dossier-pending"
const val CONTACT_DETAIL_DOSSIER_FAILED_DESCRIPTION: String = "district-contact-detail-dossier-failed"
const val CONTACT_DETAIL_DOSSIER_COMPANY_DESCRIPTION: String = "district-contact-detail-dossier-company"
const val CONTACT_DETAIL_DOSSIER_RAW_DESCRIPTION: String = "district-contact-detail-dossier-raw"
const val CONTACT_DETAIL_ENRICH_DESCRIPTION: String = "district-contact-detail-enrich"
const val CONTACT_DETAIL_CLEAR_INTEL_DESCRIPTION: String = "district-contact-detail-clear-intel"
const val CONTACT_DETAIL_CLEAR_INTEL_CONFIRM_DESCRIPTION: String =
    "district-contact-detail-clear-intel-confirm"
