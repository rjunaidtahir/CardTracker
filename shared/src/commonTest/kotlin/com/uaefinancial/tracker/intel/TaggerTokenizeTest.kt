package com.uaefinancial.tracker.intel

import kotlin.test.Test
import kotlin.test.assertEquals

class TaggerTokenizeTest {
    @Test fun wordsKeepJoinersInsideAndDropThemAtTheEnd() {
        val toks = MerchantTagger.tokenize("Paid at M&S/DXB, ALI'S CAFE... ref-12.")
        assertEquals(listOf("Paid", "at", "M&S/DXB", "ALI'S", "CAFE", "ref-12"), toks.map { it.text })
        assertEquals(8, toks[2].start)
        assertEquals(15, toks[2].end)
    }

    @Test fun punctuationAloneMakesNoWord() {
        assertEquals(emptyList(), MerchantTagger.tokenize(" -- ** .. ").map { it.text })
    }
}
