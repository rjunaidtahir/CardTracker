package com.junaid.cardtracker.parser

/**
 * ============================================================================
 *  CATEGORY RULES: the default categories and the keywords that auto-assign them
 * ============================================================================
 *
 * How a transaction gets its category (first match wins):
 *   1. You set it yourself on that transaction (kept even after Re-parse).
 *   2. A rule the app learned when you re-categorised a merchant ("apply to all").
 *   3. The keyword lists below, matched against the merchant text (case-insensitive).
 *   4. "Other".
 * Only spending-type transactions (purchases, refunds) get a category. Transfers,
 * money in and card payments don't.
 *
 * To add a keyword: put it in the right list below, then install and tap Re-parse.
 */
object CategoryRules {

    data class DefaultCategory(val id: Long, val name: String)

    // Ids are fixed so keyword rules, backups and learned rules stay stable. Add new ones at the end.
    const val GROCERIES = 1L
    const val DINING = 2L
    const val TRANSPORT = 3L
    const val UTILITIES = 4L
    const val SHOPPING = 5L
    const val TRAVEL = 6L
    const val HEALTH = 7L
    const val ENTERTAINMENT = 8L
    const val GOVERNMENT = 9L
    const val EDUCATION = 10L
    const val EMI_LOANS = 11L
    const val CASH = 12L
    const val INSURANCE = 13L
    const val OTHER = 14L

    val defaults: List<DefaultCategory> = listOf(
        DefaultCategory(GROCERIES, "Groceries"),
        DefaultCategory(DINING, "Dining & delivery"),
        DefaultCategory(TRANSPORT, "Fuel & transport"),
        DefaultCategory(UTILITIES, "Utilities & bills"),
        DefaultCategory(SHOPPING, "Shopping"),
        DefaultCategory(TRAVEL, "Travel"),
        DefaultCategory(HEALTH, "Health"),
        DefaultCategory(ENTERTAINMENT, "Entertainment"),
        DefaultCategory(GOVERNMENT, "Government & fees"),
        DefaultCategory(EDUCATION, "Education"),
        DefaultCategory(EMI_LOANS, "EMI & loans"),
        DefaultCategory(CASH, "Cash (ATM)"),
        DefaultCategory(INSURANCE, "Insurance"),
        DefaultCategory(OTHER, "Other"),
    )

    /** category id -> keywords (plain words or regex fragments), checked in this order. */
    val keywords: List<Pair<Long, List<String>>> = listOf(
        CASH to listOf("ATM CASH", "CASH WITHDRAWAL"),
        EMI_LOANS to listOf("\\bEMI\\b", "ACCOUNT DEBIT", "\\bLOAN\\b", "INSTALMENT", "INSTALLMENT", "TABBY", "TAMARA", "POSTPAY"),
        INSURANCE to listOf("INSURANCE", "LIVA INS", "\\bINS\\b", "TAKAFUL", "AXA", "SUKOON", "ORIENT INS"),
        UTILITIES to listOf("DEWA", "ADDC", "SEWA", "FEWA", "ETISALAT", "\\bE&\\b", "\\bDU\\b", "HOMEINTERNET", "UTLTY", "UTILITY", "EMPOWER", "NESTLE WATERS", "GAS CYLIND", "MANDOOS"),
        GOVERNMENT to listOf("SMART DUBAI", "SMARTDXBGOV", "DUBAI EGOVERNMENT", "GOVERNMENT", "POLICE", "TASHEEL", "\\bAMER\\b", "\\bICP\\b", "INTEGRATED TRANSPORT", "MOHRE", "TAMM", "EMIRATES AUCTION", "\\bRTA\\b", "LAND DEPARTMENT", "DUBAI LAND"),
        TRANSPORT to listOf("ADNOC", "ENOC", "EPPCO", "EMARAT", "PETROL", "SALIK", "DARB", "CAREEM", "UBER", "TAXI", "PARKING", "MAWAQIF", "HALA", "\\bNOL\\b"),
        DINING to listOf("TALABAT", "DELIVEROO", "NOON FOOD", "KEETA", "CAFE", "COFFEE", "RESTAURANT", "RESTURAUNT", "GRILL", "PIZZA", "BURGER", "KFC", "MCDONALD", "STARBUCKS", "TIM HORTONS", "SHAWARMA", "BAKERY", "WHIPPY", "SNACK", "ANGAARA", "KITCHEN"),
        GROCERIES to listOf("CARREFOUR", "LULU", "SUPERMARKE", "SUPERM", "HYPERMA", "HYPMKT", "MINIMART", "GROCERY", "UNION COOP", "SPINNEYS", "CHOITHRAMS", "WAITROSE", "GRANDIOSE", "WEST ZONE", "MORE VALUE", "AL MAYA", "NESTO", "VIVA", "KIBSONS", "AMAZON NOW", "INSTASHOP"),
        ENTERTAINMENT to listOf("BIG TICKET", "MAGIC PLANET", "CINEMA", "VOX", "REEL", "NOVO", "OSN", "NETFLIX", "SPOTIFY", "SHAHID", "ANGHAMI", "GAMES", "PLAYSTATION", "STEAM", "APPLE\\.COM", "GOOGLE"),
        TRAVEL to listOf("AGODA", "BOOKING\\.COM", "EXPEDIA", "FLYDUBAI", "EMIRATES AIRLINE", "ETIHAD", "AIR ARABIA", "AIRLINE", "HOTEL", "HOLIDAY IN", "AIRBNB", "TICKET", "RAHAT"),
        HEALTH to listOf("PHARMACY", "PHARMA", "HOSPITAL", "CLINIC", "MEDICAL", "SPECIALITY HO", "DENTAL", "ASTER", "LIFE PHARMACY", "BOOTS"),
        EDUCATION to listOf("UNIVERSITY", "SCHOOL", "BROOKES", "COURSE", "ACADEMY", "UDEMY", "COURSERA"),
        SHOPPING to listOf("AMAZON", "NOON", "TEMU", "SHEIN", "NAMSHI", "IKEA", "BRANDS VILLAGE", "OUTLET", "MALL", "CENTREPOINT", "MAX FASHION", "SHARAF DG", "SAMSUNG", "APPLE STORE", "JUMBO", "ACE ", "DATAFLOW", "FEDEX", "ARAMEX"),
    )

    private val compiled: List<Pair<Long, Regex>> by lazy {
        keywords.map { (id, words) -> id to Regex(words.joinToString("|") { "(?:$it)" }, RegexOption.IGNORE_CASE) }
    }

    /** Best guess for a merchant; null only for non-spending transactions. */
    fun guess(merchant: String, type: TxnType): Long? {
        if (type != TxnType.PURCHASE && type != TxnType.REFUND) return null
        if (merchant.equals("Cashback", true) || merchant.startsWith("FAB Rewards", true)) return OTHER
        return compiled.firstOrNull { it.second.containsMatchIn(merchant) }?.first ?: OTHER
    }

    /** Generic SMS texts that say nothing about who was paid ("Account debit (EMI / direct debit)", "Outward remittance"). */
    private val genericPrefixes = listOf("ACCOUNT DEBIT", "ACCOUNT CREDIT", "OUTWARD REMITTANCE", "INWARD REMITTANCE", "TRANSFER ADCB", "CASH DEPOSIT")

    /**
     * True when "apply to all" should only cover the same text AND the same amount: transfers, and generic
     * account debits (your car EMI and your rent are both "Account debit", but with different amounts).
     */
    fun isAmountSpecific(merchant: String, type: TxnType): Boolean =
        type == TxnType.TRANSFER_OUT || merchantKey(merchant).let { k -> genericPrefixes.any { k.startsWith(it) } }

    /** Types that can carry a category. Transfers only get one when you choose it (never guessed). */
    fun canHaveCategory(type: TxnType): Boolean = type == TxnType.PURCHASE || type == TxnType.REFUND || type == TxnType.TRANSFER_OUT

    /** The key a learned "merchant = category" rule is stored under. */
    fun learningKey(merchant: String, amountMinor: Long, type: TxnType): String =
        if (isAmountSpecific(merchant, type)) "=" + merchant.uppercase().trim() + "#" + amountMinor else merchantKey(merchant)

    /**
     * Key used to learn from your corrections: letters only, first 3 words.
     * "AGODA.COM AL HAMRA R" -> "AGODA COM AL", "ADNOC ROVE HOTEL 532" -> "ADNOC ROVE HOTEL".
     */
    fun merchantKey(merchant: String): String =
        merchant.uppercase().replace(Regex("[^A-Z]+"), " ").trim().split(" ").filter { it.isNotEmpty() }.take(3).joinToString(" ")
}
