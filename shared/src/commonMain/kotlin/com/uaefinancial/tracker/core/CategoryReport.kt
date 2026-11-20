package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.CategoryRules
import com.uaefinancial.tracker.parser.TxnType

/**
 * What may be said about a category change when the person has allowed Fils to learn (sharing ON). Pure logic, so it is tested
 * without a phone. No merchant name, amount, date, card digits or message ever leaves: only the old and new category (a category
 * you typed yourself is "custom" and is not reported at all), the message type, and whether "apply to all" was chosen. The masked
 * wording (with the merchant's place marked as {MERCHANT}) is added by the sender.
 */
object CategoryReport {
    /** "Groceries" for a built-in category, "custom" for one you made, "none" for no category. */
    fun categoryLabel(id: Long?): String {
        if (id == null) return "none"
        return CategoryRules.defaults.firstOrNull { it.id == id }?.name ?: "custom"
    }

    /** The key=value text for the report, or null when there is nothing to learn (no change, or a category you made yourself). */
    fun fields(type: TxnType, fromId: Long?, toId: Long, applyToMerchant: Boolean): String? {
        if (fromId == toId) return null
        val to = categoryLabel(toId)
        if (to == "custom") return null
        return listOf("from=" + categoryLabel(fromId), "to=$to", "type=${type.name}", "all=" + (if (applyToMerchant) "yes" else "no")).joinToString(" | ")
    }
}
