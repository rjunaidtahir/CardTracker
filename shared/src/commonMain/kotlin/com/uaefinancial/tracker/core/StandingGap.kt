package com.uaefinancial.tracker.core

import kotlin.math.abs

/**
 * Places a "standing instruction has been processed" SMS that has no matching debit SMS.
 *
 * Some banks (FAB, for one) send two SMS per transfer: a debit alert that names the account and shows the balance, and a
 * "standing instruction ... processed" message that names the payee but NOT the account. When only the second one arrives,
 * the money is real but the account is unknown. The balances in the other messages tell us where it went: on the account
 * whose balance dropped by more than its recorded transactions explain.
 *
 * Pure logic, no storage: the apps pass in each account's saved rows and the waiting standing-instruction messages, and
 * get back one decision per message. Same input, same output (a re-read of stored messages gives the same result).
 *
 * Rules (never guess):
 *  - A standing instruction that already has a matching debit (same amount, within a day) adds nothing: [Outcome.AlreadyCounted].
 *  - It is placed only when exactly one balance gap (one account, one stretch of time) can hold it, together with any other
 *    waiting messages that fit the same gap: [Outcome.Place]. Whatever is left over in the gap (a fee, say) is reported as
 *    [Outcome.Place.residualMinor]; it never blocks the placement and is shown as its own flag by [Checks].
 *  - No gap fits, or more than one could hold it: [Outcome.Unplaced], so the app leaves it for the person to look at.
 */
object StandingGap {
    /** What may be left over in a gap after the standing instructions are placed (a fee, rounding): AED 15.00. */
    const val MAX_RESIDUAL = 1_500L
    private const val DAY = 24L * 3600 * 1000
    private const val MAX_SPAN_MS = 7L * DAY
    private const val MAX_CANDIDATES = 8
    private const val MAX_SUBSET = 3

    private val moneyIn = setOf("REFUND", "PAYMENT", "TRANSFER_IN")
    private val moneyOut = setOf("PURCHASE", "TRANSFER_OUT")

    /** One saved row on an account. [availMinor] is the balance printed in its message, if any. Amounts are minor units. */
    data class Entry(val id: Long, val timestamp: Long, val type: String, val amountMinor: Long, val availMinor: Long?)

    data class Account(val key: String, val rows: List<Entry>)

    /** A waiting standing-instruction message: [timestamp] is the date/time in the message. */
    data class Standing(val id: Long, val timestamp: Long, val amountMinor: Long)

    enum class Why { NO_GAP, AMBIGUOUS }

    sealed interface Outcome {
        val standingId: Long

        /** A debit with the same amount and day is already saved: do not add another transaction. */
        data class AlreadyCounted(override val standingId: Long) : Outcome

        /** Create the transaction on [accountKey] at [timestamp]; [residualMinor] is what the gap still leaves unexplained. */
        data class Place(override val standingId: Long, val accountKey: String, val timestamp: Long, val residualMinor: Long) : Outcome

        /** Money left one of your accounts, but it can't be said which (or when): leave it for review. */
        data class Unplaced(override val standingId: Long, val why: Why) : Outcome
    }

    private class Gap(val account: String, val from: Entry, val to: Entry, val unexplainedOut: Long)

    fun resolve(accounts: List<Account>, standing: List<Standing>): List<Outcome> {
        val order = standing.sortedWith(compareBy({ it.timestamp }, { it.id }))
        val outcomes = LinkedHashMap<Long, Outcome>()

        // 1. Already counted: a saved debit with the same amount on the same day (one debit covers one message).
        val used = HashSet<Pair<String, Long>>()
        val open = ArrayList<Standing>()
        for (s in order) {
            val hit = accounts.firstNotNullOfOrNull { a ->
                a.rows.firstOrNull { r ->
                    r.type in moneyOut && r.amountMinor == s.amountMinor && abs(r.timestamp - s.timestamp) <= DAY && (a.key to r.id) !in used
                }?.let { a.key to it.id }
            }
            if (hit != null) { used += hit; outcomes[s.id] = Outcome.AlreadyCounted(s.id) } else open += s
        }
        if (open.isEmpty()) return order.map { outcomes.getValue(it.id) }

        // 2. Every stretch between two balances where more money left than the saved rows explain.
        val gaps = accounts.flatMap { gapsOf(it) }

        // 3. For each gap, which waiting messages (alone or together) account for it.
        class Proposal(val gap: Gap, val ids: List<Long>)
        val proposals = ArrayList<Proposal>()
        val ambiguous = HashSet<Long>()
        for (g in gaps) {
            val near = open.filter { it.timestamp >= g.from.timestamp - DAY && it.timestamp <= g.to.timestamp + DAY }.take(MAX_CANDIDATES)
            val fits = subsets(near, g.unexplainedOut)
            when (fits.size) {
                0 -> {}
                1 -> proposals += Proposal(g, fits.single().map { it.id })
                else -> fits.forEach { sub -> sub.forEach { ambiguous += it.id } }
            }
        }

        // 4. A message wanted by two gaps (two accounts, or two stretches of one) is ambiguous, and so is
        //    everything that would be placed beside it. Repeat until nothing changes.
        var live = proposals.toList()
        while (true) {
            val wanted = live.flatMap { it.ids }.groupingBy { it }.eachCount()
            val newlyAmbiguous = wanted.filter { it.value > 1 }.keys - ambiguous
            val tainted = live.filter { p -> p.ids.any { it in ambiguous || it in newlyAmbiguous } }
            if (newlyAmbiguous.isEmpty() && tainted.isEmpty()) break
            ambiguous += newlyAmbiguous
            tainted.forEach { p -> ambiguous += p.ids }
            live = live - tainted.toSet()
        }

        // 5. Decisions.
        val byId = open.associateBy { it.id }
        for (p in live) {
            val members = p.ids.map { byId.getValue(it) }.sortedWith(compareBy({ it.timestamp }, { it.id }))
            val residual = p.gap.unexplainedOut - members.sumOf { it.amountMinor }
            members.forEachIndexed { i, s ->
                outcomes[s.id] = Outcome.Place(s.id, p.gap.account, slot(p.gap, s.timestamp, i), residual)
            }
        }
        for (s in open) if (s.id !in outcomes) outcomes[s.id] = Outcome.Unplaced(s.id, if (s.id in ambiguous) Why.AMBIGUOUS else Why.NO_GAP)
        return order.map { outcomes.getValue(it.id) }
    }

    /** The time a placed message gets: its own date, kept inside the stretch between the two balances (ms apart in message order). */
    private fun slot(g: Gap, t: Long, index: Int): Long {
        val lo = g.from.timestamp + 1
        val hi = g.to.timestamp - 1
        val base = if (lo > hi) g.from.timestamp else t.coerceIn(lo, hi)
        return if (lo > hi) base else minOf(base + index, hi)
    }

    private fun delta(e: Entry): Long = when (e.type) {
        in moneyOut -> -e.amountMinor
        in moneyIn -> e.amountMinor
        else -> 0L
    }

    private fun gapsOf(a: Account): List<Gap> {
        val rows = a.rows.sortedWith(compareBy({ it.timestamp }, { it.id }))
        val out = ArrayList<Gap>()
        var anchor: Entry? = null
        var since = 0L
        for (r in rows) {
            since += delta(r)
            val bal = r.availMinor ?: continue
            val p = anchor
            if (p != null && r.timestamp - p.timestamp <= MAX_SPAN_MS) {
                val diff = bal - (p.availMinor!! + since)
                if (diff < 0) out += Gap(a.key, p, r, -diff)
            }
            anchor = r
            since = 0L
        }
        return out
    }

    /** Every combination of up to [MAX_SUBSET] messages whose total fits [unexplained] with at most [MAX_RESIDUAL] left over. */
    private fun subsets(near: List<Standing>, unexplained: Long): List<List<Standing>> {
        val found = ArrayList<List<Standing>>()
        fun walk(start: Int, picked: List<Standing>, sum: Long) {
            if (picked.isNotEmpty() && sum <= unexplained && unexplained - sum <= MAX_RESIDUAL) found += picked
            if (picked.size == MAX_SUBSET) return
            for (i in start until near.size) {
                val s = near[i]
                if (sum + s.amountMinor <= unexplained) walk(i + 1, picked + s, sum + s.amountMinor)
            }
        }
        walk(0, emptyList(), 0L)
        // Messages with the same amounts are interchangeable here: keep one realisation per set of amounts, the earliest.
        val seen = HashSet<List<Long>>()
        return found.filter { seen.add(it.map { s -> s.amountMinor }.sorted()) }
    }
}
