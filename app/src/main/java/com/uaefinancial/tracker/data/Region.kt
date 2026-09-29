package com.uaefinancial.tracker.data

import android.content.Context
import android.telephony.TelephonyManager
import com.uaefinancial.tracker.parser.Currencies
import com.uaefinancial.tracker.parser.SmsParser
import java.util.Locale
import java.util.TimeZone

/**
 * Where you are, for reading messages: your home currency, whether dates are written month first, and the phone's
 * time zone. Set before any message is read (the SMS receiver can start the app cold).
 */
object Region {

    /**
     * The phone's country: the SIM's country first (a UAE SIM in a phone set to English (US) is still the UAE), then
     * the mobile network's, then the language setting's. Null if none is known.
     */
    fun detect(context: Context): String? {
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        return listOf(
            runCatching { tm?.simCountryIso }.getOrNull(),
            runCatching { tm?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstNotNullOfOrNull { c -> c?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all(Char::isLetter) } }
    }

    /** The currency used in [country], if the app has a rate for it; otherwise AED. */
    fun currencyFor(country: String?): String {
        if (country == null) return Currencies.PIVOT
        Currencies.forRegion(country)?.let { return it }
        val code = runCatching { java.util.Currency.getInstance(Locale.Builder().setRegion(country).build()).currencyCode }.getOrNull()
        return code?.takeIf { Currencies.isCode(it) } ?: Currencies.PIVOT
    }

    /**
     * Applies your home currency, date style and time zone to the reader. The first time, they come from the phone's
     * region and are kept, so they don't change when you travel; an install from before the app went global keeps AED
     * and day-first dates, as it always read them.
     */
    fun apply(context: Context, prefs: Prefs) {
        if (prefs.homeCurrency == null || prefs.dateRegion == null) {
            val country = if (prefs.onboarded) "AE" else detect(context)
            if (prefs.homeCurrency == null) prefs.homeCurrency = currencyFor(country)
            if (prefs.dateRegion == null) prefs.dateRegion = country ?: ""
        }
        SmsParser.setHomeCurrency(prefs.homeCurrency ?: Currencies.PIVOT)
        SmsParser.setMonthFirstDates(prefs.dateRegion?.let { SmsParser.isMonthFirstRegion(it) } ?: false)
        // The offset in force when each message arrived (summer time included).
        SmsParser.setZoneProvider { at -> TimeZone.getDefault().getOffset(at) / 60_000 }
    }
}
