package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.Decimal

/**
 * Every currency the app understands, how banks write them, and approximate rates.
 *
 * Rates are "value of 1 unit in AED" (AED is only the internal pivot: amounts are shown in your home currency).
 * They are approximate (open.er-api.com, 28 Sep 2026) because the app has no internet; you can edit any rate in the
 * app, and converted amounts are always marked as estimates.
 *
 * Left out on purpose: codes that are ordinary words next to numbers in messages (ALL, TOP, CUP, SOS, PEN, BOB, MOP,
 * BAM, CVE as in "CVE-2026") and units that aren't separate circulating currencies (CNH, CLF, XDR, FOK, GGP, IMP, JEP,
 * KID, TVD, SHP, FKP, GIP, SLL, ZWL, HRK).
 */
object Currencies {

    /** Internal pivot for rates (the table below is "1 unit = x AED"). */
    const val PIVOT = "AED"

    val rateToAed: Map<String, Decimal> = mapOf(
        "AED" to "1",
        "AFN" to "0.0570418",
        "AMD" to "0.0101",
        "ANG" to "2.05168",
        "AOA" to "0.00398131",
        "ARS" to "0.00240983",
        "AUD" to "2.57543",
        "AWG" to "2.05168",
        "AZN" to "2.16073",
        "BBD" to "1.83625",
        "BDT" to "0.0298776",
        "BGN" to "2.13865",
        "BHD" to "9.76725",
        "BIF" to "0.00122776",
        "BMD" to "3.6725",
        "BND" to "2.87299",
        "BRL" to "0.70812",
        "BSD" to "3.6725",
        "BTN" to "0.0382817",
        "BWP" to "0.264704",
        "BYN" to "1.21452",
        "BZD" to "1.83625",
        "CAD" to "2.59576",
        "CDF" to "0.00158873",
        "CHF" to "4.42787",
        "CLP" to "0.00381652",
        "CNY" to "0.546545",
        "COP" to "0.00110144",
        "CRC" to "0.00810749",
        "CZK" to "0.171566",
        "DJF" to "0.0206644",
        "DKK" to "0.559877",
        "DOP" to "0.0617651",
        "DZD" to "0.027458",
        "EGP" to "0.0710217",
        "ERN" to "0.244833",
        "ETB" to "0.0226037",
        "EUR" to "4.18282",
        "FJD" to "1.63573",
        "GBP" to "4.8597",
        "GEL" to "1.41159",
        "GHS" to "0.317004",
        "GMD" to "0.0493705",
        "GNF" to "0.000417717",
        "GTQ" to "0.481029",
        "GYD" to "0.0175582",
        "HKD" to "0.468161",
        "HNL" to "0.136875",
        "HTG" to "0.0280673",
        "HUF" to "0.0114403",
        "IDR" to "0.000204931",
        "ILS" to "1.20591",
        "INR" to "0.0382816",
        "IQD" to "0.00280496",
        "IRR" to "0.00000229325",
        "ISK" to "0.0305394",
        "JMD" to "0.0232535",
        "JOD" to "5.17982",
        "JPY" to "0.0233093",
        "KES" to "0.0283537",
        "KGS" to "0.0419873",
        "KHR" to "0.000907033",
        "KMF" to "0.00850225",
        "KRW" to "0.00270835",
        "KWD" to "11.9109",
        "KYD" to "4.40699",
        "KZT" to "0.00829689",
        "LAK" to "0.000165064",
        "LBP" to "0.0000410335",
        "LKR" to "0.011134",
        "LRD" to "0.0213548",
        "LSL" to "0.225043",
        "LYD" to "0.574603",
        "MAD" to "0.382641",
        "MDL" to "0.208295",
        "MGA" to "0.000835376",
        "MKD" to "0.0683516",
        "MMK" to "0.00174718",
        "MNT" to "0.00102828",
        "MRU" to "0.091328",
        "MUR" to "0.0771234",
        "MVR" to "0.237871",
        "MWK" to "0.00210187",
        "MXN" to "0.207015",
        "MYR" to "0.901367",
        "MZN" to "0.0575198",
        "NAD" to "0.225043",
        "NGN" to "0.00276689",
        "NIO" to "0.099811",
        "NOK" to "0.38597",
        "NPR" to "0.0239261",
        "NZD" to "2.07686",
        "OMR" to "9.55146",
        "PAB" to "3.6725",
        "PGK" to "0.824986",
        "PHP" to "0.0588071",
        "PKR" to "0.0132631",
        "PLN" to "0.956178",
        "PYG" to "0.000619949",
        "QAR" to "1.00893",
        "RON" to "0.793388",
        "RSD" to "0.0356146",
        "RUB" to "0.0436154",
        "RWF" to "0.00248439",
        "SAR" to "0.979333",
        "SBD" to "0.45852",
        "SCR" to "0.250576",
        "SDG" to "0.00800679",
        "SEK" to "0.36993",
        "SGD" to "2.87299",
        "SLE" to "0.149204",
        "SRD" to "0.0974615",
        "SSP" to "0.000649658",
        "STN" to "0.170728",
        "SYP" to "0.0301791",
        "SZL" to "0.225043",
        "THB" to "0.109908",
        "TJS" to "0.398009",
        "TMT" to "1.0498",
        "TND" to "1.24874",
        "TRY" to "0.0750289",
        "TTD" to "0.540678",
        "TWD" to "0.115609",
        "TZS" to "0.00138429",
        "UAH" to "0.0818925",
        "UGX" to "0.000940533",
        "USD" to "3.6725",
        "UYU" to "0.0916679",
        "UZS" to "0.000310434",
        "VES" to "0.00428527",
        "VND" to "0.000141603",
        "VUV" to "0.0309983",
        "WST" to "1.3446",
        "XAF" to "0.00637669",
        "XCD" to "1.36019",
        "XCG" to "2.05168",
        "XOF" to "0.00637669",
        "XPF" to "0.0350521",
        "YER" to "0.015528",
        "ZAR" to "0.225042",
        "ZMW" to "0.186549",
        "ZWG" to "0.137911",
    ).mapValues { Decimal(it.value) }

    fun isCode(code: String): Boolean = code.uppercase() in rateToAed

    /** Currencies with 3 decimals (fils / baisa): "KWD 12.500" is twelve and a half. */
    val threeDecimals: Set<String> = setOf("KWD", "BHD", "OMR", "JOD", "TND", "LYD")

    /** Currencies written without decimals in practice ("IDR 1.500.000", "JPY 1,200"). */
    val noDecimals: Set<String> = setOf(
        "JPY", "KRW", "VND", "IDR", "CLP", "PYG", "ISK", "UGX", "RWF", "XAF", "XOF", "XPF", "KMF", "GNF", "BIF", "DJF",
        "VUV", "MGA", "IRR", "IQD", "LAK", "MMK", "KHR", "COP", "LBP", "SYP", "UZS", "HUF",
    )

    /** Dollar currencies: a bare "$" means your home currency when it's one of these, else USD. */
    private val dollars = setOf("USD", "CAD", "AUD", "NZD", "SGD", "HKD", "TWD", "MXN", "BSD", "BBD", "BZD", "BMD", "KYD", "JMD", "TTD", "XCD", "FJD", "LRD", "NAD", "SRD", "GYD", "SBD")
    private val rupees = setOf("INR", "PKR", "LKR", "NPR", "MUR", "SCR")
    private val kronor = setOf("SEK", "NOK", "DKK", "ISK")
    private val rials = setOf("SAR", "QAR", "OMR", "YER", "IRR")
    private val dinars = setOf("KWD", "BHD", "JOD", "IQD", "LYD", "TND", "DZD", "RSD", "MKD")

    /**
     * How messages write currencies other than the ISO code: symbols and local abbreviations, mapped to a code
     * (or to a family resolved with your home currency). Longest first when matching.
     */
    private val fixedSymbols: Map<String, String> = mapOf(
        "US$" to "USD", "HK$" to "HKD", "NZ$" to "NZD", "AU$" to "AUD", "A$" to "AUD", "CA$" to "CAD", "C$" to "CAD",
        "SG$" to "SGD", "S$" to "SGD", "R$" to "BRL", "MX$" to "MXN", "NT$" to "TWD",
        "€" to "EUR", "£" to "GBP", "₹" to "INR", "₩" to "KRW", "₺" to "TRY", "₦" to "NGN", "₱" to "PHP", "₫" to "VND",
        "฿" to "THB", "₪" to "ILS", "₴" to "UAH", "₸" to "KZT", "₼" to "AZN", "৳" to "BDT", "zł" to "PLN", "Kč" to "CZK",
        "SR" to "SAR", "S.R." to "SAR", "QR" to "QAR", "Q.R." to "QAR", "KD" to "KWD", "K.D." to "KWD", "BD" to "BHD",
        "B.D." to "BHD", "RO" to "OMR", "R.O." to "OMR", "RM" to "MYR", "Rp" to "IDR", "Tk" to "BDT", "TL" to "TRY",
        // Arabic
        "د.إ" to "AED", "درهم" to "AED", "ر.س" to "SAR", "ر.ق" to "QAR", "د.ك" to "KWD", "د.ب" to "BHD", "ر.ع" to "OMR", "ج.م" to "EGP",
    )

    /** Shared symbols: resolved with your home currency (a "$" in Canada is CAD). */
    private val familySymbols: Map<String, Pair<Set<String>, String>> = mapOf(
        "$" to (dollars to "USD"),
        "Rs" to (rupees to "INR"), "₨" to (rupees to "INR"),
        "kr" to (kronor to "SEK"),
        "¥" to (setOf("CNY", "JPY") to "JPY"),
        "ريال" to (rials to "SAR"),
        "دينار" to (dinars to "KWD"),
    )

    /** Every symbol / abbreviation, longest first (for building the money pattern). */
    val symbols: List<String> by lazy { (fixedSymbols.keys + familySymbols.keys).sortedByDescending { it.length } }

    /** The ISO code for [raw] as written in a message ("US$" → USD, "Rs." → your rupee, "Dhs" → AED), or null if unknown. */
    fun codeFor(raw: String, home: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val up = t.uppercase()
        if (up in rateToAed) return up
        if (up in listOf("DH", "DHS", "DIRHAM", "DIRHAMS")) return "AED"
        fixedSymbols[t]?.let { return it }
        // Case-insensitive abbreviations ("RS.", "rs", "KR", "Sr").
        val bare = t.trimEnd('.')
        fixedSymbols.entries.firstOrNull { it.key.trimEnd('.').equals(bare, ignoreCase = true) && it.key.any(Char::isLetter) }?.let { return it.value }
        familySymbols.entries.firstOrNull { it.key.equals(bare, ignoreCase = true) }?.let { (_, fam) ->
            return if (home.uppercase() in fam.first) home.uppercase() else fam.second
        }
        return null
    }

    /** The home currency for a country (ISO 3166 alpha-2), for apps that only know the region; null if unknown. */
    fun forRegion(countryCode: String): String? = regionCurrency[countryCode.uppercase()]

    private val regionCurrency: Map<String, String> = mapOf(
        "AE" to "AED", "SA" to "SAR", "QA" to "QAR", "KW" to "KWD", "BH" to "BHD", "OM" to "OMR", "JO" to "JOD", "EG" to "EGP",
        "IN" to "INR", "PK" to "PKR", "BD" to "BDT", "LK" to "LKR", "NP" to "NPR",
        "GB" to "GBP", "IE" to "EUR", "DE" to "EUR", "FR" to "EUR", "ES" to "EUR", "IT" to "EUR", "NL" to "EUR", "BE" to "EUR",
        "PT" to "EUR", "AT" to "EUR", "FI" to "EUR", "GR" to "EUR", "LU" to "EUR", "SK" to "EUR", "SI" to "EUR", "EE" to "EUR",
        "LV" to "EUR", "LT" to "EUR", "MT" to "EUR", "CY" to "EUR", "HR" to "EUR", "CH" to "CHF", "SE" to "SEK", "NO" to "NOK",
        "DK" to "DKK", "PL" to "PLN", "CZ" to "CZK", "HU" to "HUF", "RO" to "RON", "BG" to "BGN", "TR" to "TRY",
        "US" to "USD", "CA" to "CAD",
    )
}
