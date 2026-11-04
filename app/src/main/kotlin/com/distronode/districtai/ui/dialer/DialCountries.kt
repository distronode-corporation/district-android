package com.distronode.districtai.ui.dialer

/**
 * The data [DialPlan] reads by: every country the picker lists, with its calling code, and the few
 * facts about numbering plans the formatter needs.
 *
 * ⚠️ district-ios `DialPlan+Countries.swift`, TABLE FOR TABLE. A country added here and not there
 * (or the reverse) makes the same pasted number name two different places on two clients.
 *
 * ⚠️ ONE COUNTRY PER CODE OUTSIDE +1 AND +7. Territories that share a code with a larger country
 * (Guernsey and Jersey on +44, the Vatican on +39, Mayotte on +262) are left out rather than listed
 * second, so a pasted number never names a place it cannot tell apart.
 */
object DialCountries {

    data class Country(val iso: String, val code: String)

    val countries: List<Country> = (
        "CA 1|US 1|AF 93|AL 355|DZ 213|AS 1|AD 376|AO 244|AI 1|AG 1|AR 54|AM 374|AW 297|AU 61|AT 43|" +
            "AZ 994|BS 1|BH 973|BD 880|BB 1|BY 375|BE 32|BZ 501|BJ 229|BM 1|BT 975|BO 591|BA 387|BW 267|" +
            "BR 55|VG 1|BN 673|BG 359|BF 226|BI 257|KH 855|CM 237|CV 238|KY 1|CF 236|TD 235|CL 56|CN 86|" +
            "CO 57|KM 269|CG 242|CD 243|CK 682|CR 506|CI 225|HR 385|CU 53|CW 599|CY 357|CZ 420|DK 45|" +
            "DJ 253|DM 1|DO 1|EC 593|EG 20|SV 503|GQ 240|ER 291|EE 372|SZ 268|ET 251|FK 500|FO 298|FJ 679|" +
            "FI 358|FR 33|GF 594|PF 689|GA 241|GM 220|GE 995|DE 49|GH 233|GI 350|GR 30|GL 299|GD 1|GP 590|" +
            "GU 1|GT 502|GN 224|GW 245|GY 592|HT 509|HN 504|HK 852|HU 36|IS 354|IN 91|ID 62|IR 98|IQ 964|" +
            "IE 353|IL 972|IT 39|JM 1|JP 81|JO 962|KZ 7|KE 254|KI 686|XK 383|KW 965|KG 996|LA 856|LV 371|" +
            "LB 961|LS 266|LR 231|LY 218|LI 423|LT 370|LU 352|MO 853|MG 261|MW 265|MY 60|MV 960|ML 223|" +
            "MT 356|MH 692|MQ 596|MR 222|MU 230|MX 52|FM 691|MD 373|MC 377|MN 976|ME 382|MS 1|MA 212|" +
            "MZ 258|MM 95|NA 264|NR 674|NP 977|NL 31|NC 687|NZ 64|NI 505|NE 227|NG 234|NU 683|KP 850|" +
            "MK 389|MP 1|NO 47|OM 968|PK 92|PW 680|PS 970|PA 507|PG 675|PY 595|PE 51|PH 63|PL 48|PT 351|" +
            "PR 1|QA 974|RE 262|RO 40|RU 7|RW 250|KN 1|LC 1|PM 508|VC 1|WS 685|SM 378|ST 239|SA 966|" +
            "SN 221|RS 381|SC 248|SL 232|SG 65|SX 1|SK 421|SI 386|SB 677|SO 252|ZA 27|KR 82|SS 211|ES 34|" +
            "LK 94|SD 249|SR 597|SE 46|CH 41|SY 963|TW 886|TJ 992|TZ 255|TH 66|TL 670|TG 228|TO 676|TT 1|" +
            "TN 216|TR 90|TM 993|TC 1|TV 688|VI 1|UG 256|UA 380|AE 971|GB 44|UY 598|UZ 998|VU 678|VE 58|" +
            "VN 84|WF 681|YE 967|ZM 260|ZW 263"
        )
        .split('|')
        .map { pair ->
            val (iso, code) = pair.split(' ')
            Country(iso, code)
        }

    /**
     * Countries whose national numbers carry a trunk `0` that is dropped after the calling code:
     * `020 7946 0958` in London is `+44 20 7946 0958`.
     *
     * ⚠️ EVERY COUNTRY HERE WHOSE NATIONAL PREFIX IS `0` IN libphonenumber (libphonenumber-js 1.13.14;
     * Seer on district-core-rust #63 found Venezuela, Ecuador and Uruguay missing). One missing would
     * keep a typed leading `0` in the number: `0412 123 4567` in Venezuela sent `+58 0412…`.
     * The same list is in district-ios, district-macos and district-core-rust.
     */
    val trunkZero: Set<String> = setOf(
        "AE", "AF", "AL", "AM", "AR", "AT", "AU", "AZ", "BA", "BD", "BE", "BG", "BO", "BR", "CD", "CH", "CN",
        "CO", "CU", "DE", "DZ", "EC", "EG", "ER", "ET", "FI", "FR", "GB", "GE", "GF", "GH", "GP", "HR", "ID",
        "IE", "IL", "IN", "IQ", "IR", "JO", "JP", "KE", "KG", "KH", "KI", "KP", "KR", "LA", "LB", "LI", "LK",
        "LR", "LT", "LY", "MA", "MC", "MD", "ME", "MG", "MK", "MM", "MN", "MQ", "MW", "MY", "NA", "NG", "NL",
        "NP", "NZ", "PE", "PH", "PK", "PM", "PS", "PY", "RE", "RO", "RS", "RW", "SA", "SD", "SE", "SI", "SK",
        "SL", "SO", "SS", "SY", "TH", "TR", "TW", "TZ", "UA", "UG", "UY", "VE", "VN", "XK", "YE", "ZA", "ZM",
        "ZW",
    )

    /**
     * Trunk prefixes that are not a single `0`: libphonenumber's national prefix for every country
     * outside +1 that has one and it is not `0` (libphonenumber-js 1.13.14). Without them `8 495 123
     * 4567` typed in Russia would keep its `8` and send `+7 849…`, a wrong number that still dials
     * (Seer on district-ios #95). Belarus is `8`, not `80`: `80` is one of [parsingTrunkPrefixes].
     */
    private val otherTrunkPrefixes: Map<String, String> =
        mapOf("BY" to "8", "HU" to "06", "KZ" to "8", "MH" to "1", "RU" to "8", "TM" to "8")

    /**
     * The other prefixes libphonenumber strips when it reads a national number, where its
     * `national_prefix_for_parsing` is a plain list of them: Belarus `0|80?`, Lithuania `[08]` (its trunk
     * prefix became `0` in 2025, and the old `8` is still dialled) and the UK's `180020`. They are read,
     * never shown: the local form keeps [trunkPrefix]. ⚠️ The rules that are not a plain list (carrier
     * codes and area-code rewrites: AR, AU, BO, BR, CN, CO, JP, KR, LI, MG) are not modelled. The same
     * table is in the other app repos and district-core-rust.
     */
    val parsingTrunkPrefixes: Map<String, List<String>> =
        mapOf("BY" to listOf("80", "0"), "GB" to listOf("180020"), "LT" to listOf("8"))

    /**
     * The +1 toll-free area codes. ⚠️ They belong to the whole plan, so they never name a country and
     * never vote for a workspace's home country.
     */
    private val nanpTollFree: Set<String> = setOf("800", "833", "844", "855", "866", "877", "888")

    /**
     * The +1 area codes the web's table gives no country (`nanp-area-codes.ts`, `country: null`): the
     * toll-free ones and the non-geographic 500, 600, 700, 710 and 900. The line under the field names
     * no country for them (`noCountryNumber`).
     */
    private val nanpNoCountry: Set<String> = nanpTollFree + setOf("500", "600", "700", "710", "900")

    /** Canadian area codes, so a +1 number reads Canada or the US by its first three digits. */
    private val canadianAreaCodes: Set<String> = (
        "204 226 236 249 250 257 263 289 306 343 354 365 367 368 382 387 403 416 418 428 431 437 438 450 " +
            "460 468 474 506 514 519 548 579 581 584 587 600 604 613 639 647 672 683 705 709 742 753 778 780 " +
            "782 807 819 825 867 873 879 902 905 942"
        ).split(' ').toSet()

    /**
     * The +1 area codes that belong to neither Canada nor the United States.
     *
     * ⛔ WITHOUT THIS A JAMAICAN NUMBER WOULD SAY "RINGS UNITED STATES", which is the wrong-country
     * disclosure this screen exists to prevent.
     */
    private val nanpAreaCountries: Map<String, String> = mapOf(
        "242" to "BS", "246" to "BB", "264" to "AI", "268" to "AG", "284" to "VG", "340" to "VI",
        "345" to "KY", "441" to "BM", "473" to "GD", "649" to "TC", "658" to "JM", "664" to "MS",
        "670" to "MP", "671" to "GU", "684" to "AS", "721" to "SX", "758" to "LC", "767" to "DM",
        "784" to "VC", "787" to "PR", "809" to "DO", "829" to "DO", "849" to "DO", "868" to "TT",
        "869" to "KN", "876" to "JM", "939" to "PR",
    )

    private val codesByCountry: Map<String, String> = countries.associate { it.iso to it.code }

    /**
     * ⚠️ THE FIRST COUNTRY LISTED FOR A CODE IS THE ONE A PASTED `+` NUMBER RESOLVES TO, except +1 and
     * +7, which [DialPlan] reads by their digits.
     */
    private val countriesByCode: Map<String, String> = countries
        .groupBy { it.code }
        .mapValues { (_, listed) -> listed.first().iso }

    fun callingCode(iso: String): String? = codesByCountry[iso]

    fun firstCountryForCode(code: String): String? = countriesByCode[code]

    fun isNanp(iso: String): Boolean = codesByCountry[iso] == "1"

    /**
     * Which +1 country an area code belongs to: Canada, a Caribbean or Pacific member of the plan, or
     * else the United States. Before three digits, [fallback] when it is a +1 country, else the US.
     */
    fun nanpCountry(nsn: String, fallback: String?): String {
        val plusOne = fallback?.takeIf(::isNanp) ?: "US"
        // ⚠️ A TOLL-FREE CODE (800, 888 …) BELONGS TO NO ONE COUNTRY, so it keeps the +1 country
        // already on screen rather than claiming the United States.
        if (nsn.length < AREA_CODE_LENGTH || isNanpTollFree(nsn)) return plusOne
        val area = nsn.take(AREA_CODE_LENGTH)
        if (area in canadianAreaCodes) return "CA"
        return nanpAreaCountries[area] ?: "US"
    }

    /** Whether a +1 national number is toll-free, shared by every country of the plan. */
    fun isNanpTollFree(nsn: String): Boolean = nsn.take(AREA_CODE_LENGTH) in nanpTollFree

    /** Whether a +1 national number belongs to no one country, as the web's table has it. */
    fun isNanpNoCountry(nsn: String): Boolean = nsn.take(AREA_CODE_LENGTH) in nanpNoCountry

    /**
     * The trunk prefix dialled before a national number inside the country, or null: `0` for most, `8`
     * in Russia, Kazakhstan, Belarus and Turkmenistan, `06` in Hungary, `1` in the Marshall Islands.
     */
    fun trunkPrefix(iso: String): String? = otherTrunkPrefixes[iso] ?: "0".takeIf { iso in trunkZero }

    /**
     * Every prefix a national number typed in [iso] may start with that is not part of the number: the
     * trunk prefix and [parsingTrunkPrefixes], longest first, so Belarus's `80` is tried before its `8`.
     */
    fun nationalPrefixes(iso: String): List<String> {
        val trunk = trunkPrefix(iso) ?: return emptyList()
        return (listOf(trunk) + parsingTrunkPrefixes[iso].orEmpty()).sortedByDescending { it.length }
    }

    /** The flag, as the two regional-indicator letters Android draws as one emoji. */
    fun flag(iso: String): String = buildString {
        for (letter in iso.uppercase()) appendCodePoint(REGIONAL_INDICATOR_A + (letter - 'A'))
    }

    /**
     * The country most of a workspace's numbers belong to; on a tie, the first listed. Null when none
     * of them reads as a known country.
     *
     * ⛔ READ FROM `GET /api/district/workspace/provider/numbers`, WHICH THE APP ALREADY DECODES. No
     * response field was added for it: a new field breaks the mobile contracts.
     */
    fun homeCountry(numbers: List<String>): String? {
        val counts = linkedMapOf<String, Int>()
        for (reading in numbers.map { DialPlan.read(it, country = null) }) {
            // ⚠️ A TOLL-FREE +1 NUMBER IS NOT EVIDENCE OF ANY COUNTRY (Seer on district-ios #95): a
            // Canadian workspace with only 1-800 numbers would otherwise read as American.
            val tollFree = reading.callingCode == "1" && isNanpTollFree(reading.nationalNumber)
            val iso = reading.iso?.takeUnless { tollFree } ?: continue
            counts[iso] = (counts[iso] ?: 0) + 1
        }
        // ⚠️ `maxByOrNull` keeps the FIRST of equal maxima, and the map keeps insertion order.
        return counts.entries.maxByOrNull { it.value }?.key
    }

    private const val AREA_CODE_LENGTH = 3
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
}
