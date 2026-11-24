package com.uaefinancial.tracker.feedback

/**
 * Where "Share feedback" posts (2.8 spec 18.3): a SEPARATE Google Form with its own responses sheet ("Fils user feedback
 * (Responses)"), never the message-shape form. Every question on it is optional. The entry ids were read in form order from a
 * pre-filled link (Category, Message, Identity, Email, App); the owner confirms the columns with one real test send from a
 * debug build.
 */
object FeedbackConfig {
    const val FEEDBACK_FORM_POST = "https://docs.google.com/forms/d/e/1FAIpQLSf2W4GK-8PqNnK74VY5VQV9B0-UjLE72eeXpmiL4Jdv8oVzrA/formResponse"
    const val F_CATEGORY = "entry.588787966"
    const val F_MESSAGE = "entry.1245241074"
    const val F_IDENTITY = "entry.1633497436"
    const val F_EMAIL = "entry.1538287310"
    const val F_APP = "entry.847783671"

    private val entryId = Regex("""entry\.\d{3,}""")

    /** True only when every constant is real: a build can never show a "Share feedback" row that posts nowhere. */
    fun isReal(post: String, vararg ids: String): Boolean =
        post.startsWith("https://docs.google.com/forms/d/e/") && post.endsWith("/formResponse") &&
            !post.contains("PLACEHOLDER", ignoreCase = true) && !post.contains("XXXX") &&
            ids.all { entryId.matches(it) } && ids.toSet().size == ids.size

    val enabled: Boolean get() = isReal(FEEDBACK_FORM_POST, F_CATEGORY, F_MESSAGE, F_IDENTITY, F_EMAIL, F_APP)
}
