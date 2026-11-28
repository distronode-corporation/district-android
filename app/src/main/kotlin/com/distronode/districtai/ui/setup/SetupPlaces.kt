// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.setup

import com.distronode.districtai.l10n.AppLanguage
import java.util.Locale

/**
 * The time zone a place implies. [multiZone] is the web's flag for "this place spans more than one
 * zone", and step 1 then says to check the zone in step 4 rather than claiming it.
 */
data class SetupZone(val timezone: String, val multiZone: Boolean = false)

/**
 * The countries, provinces and states step 1 offers, and the time zone each one implies: the web's
 * `setup-places.ts`, entry for entry.
 *
 * ⚠️ PLACE NAMES, NOT COPY, SO NOT IN THE CATALOG. A province's French name is its name in French
 * ("Colombie-Britannique"), not a translation anyone reviews, and the web keeps the two side by side
 * in the same table for the same reason. A country's name comes from the system ([Locale]), as the
 * web's comes from `Intl`.
 */
object SetupPlaces {
    data class Subdivision(val code: String, val en: String, val fr: String, val zone: SetupZone) {
        fun name(language: AppLanguage): String = if (language == AppLanguage.FR) fr else en
    }

    private fun sub(code: String, en: String, fr: String, zone: String, multi: Boolean = false) =
        Subdivision(code, en, fr, SetupZone(zone, multi))

    val provinces: List<Subdivision> = listOf(
        sub("AB", "Alberta", "Alberta", "America/Edmonton"),
        sub("BC", "British Columbia", "Colombie-Britannique", "America/Vancouver", multi = true),
        sub("MB", "Manitoba", "Manitoba", "America/Winnipeg"),
        sub("NB", "New Brunswick", "Nouveau-Brunswick", "America/Moncton"),
        sub("NL", "Newfoundland and Labrador", "Terre-Neuve-et-Labrador", "America/St_Johns", multi = true),
        sub("NS", "Nova Scotia", "Nouvelle-Écosse", "America/Halifax"),
        sub("NT", "Northwest Territories", "Territoires du Nord-Ouest", "America/Yellowknife"),
        sub("NU", "Nunavut", "Nunavut", "America/Iqaluit", multi = true),
        sub("ON", "Ontario", "Ontario", "America/Toronto", multi = true),
        sub("PE", "Prince Edward Island", "Île-du-Prince-Édouard", "America/Halifax"),
        sub("QC", "Quebec", "Québec", "America/Toronto", multi = true),
        sub("SK", "Saskatchewan", "Saskatchewan", "America/Regina", multi = true),
        sub("YT", "Yukon", "Yukon", "America/Whitehorse"),
    )

    val states: List<Subdivision> = listOf(
        sub("AL", "Alabama", "Alabama", "America/Chicago"),
        sub("AK", "Alaska", "Alaska", "America/Anchorage", multi = true),
        sub("AZ", "Arizona", "Arizona", "America/Phoenix"),
        sub("AR", "Arkansas", "Arkansas", "America/Chicago"),
        sub("CA", "California", "Californie", "America/Los_Angeles"),
        sub("CO", "Colorado", "Colorado", "America/Denver"),
        sub("CT", "Connecticut", "Connecticut", "America/New_York"),
        sub("DE", "Delaware", "Delaware", "America/New_York"),
        sub("DC", "District of Columbia", "District de Columbia", "America/New_York"),
        sub("FL", "Florida", "Floride", "America/New_York", multi = true),
        sub("GA", "Georgia", "Géorgie", "America/New_York"),
        sub("HI", "Hawaii", "Hawaï", "Pacific/Honolulu"),
        sub("ID", "Idaho", "Idaho", "America/Boise", multi = true),
        sub("IL", "Illinois", "Illinois", "America/Chicago"),
        sub("IN", "Indiana", "Indiana", "America/Indiana/Indianapolis", multi = true),
        sub("IA", "Iowa", "Iowa", "America/Chicago"),
        sub("KS", "Kansas", "Kansas", "America/Chicago", multi = true),
        sub("KY", "Kentucky", "Kentucky", "America/New_York", multi = true),
        sub("LA", "Louisiana", "Louisiane", "America/Chicago"),
        sub("ME", "Maine", "Maine", "America/New_York"),
        sub("MD", "Maryland", "Maryland", "America/New_York"),
        sub("MA", "Massachusetts", "Massachusetts", "America/New_York"),
        sub("MI", "Michigan", "Michigan", "America/Detroit", multi = true),
        sub("MN", "Minnesota", "Minnesota", "America/Chicago"),
        sub("MS", "Mississippi", "Mississippi", "America/Chicago"),
        sub("MO", "Missouri", "Missouri", "America/Chicago"),
        sub("MT", "Montana", "Montana", "America/Denver"),
        sub("NE", "Nebraska", "Nebraska", "America/Chicago", multi = true),
        sub("NV", "Nevada", "Nevada", "America/Los_Angeles"),
        sub("NH", "New Hampshire", "New Hampshire", "America/New_York"),
        sub("NJ", "New Jersey", "New Jersey", "America/New_York"),
        sub("NM", "New Mexico", "Nouveau-Mexique", "America/Denver"),
        sub("NY", "New York", "New York", "America/New_York"),
        sub("NC", "North Carolina", "Caroline du Nord", "America/New_York"),
        sub("ND", "North Dakota", "Dakota du Nord", "America/Chicago", multi = true),
        sub("OH", "Ohio", "Ohio", "America/New_York"),
        sub("OK", "Oklahoma", "Oklahoma", "America/Chicago"),
        sub("OR", "Oregon", "Oregon", "America/Los_Angeles", multi = true),
        sub("PA", "Pennsylvania", "Pennsylvanie", "America/New_York"),
        sub("RI", "Rhode Island", "Rhode Island", "America/New_York"),
        sub("SC", "South Carolina", "Caroline du Sud", "America/New_York"),
        sub("SD", "South Dakota", "Dakota du Sud", "America/Chicago", multi = true),
        sub("TN", "Tennessee", "Tennessee", "America/Chicago", multi = true),
        sub("TX", "Texas", "Texas", "America/Chicago", multi = true),
        sub("UT", "Utah", "Utah", "America/Denver"),
        sub("VT", "Vermont", "Vermont", "America/New_York"),
        sub("VA", "Virginia", "Virginie", "America/New_York"),
        sub("WA", "Washington", "Washington", "America/Los_Angeles"),
        sub("WV", "West Virginia", "Virginie-Occidentale", "America/New_York"),
        sub("WI", "Wisconsin", "Wisconsin", "America/Chicago"),
        sub("WY", "Wyoming", "Wyoming", "America/Denver"),
    )

    /** Every other country step 1 offers, in the web's order, with its zone. */
    val countryZones: List<Pair<String, SetupZone>> = listOf(
        "AT" to SetupZone("Europe/Vienna"),
        "BE" to SetupZone("Europe/Brussels"),
        "BG" to SetupZone("Europe/Sofia"),
        "HR" to SetupZone("Europe/Zagreb"),
        "CY" to SetupZone("Asia/Nicosia"),
        "CZ" to SetupZone("Europe/Prague"),
        "DK" to SetupZone("Europe/Copenhagen"),
        "EE" to SetupZone("Europe/Tallinn"),
        "FI" to SetupZone("Europe/Helsinki"),
        "FR" to SetupZone("Europe/Paris"),
        "DE" to SetupZone("Europe/Berlin"),
        "GR" to SetupZone("Europe/Athens"),
        "HU" to SetupZone("Europe/Budapest"),
        "IE" to SetupZone("Europe/Dublin"),
        "IT" to SetupZone("Europe/Rome"),
        "LV" to SetupZone("Europe/Riga"),
        "LT" to SetupZone("Europe/Vilnius"),
        "LU" to SetupZone("Europe/Luxembourg"),
        "MT" to SetupZone("Europe/Malta"),
        "NL" to SetupZone("Europe/Amsterdam"),
        "PL" to SetupZone("Europe/Warsaw"),
        "PT" to SetupZone("Europe/Lisbon", multiZone = true),
        "RO" to SetupZone("Europe/Bucharest"),
        "SK" to SetupZone("Europe/Bratislava"),
        "SI" to SetupZone("Europe/Ljubljana"),
        "ES" to SetupZone("Europe/Madrid", multiZone = true),
        "SE" to SetupZone("Europe/Stockholm"),
        "IS" to SetupZone("Atlantic/Reykjavik"),
        "LI" to SetupZone("Europe/Vaduz"),
        "NO" to SetupZone("Europe/Oslo"),
        "GB" to SetupZone("Europe/London"),
        "CH" to SetupZone("Europe/Zurich"),
        "AU" to SetupZone("Australia/Sydney", multiZone = true),
        "NZ" to SetupZone("Pacific/Auckland"),
        "SG" to SetupZone("Asia/Singapore"),
        "HK" to SetupZone("Asia/Hong_Kong"),
        "JP" to SetupZone("Asia/Tokyo"),
        "IN" to SetupZone("Asia/Kolkata"),
        "PH" to SetupZone("Asia/Manila"),
    )

    /** `SETUP_COUNTRIES`: Canada and the United States first, then the rest. */
    val countries: List<String> = listOf("CA", "US") + countryZones.map { it.first }

    fun subdivisions(country: String): List<Subdivision>? = when (country) {
        "CA" -> provinces
        "US" -> states
        else -> null
    }

    /** The zone a place implies, or null while the province or state is not chosen. */
    fun zone(country: String, subdivision: String): SetupZone? {
        val list = subdivisions(country)
        return if (list != null) {
            list.firstOrNull { it.code == subdivision.uppercase() }?.zone
        } else {
            countryZones.firstOrNull { it.first == country }?.second
        }
    }

    fun defaultCountry(region: String?): String = when (region) {
        "ca" -> "CA"
        "us" -> "US"
        else -> ""
    }

    /** The zones step 4 offers for a country: its own, plus the one already stored. */
    fun zones(country: String, including: String): List<String> {
        val own = subdivisions(country)?.map { it.zone.timezone }
            ?: countryZones.filter { it.first == country }.map { it.second.timezone }
        val all = if (including.isEmpty()) own else own + including
        return all.distinct().sorted()
    }

    /** The country's name in the app's language, from the system. */
    fun countryName(code: String, language: AppLanguage): String {
        val display = if (language == AppLanguage.FR) Locale.CANADA_FRENCH else Locale.CANADA
        return Locale.Builder().setRegion(code).build().getDisplayCountry(display)
    }
}
