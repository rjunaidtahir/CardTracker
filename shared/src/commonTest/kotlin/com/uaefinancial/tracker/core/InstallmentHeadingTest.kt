package com.uaefinancial.tracker.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** "Installment Plan" is a table heading only on a short line; a long description line that starts with it is not. (The short-heading case is covered by the b4 rewards/instalment fixture in StatementReaderPdfTest.) */
class InstallmentHeadingTest {
    private fun read(t: String) = StatementReader.analyze(StatementReader.linesFromText(t.trimIndent()), 2026)

    private val header = """
        Transaction  Posting
           Date       Date      Transaction Details                 Amount           Debit         Credit
    """

    @Test fun a_long_description_line_starting_with_installment_plan_does_not_hide_the_rows_after_it() {
        val a = read(header + """
        22-08-2026 23-08-2026 LULU HYPERMARKET LLC  DUBAI  UAE AED    120.00         120.00
        Installment Plan payment to ABC TRADING COMPANY LLC DUBAI
        24-08-2026 25-08-2026 ABC TRADING COMPANY LLC  DUBAI  UAE AED    80.00         80.00
        """)
        assertEquals(2, a.lines.size)
    }
}
