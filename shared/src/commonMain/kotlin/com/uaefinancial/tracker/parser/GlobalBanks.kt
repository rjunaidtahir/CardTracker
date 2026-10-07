package com.uaefinancial.tracker.parser

/**
 * A directory of well-known banks by country. It is used to (1) offer a country at first run, (2) put a proper bank
 * name on a chat that looks like a bank ("HDFCBK" -> "HDFC Bank"), and (3) pre-select a country's banks.
 *
 * Sender IDs are listed only where we are confident of them; a bank with no sender ID is still recognised by its name
 * (the sender's name, or the bank's name inside the message). Reading rules are never guessed here: a bank in this
 * list is read by the smart reader until a rule built from real messages exists.
 */
object GlobalBanks {

    data class Country(val code: String, val name: String)

    data class DirBank(
        val name: String,
        val country: String,
        val aliases: List<String> = emptyList(),
        val senderIds: List<String> = emptyList(),
    )

    val countries: List<Country> = listOf(
        Country("AE", "United Arab Emirates"), Country("SA", "Saudi Arabia"), Country("QA", "Qatar"), Country("KW", "Kuwait"),
        Country("BH", "Bahrain"), Country("OM", "Oman"), Country("JO", "Jordan"), Country("EG", "Egypt"),
        Country("IN", "India"), Country("PK", "Pakistan"), Country("BD", "Bangladesh"), Country("LK", "Sri Lanka"), Country("NP", "Nepal"),
        Country("GB", "United Kingdom"), Country("IE", "Ireland"), Country("DE", "Germany"), Country("FR", "France"),
        Country("ES", "Spain"), Country("IT", "Italy"), Country("NL", "Netherlands"), Country("BE", "Belgium"),
        Country("PT", "Portugal"), Country("AT", "Austria"), Country("FI", "Finland"), Country("GR", "Greece"),
        Country("CH", "Switzerland"), Country("SE", "Sweden"), Country("NO", "Norway"), Country("DK", "Denmark"),
        Country("PL", "Poland"), Country("CZ", "Czechia"), Country("HU", "Hungary"), Country("RO", "Romania"), Country("TR", "Turkey"),
        Country("US", "United States"), Country("CA", "Canada"),
    )

    fun countryName(code: String?): String? = countries.firstOrNull { it.code.equals(code, ignoreCase = true) }?.name

    val banks: List<DirBank> = listOf(
        // Saudi Arabia
        DirBank("Al Rajhi Bank", "SA", listOf("AlRajhi", "Rajhi Bank", "Al Rajhi")),
        DirBank("Saudi National Bank", "SA", listOf("SNB", "AlAhli", "Al Ahli Bank", "NCB")),
        DirBank("Riyad Bank", "SA", listOf("Riyadbank", "Riyad")),
        DirBank("SABB", "SA", listOf("Saudi British Bank")),
        DirBank("Banque Saudi Fransi", "SA", listOf("BSF", "Saudi Fransi")),
        DirBank("Alinma Bank", "SA", listOf("Alinma")),
        DirBank("Arab National Bank", "SA", listOf("ANB")),
        // Qatar
        DirBank("Qatar National Bank", "QA", listOf("QNB")),
        DirBank("Commercial Bank of Qatar", "QA", listOf("CBQ", "Commercial Bank")),
        DirBank("Doha Bank", "QA", listOf("DohaBank")),
        DirBank("Masraf Al Rayan", "QA", listOf("Al Rayan", "MARAR")),
        DirBank("Qatar Islamic Bank", "QA", listOf("QIB")),
        // Kuwait
        DirBank("National Bank of Kuwait", "KW", listOf("NBK")),
        DirBank("Kuwait Finance House", "KW", listOf("KFH")),
        DirBank("Gulf Bank", "KW", listOf("GulfBank")),
        DirBank("Burgan Bank", "KW", listOf("Burgan")),
        DirBank("Warba Bank", "KW", listOf("Warba")),
        // Bahrain, Oman, Jordan, Egypt
        DirBank("National Bank of Bahrain", "BH", listOf("NBB")),
        DirBank("Bank of Bahrain and Kuwait", "BH", listOf("BBK")),
        DirBank("Bank Muscat", "OM", listOf("BankMuscat", "Muscat Bank")),
        DirBank("Bank Dhofar", "OM", listOf("BankDhofar", "Dhofar")),
        DirBank("National Bank of Oman", "OM", listOf("NBO")),
        DirBank("Arab Bank", "JO", listOf("ArabBank")),
        DirBank("Housing Bank", "JO", listOf("HBTF", "Housing Bank for Trade and Finance")),
        DirBank("National Bank of Egypt", "EG", listOf("NBE")),
        DirBank("Banque Misr", "EG", listOf("BanqueMisr", "Misr")),
        DirBank("CIB Egypt", "EG", listOf("CIB", "Commercial International Bank")),
        // India (DLT headers are widely published; the app also matches the operator prefix, e.g. "AX-HDFCBK-S")
        DirBank("HDFC Bank", "IN", listOf("HDFC"), listOf("HDFCBK")),
        DirBank("State Bank of India", "IN", listOf("SBI"), listOf("SBIINB", "SBIPSG", "SBICRD")),
        DirBank("ICICI Bank", "IN", listOf("ICICI"), listOf("ICICIB")),
        DirBank("Axis Bank", "IN", listOf("Axis"), listOf("AXISBK")),
        DirBank("Kotak Mahindra Bank", "IN", listOf("Kotak"), listOf("KOTAKB")),
        DirBank("Punjab National Bank", "IN", listOf("PNB"), listOf("PNBSMS")),
        DirBank("Bank of Baroda", "IN", listOf("BoB", "BOB"), listOf("BOBTXN")),
        DirBank("Canara Bank", "IN", listOf("Canara"), listOf("CANBNK")),
        DirBank("Yes Bank", "IN", listOf("YesBank"), listOf("YESBNK")),
        DirBank("IDFC FIRST Bank", "IN", listOf("IDFC"), listOf("IDFCFB")),
        DirBank("IndusInd Bank", "IN", listOf("IndusInd"), listOf("INDBNK")),
        DirBank("Union Bank of India", "IN", listOf("UnionBank")),
        // Pakistan
        DirBank("HBL", "PK", listOf("Habib Bank", "Habib Bank Limited")),
        DirBank("UBL", "PK", listOf("United Bank", "United Bank Limited")),
        DirBank("MCB Bank", "PK", listOf("MCB", "Muslim Commercial Bank")),
        DirBank("Allied Bank", "PK", listOf("ABL", "Allied Bank Limited")),
        DirBank("Meezan Bank", "PK", listOf("Meezan")),
        DirBank("Bank Alfalah", "PK", listOf("Alfalah", "BAFL")),
        DirBank("Standard Chartered Pakistan", "PK", listOf("Standard Chartered", "StanChart")),
        DirBank("Faysal Bank", "PK", listOf("Faysal")),
        DirBank("Askari Bank", "PK", listOf("Askari")),
        DirBank("Bank Al Habib", "PK", listOf("BAHL", "AlHabib")),
        DirBank("Habib Metropolitan Bank", "PK", listOf("HabibMetro", "HMB")),
        DirBank("JS Bank", "PK", listOf("JSBank")),
        DirBank("Soneri Bank", "PK", listOf("Soneri")),
        DirBank("NBP", "PK", listOf("National Bank of Pakistan")),
        DirBank("Easypaisa", "PK", listOf("Telenor Microfinance", "EasyPaisa")),
        DirBank("JazzCash", "PK", listOf("Jazz Cash", "Mobilink Microfinance")),
        // Bangladesh, Sri Lanka, Nepal
        DirBank("BRAC Bank", "BD", listOf("BRAC")),
        DirBank("Dutch-Bangla Bank", "BD", listOf("DBBL", "Dutch Bangla")),
        DirBank("City Bank", "BD", listOf("The City Bank")),
        DirBank("bKash", "BD", listOf("bKash Limited")),
        DirBank("Commercial Bank of Ceylon", "LK", listOf("ComBank", "Commercial Bank")),
        DirBank("Sampath Bank", "LK", listOf("Sampath")),
        DirBank("Hatton National Bank", "LK", listOf("HNB")),
        DirBank("Bank of Ceylon", "LK", listOf("BOC")),
        DirBank("Nabil Bank", "NP", listOf("Nabil")),
        DirBank("Global IME Bank", "NP", listOf("GlobalIME", "Global IME")),
        // United Kingdom and Ireland
        DirBank("Barclays", "GB", listOf("Barclaycard")),
        DirBank("HSBC UK", "GB", listOf("HSBC")),
        DirBank("Lloyds Bank", "GB", listOf("Lloyds")),
        DirBank("NatWest", "GB", listOf("National Westminster", "Natwest Bank")),
        DirBank("Santander UK", "GB", listOf("Santander")),
        DirBank("Monzo", "GB", listOf("Monzo Bank")),
        DirBank("Starling Bank", "GB", listOf("Starling")),
        DirBank("Halifax", "GB", listOf("Halifax Bank")),
        DirBank("Nationwide", "GB", listOf("Nationwide Building Society")),
        DirBank("Bank of Ireland", "IE", listOf("BOI")),
        DirBank("AIB", "IE", listOf("Allied Irish Banks")),
        // Europe
        DirBank("Deutsche Bank", "DE", listOf("DB")),
        DirBank("Commerzbank", "DE", listOf("Commerz")),
        DirBank("Sparkasse", "DE", listOf("S-Sparkasse")),
        DirBank("N26", "DE", listOf("N26 Bank")),
        DirBank("BNP Paribas", "FR", listOf("BNP")),
        DirBank("Crédit Agricole", "FR", listOf("Credit Agricole")),
        DirBank("Société Générale", "FR", listOf("Societe Generale", "SG")),
        DirBank("CaixaBank", "ES", listOf("La Caixa", "Caixa")),
        DirBank("BBVA", "ES"),
        DirBank("Banco Santander", "ES", listOf("Santander")),
        DirBank("Intesa Sanpaolo", "IT", listOf("Intesa")),
        DirBank("UniCredit", "IT", listOf("Unicredit")),
        DirBank("ING", "NL", listOf("ING Bank")),
        DirBank("Rabobank", "NL", listOf("Rabo")),
        DirBank("ABN AMRO", "NL", listOf("ABN")),
        DirBank("KBC", "BE", listOf("KBC Bank")),
        DirBank("Belfius", "BE"),
        DirBank("UBS", "CH", listOf("UBS Switzerland")),
        DirBank("PostFinance", "CH", listOf("Post Finance")),
        DirBank("Swedbank", "SE", listOf("Swed Bank")),
        DirBank("SEB", "SE", listOf("Skandinaviska Enskilda Banken")),
        DirBank("Handelsbanken", "SE", listOf("Svenska Handelsbanken")),
        DirBank("DNB", "NO", listOf("DNB Bank")),
        DirBank("Danske Bank", "DK", listOf("Danske")),
        DirBank("PKO Bank Polski", "PL", listOf("PKO", "PKO BP")),
        DirBank("mBank", "PL", listOf("m Bank")),
        DirBank("Ziraat Bankası", "TR", listOf("Ziraat", "Ziraat Bank")),
        DirBank("İş Bankası", "TR", listOf("Is Bankasi", "Isbank", "İşbank")),
        DirBank("Garanti BBVA", "TR", listOf("Garanti")),
        DirBank("Akbank", "TR"),
        // North America
        DirBank("Chase", "US", listOf("JPMorgan Chase", "Chase Bank")),
        DirBank("Bank of America", "US", listOf("BofA", "BoA")),
        DirBank("Wells Fargo", "US", listOf("WellsFargo")),
        DirBank("Citi", "US", listOf("Citibank", "Citi Bank")),
        DirBank("Capital One", "US", listOf("CapitalOne")),
        DirBank("American Express", "US", listOf("Amex", "AmEx")),
        DirBank("Discover", "US", listOf("Discover Card")),
        DirBank("U.S. Bank", "US", listOf("US Bank", "USBank")),
        DirBank("PNC Bank", "US", listOf("PNC")),
        DirBank("Royal Bank of Canada", "CA", listOf("RBC")),
        DirBank("TD Bank", "CA", listOf("TD", "TD Canada Trust")),
        DirBank("Scotiabank", "CA", listOf("Bank of Nova Scotia")),
        DirBank("BMO", "CA", listOf("Bank of Montreal")),
        DirBank("CIBC", "CA", listOf("Canadian Imperial Bank of Commerce")),
        DirBank("Tangerine", "CA"),
    )

    @kotlin.concurrent.Volatile
    private var extra: List<DirBank> = emptyList()

    /** Banks added by the signed rules file (see RulesPack). Replaces the previous set. */
    fun setExtra(list: List<DirBank>) { extra = list }

    fun all(): List<DirBank> = banks + extra

    fun forCountry(code: String?): List<DirBank> =
        if (code == null) emptyList() else all().filter { it.country.equals(code, ignoreCase = true) }

    private fun key(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    /**
     * The directory bank a chat probably is, from the sender's name first, then (when the sender is only a number)
     * from a bank's name written in the message. Short, easily confused names (under 4 characters) must match the
     * whole sender, so "DB" does not match every sender with D and B in it. Null when there is no confident match.
     */
    fun match(sender: String, text: String? = null, preferCountry: String? = null): DirBank? {
        val sk = SmsParser.senderKey(sender)
        val hits = mutableListOf<DirBank>()
        for (b in all()) {
            val names = (listOf(b.name) + b.aliases).map(::key).filter { it.isNotEmpty() }
            val ids = b.senderIds.map(::key)
            val bySender = ids.any { it == sk } || names.any { n ->
                sk == n || (n.length >= 4 && sk.contains(n) && sk.length <= n.length + 6)
            }
            if (bySender) hits += b
        }
        if (hits.isEmpty() && text != null) {
            val tk = key(text)
            for (b in all()) {
                // A bank's full name in the text: long names only, so a stray "DB" or "SG" never matches.
                if (key(b.name).length >= 7 && tk.contains(key(b.name))) hits += b
            }
        }
        if (hits.isEmpty()) return null
        return hits.firstOrNull { it.country.equals(preferCountry, ignoreCase = true) } ?: hits.first()
    }
}
