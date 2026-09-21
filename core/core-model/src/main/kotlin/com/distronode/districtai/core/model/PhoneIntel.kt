package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `phoneIntel`: what the server can say about one phone number. Carried on every [CallSummary]
 * (for the OTHER party: the caller on an inbound call, the dialled number on an outbound one) and
 * beside the contact in [ContactDetailResponse].
 *
 * ⛔ EVERY NULL MEANS "NOBODY KNOWS", NEVER "CHECKED AND EMPTY", and the server sends them rather
 * than omitting the keys:
 *   - [region] is null outside Canada and the United States (the server's area-code table covers
 *     only those), and when a stored carrier lookup contradicts the number's country.
 *   - [lineType] and [carrier] are null unless a PAID carrier lookup is stored on the contact.
 *     Number metadata cannot tell a North American mobile from a landline, so the server never
 *     guesses them, and neither may the app.
 *
 * ⚠️ The whole block is null on an outbound call with no recorded callee (older rows)
 * and for a stored "number" that does not parse (telephony writes prose such as "Inbound SIP
 * Caller" when it has no caller ID). Every field defaults so an older server, which sends no block
 * at all, still decodes.
 */
@Serializable
data class PhoneIntel(
    /** ISO 3166-1 alpha-2, e.g. "CA". A stored carrier answer wins over the number's metadata. */
    val country: String? = null,
    /** English display name of [country], e.g. "Canada". */
    val countryName: String? = null,
    /** e.g. "(416) 555-0142". */
    val nationalFormat: String? = null,
    /** e.g. "+1 416 555 0142". Prefer this outside +1, where a national format hides the country. */
    val internationalFormat: String? = null,
    val region: PhoneRegion? = null,
    /** "mobile" | "landline" | "voip" | …, from a stored carrier lookup only. */
    val lineType: String? = null,
    /** The carrier holding the number, from a stored carrier lookup only. */
    val carrier: String? = null,
)

/** A Canadian province or US state, from the number's area code. */
@Serializable
data class PhoneRegion(
    /** ISO 3166-2 subdivision suffix, e.g. "ON". */
    val code: String,
    /** e.g. "Ontario". */
    val name: String,
    /** Present only where the area-code table names one. */
    val city: String? = null,
)
