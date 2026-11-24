package com.uaefinancial.tracker.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.8 spec 18.3: "Share feedback". Invented texts only. */
class FeedbackTest {
    private class Counter(var v: String = "") : Feedback.DayCounter {
        override fun get() = v
        override fun set(v: String) { this.v = v }
    }

    private val day = 20_416L
    private val app = Feedback.appLine("2.8", 8)
    private fun Map<String, String>.cat() = this[FeedbackConfig.F_CATEGORY]

    private fun sendAndCapture(input: Feedback.Input, counter: Counter = Counter(), ok: Boolean = true): Pair<Feedback.Result, Map<String, String>?> {
        var sent: Map<String, String>? = null
        val r = Feedback.send(input, app, counter, day) { sent = it.toMap(); ok }
        return r to sent
    }

    @Test fun theReleaseBuildHasRealFormConstants() {
        // Fails the build (and so the release) if anyone puts placeholders back: the row would be hidden.
        assertTrue(FeedbackConfig.enabled)
        assertFalse(FeedbackConfig.isReal("https://docs.google.com/forms/d/e/PLACEHOLDER/formResponse", "entry.1", "entry.2"))
        assertFalse(FeedbackConfig.isReal(FeedbackConfig.FEEDBACK_FORM_POST, "entry.XXXX", "entry.1245241074"))
        assertFalse(FeedbackConfig.isReal(FeedbackConfig.FEEDBACK_FORM_POST, "", "entry.1245241074"))
        assertFalse("the same id twice is a placeholder too", FeedbackConfig.isReal(FeedbackConfig.FEEDBACK_FORM_POST, "entry.1245241074", "entry.1245241074"))
        assertFalse("a view link is not the post address", FeedbackConfig.isReal(FeedbackConfig.FEEDBACK_FORM_POST.replace("formResponse", "viewform"), "entry.1245241074"))
    }

    @Test fun theCategoriesAreExactlyTheFormTexts() {
        assertEquals(listOf("Something is wrong with a message", "Idea or request", "Something else"), Feedback.CATEGORIES)
    }

    @Test fun aMessageOnlySendWorks() {
        val (r, f) = sendAndCapture(Feedback.Input(null, "The charts are great, please add a yearly view."))
        assertEquals(Feedback.Result.Sent, r)
        assertNull("an unselected category sends nothing for that field", f!!.cat())
        assertEquals("The charts are great, please add a yearly view.", f[FeedbackConfig.F_MESSAGE])
        assertEquals("anonymous", f[FeedbackConfig.F_IDENTITY])
        assertEquals("2.8 engine 8", f[FeedbackConfig.F_APP])
    }

    @Test fun aCategoryOnlySendWorks() {
        val (r, f) = sendAndCapture(Feedback.Input("Idea or request", "   "))
        assertEquals(Feedback.Result.Sent, r)
        assertEquals("Idea or request", f!!.cat())
    }

    @Test fun anEmptyFormCannotBeSent() {
        assertFalse(Feedback.canSend(null, "   \n "))
        val (r, f) = sendAndCapture(Feedback.Input(null, " "))
        assertEquals(Feedback.Result.Empty, r); assertNull(f)
        assertTrue(Feedback.canSend(null, "x"))
        assertTrue(Feedback.canSend("Something else", ""))
    }

    @Test fun emailModeWithAnEmptyBoxSendsAnonymously() {
        val (_, f) = sendAndCapture(Feedback.Input(null, "Hello", Feedback.Identity.EMAIL, "  "))
        assertEquals("anonymous", f!![FeedbackConfig.F_IDENTITY]); assertNull(f[FeedbackConfig.F_EMAIL])
    }

    @Test fun bothIdentityModesPostTheRightFields() {
        val (_, withEmail) = sendAndCapture(Feedback.Input("Something else", "Hello", Feedback.Identity.EMAIL, "someone@example.com"))
        assertEquals("email", withEmail!![FeedbackConfig.F_IDENTITY]); assertEquals("someone@example.com", withEmail[FeedbackConfig.F_EMAIL])
        assertEquals(setOf(FeedbackConfig.F_CATEGORY, FeedbackConfig.F_MESSAGE, FeedbackConfig.F_IDENTITY, FeedbackConfig.F_EMAIL, FeedbackConfig.F_APP), withEmail.keys)
        val (_, anon) = sendAndCapture(Feedback.Input("Something else", "Hello"))
        assertEquals(setOf(FeedbackConfig.F_CATEGORY, FeedbackConfig.F_MESSAGE, FeedbackConfig.F_IDENTITY, FeedbackConfig.F_APP), anon!!.keys)
    }

    @Test fun anonymousModeNeverIncludesAnEmailTypedEarlier() {
        val (_, f) = sendAndCapture(Feedback.Input(null, "Hello", Feedback.Identity.ANONYMOUS, "someone@example.com"))
        assertEquals("anonymous", f!![FeedbackConfig.F_IDENTITY])
        assertFalse(f.values.any { "@" in it })
    }

    @Test fun aBadEmailIsNotSent() {
        val (r, f) = sendAndCapture(Feedback.Input(null, "Hello", Feedback.Identity.EMAIL, "someone@"))
        assertEquals(Feedback.Result.BadEmail, r); assertNull(f)
    }

    @Test fun riskyDigitsBlockSendingAndTheTextIsNotAltered() {
        for (t in listOf(
            "My card 4111 1111 1111 1111 was read twice",
            "Account 12345678 shows wrong",
            "Call me on 050-123-4567",
            "The OTP 482913 was saved as a purchase",
            "code 4829 is the OTP",
        )) {
            val input = Feedback.Input(null, t)
            val (r, f) = sendAndCapture(input)
            assertEquals(t, Feedback.Result.RiskyDigits, r); assertNull(f)
            assertEquals(t, input.message)
        }
        for (t in listOf("Version 2.8 is great", "I spent AED 45.50 at a cafe and it showed twice", "Since 2026 I use it", "Top 3 banks")) {
            assertFalse(t, Feedback.hasRiskyDigits(t))
        }
    }

    @Test fun theFourthSendInADayIsRefused() {
        val c = Counter()
        repeat(3) { assertEquals(Feedback.Result.Sent, sendAndCapture(Feedback.Input(null, "Note $it"), c).first) }
        val (r, f) = sendAndCapture(Feedback.Input(null, "Note 4"), c)
        assertEquals(Feedback.Result.LimitReached, r); assertNull(f)
        // a new day starts again
        var posted = false
        assertEquals(Feedback.Result.Sent, Feedback.send(Feedback.Input(null, "Next day"), app, c, day + 1) { posted = true; true })
        assertTrue(posted)
    }

    @Test fun aFailureKeepsTheTextAndDoesNotCount() {
        val c = Counter()
        val input = Feedback.Input("Something else", "Please add a dark theme")
        val (r, _) = sendAndCapture(input, c, ok = false)
        assertEquals(Feedback.Result.Failed, r)
        assertEquals("Please add a dark theme", input.message)
        assertEquals(0, Feedback.sentToday(c, day))
    }

    @Test fun onlyTheVersionNameAndEngineAreSentAboutTheApp() {
        assertEquals("2.8 engine 8", Feedback.appLine("2.8-beta", 8))
        val (_, f) = sendAndCapture(Feedback.Input(null, "Hello"))
        assertEquals("message, identity and app only", setOf(FeedbackConfig.F_MESSAGE, FeedbackConfig.F_IDENTITY, FeedbackConfig.F_APP), f!!.keys)
    }
}
