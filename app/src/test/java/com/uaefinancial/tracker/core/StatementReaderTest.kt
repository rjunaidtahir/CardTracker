package com.uaefinancial.tracker.core

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Layouts modelled on real UAE statements (names and numbers changed). */
class StatementReaderTest {
    private fun read(t: String) = StatementReader.analyze(StatementReader.linesFromText(t.trimIndent()), 2026)

    @Test fun labels_above_values_and_debit_credit_columns() {
        val a = read(
            """
                                          Main Card Product          Statement Date        Payment Due Date
            MR TEST USER                  Rewards Card               01-09-2026            26-09-2026
                                          Main Card Number           Current Balance       Minimum Payment Due
                                          5425 36** **** 0831        2,647.82              132.39
              Previous Balance     + Purchases/Debits     - Payments/Credits     Total Payment Due
                  3,364.94              2,652.99               3,370.11               2,647.82
              Total Credit Limit      Available Credit Limit
                  5,000.00                2,238.76
                                                                                UAE Dirham Amount
            Transaction  Posting                                   Original Currency
               Date       Date      Transaction Details                 Amount           Debit         Credit
            22-08-2026 23-08-2026 LULU HYPERMARKET LLC  DUBAI  UAE AED    2,652.99         2,652.99
            23-08-2026 23-08-2026 PAYMENT RECEIVED - THANK YOU     AED    3,364.94                       3,364.94
                                  Payment of AED 3364.94 received on
                                  23-08-2026 towards Principle AED 3364.94
            31-08-2026 01-09-2026 TABBY                 DUBAI  ARE AED        5.17                           5.17
            """,
        )
        val s = a.summary
        assertEquals(LocalDate.of(2026, 9, 1), s.statementDate)
        assertEquals(LocalDate.of(2026, 9, 26), s.dueDate)
        assertEquals(264_782L, s.totalDueMinor)
        assertEquals(13_239L, s.minimumDueMinor)
        assertEquals(500_000L, s.creditLimitMinor)
        assertEquals(223_876L, s.availableLimitMinor)
        assertEquals("0831", s.cardLast4)
        assertEquals(3, a.lines.size)
        assertFalse(a.lines[0].isCredit)
        assertTrue(a.lines[1].isCredit)
        assertTrue(a.lines[2].isCredit) // in the Credit column, no "CR"
        assertEquals(true, a.totalsAgree)
    }

    @Test fun same_line_labels_trailing_minus_and_two_digit_dates() {
        val a = read(
            """
            Card No                    4600 XXXX XXXX 4680
            Total outstanding balance  43,844.00
            Minimum payment due        99.64
            Total payment due*         1,992.76
            Statement date             12/09/2026
            Payment due date           09/10/2026
            Previous balance           45,837.00
            Combined credit limit      100,000.00
            Available credit limit     56,156.00
            Available cash limit       20,000.00
            transaction date  posting date   description                     AED amount
            05/09             05/09          payments received thru uaefts   1,993.00 -
            sub-total                                                        1,993.00 -
            """,
        )
        assertEquals(10_000_000L, a.summary.creditLimitMinor)
        assertEquals(5_615_600L, a.summary.availableLimitMinor)
        assertEquals(199_276L, a.summary.totalDueMinor)
        assertEquals(1, a.lines.size)
        assertTrue(a.lines[0].isCredit)
        assertEquals(LocalDate.of(2026, 9, 5), a.lines[0].date)
        assertEquals(true, a.totalsAgree) // 45,837 − 1,993 = 43,844 outstanding
    }

    @Test fun values_without_labels_are_inferred() {
        // Some banks print the labels as pictures: only the values are text.
        val a = read(
            """
                                          XXXXXXXXXXXX3538
                                          19/09/26
                                          14/10/26
            13b street                    100.00
                                          1,263.92
                                          6,000.00
                                          4,736.08
            PREVIOUS BALANCE OUTSTANDING              101.75
            24/08/2026  PAYMENT RECEIVED, THANK YOU   102.00 CR
            28/08/2026  AMAZON GROCERY DUBAI ARE     1,264.17
            19/09/2026  NEW BALANCE OUTSTANDING      1263.92
            """,
        )
        val s = a.summary
        assertEquals(LocalDate.of(2026, 9, 19), s.statementDate)
        assertEquals(LocalDate.of(2026, 10, 14), s.dueDate)
        assertEquals(126_392L, s.totalDueMinor)
        assertEquals(600_000L, s.creditLimitMinor) // = available + balance
        assertEquals(473_608L, s.availableLimitMinor)
        assertEquals(10_000L, s.minimumDueMinor)
        assertEquals(2, a.lines.size)
        assertEquals(true, a.totalsAgree)
    }

    @Test fun multi_line_foreign_spend_and_supplementary_card() {
        val a = read(
            """
            Statement Period: From 12 August 26 to 11 September 26
            Transaction  Posting                                   Original Amount     Total Amount
               Date       Date     Transaction Details                                    (AED)
            Opening Balance                                                                79.20
            11-Aug-26  12-Aug-26 CASHBACK                              45.50 CR          45.50CR
            -
            4183 4899 1557 5258
                                 INSTITUTE OF CHARTERED LONDON GB GBP 541.50           2,694.92
            13-Aug-26  14-Aug-26
                                 FOREIGN CURRENCY PROCESSING FEE  53.90                   2.70
                                 STND PROC. FEE (AS PER SCHEMES)  30.99                   1.55    2,784.06
            -
            Supplementary Card Number
            OTHER USER: 5521 XXXX XXXX 3944
            08-Sept-26 09-Sept-26 Amazon.ae        Dubai    AE    79.24 CR               79.24CR
            """,
        )
        assertEquals(3, a.lines.size)
        assertEquals(LocalDate.of(2026, 9, 11), a.summary.statementDate)
        val fx = a.lines[1]
        assertEquals(278_406L, fx.amountMinor) // includes the fees
        assertTrue(fx.description.startsWith("INSTITUTE OF CHARTERED"))
        assertEquals("5258", fx.cardLast4)
        assertEquals("3944", a.lines[2].cardLast4)
        assertTrue(a.lines[2].isCredit)
        assertEquals(LocalDate.of(2026, 9, 8), a.lines[2].date)
    }

    @Test fun account_statement_with_balance_column() {
        val a = read(
            """
                                                              ACCOUNT STATEMENT
            TEST USER                                         AC-NUM        116-100-1234567-00-1
                                                              Account Statement FROM 01 AUG 2026 TO 31 AUG 2026
                                                              Closing Book Balance            7,238.41
            DATE          VALUE DATE     DESCRIPTION                   DEBIT          CREDIT          BALANCE
                                         Opening balance                                              21,160.35
            01 AUG 2026   01 AUG 2026    Telex Transfer Remittance     1,805.00                      19,355.35
                                         /REF/car installment Beneficiary Details
            03 AUG 2026   03 AUG 2026    Inward IPP Payment                           2,000.00        21,355.35
                                         t,IPP Ref: INSTQ1msck,Value Date: 0
                                         3-08-2026,Trf Ccy: AED,Trf Amt: 2000.00,P
            05 AUG 2026   05 AUG 2026    Transfer                      14,116.94                         7,238.41
            """,
        )
        val s = a.summary
        assertTrue(s.isAccount)
        assertEquals("7001", s.cardLast4)
        assertNull(s.totalDueMinor)
        assertEquals(723_841L, s.closingBalanceMinor)
        assertEquals(LocalDate.of(2026, 8, 1), s.periodFrom)
        assertEquals(3, a.lines.size) // the wrapped "3-08-2026,Trf..." line is not a transaction
        assertFalse(a.lines[0].isCredit)
        assertTrue(a.lines[1].isCredit)
        assertTrue(a.lines[0].description.contains("car installment"))
        assertEquals(true, a.totalsAgree)
    }
}
