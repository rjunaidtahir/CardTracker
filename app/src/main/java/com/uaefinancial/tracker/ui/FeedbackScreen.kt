package com.uaefinancial.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.uaefinancial.tracker.R
import com.uaefinancial.tracker.TrackerApp
import com.uaefinancial.tracker.feedback.Feedback
import com.uaefinancial.tracker.parser.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * More -> Help -> "Share feedback" (2.8 spec 18.3). Every field is optional; Send needs some text or a category. The warning
 * stays at the top. Nothing is sent until the user presses Send; on failure the text is kept.
 */
@Composable
fun FeedbackScreen() {
    val context = LocalContext.current
    val prefs = remember { (context.applicationContext as TrackerApp).prefs }
    val scope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var withEmail by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Feedback.Result?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val counter = remember {
        object : Feedback.DayCounter {
            override fun get() = prefs.feedbackSent
            override fun set(v: String) { prefs.feedbackSent = v }
        }
    }
    val app = Feedback.appLine(com.uaefinancial.tracker.BuildConfig.VERSION_NAME, SmsParser.ENGINE_VERSION)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.feedback_warning), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink.amber)
        }
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.feedback_category), style = MaterialTheme.typography.titleSmall)
            Box(Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { menuOpen = true }) { Text(category ?: stringResource(R.string.feedback_category_none)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.feedback_category_none)) }, onClick = { category = null; menuOpen = false })
                    Feedback.CATEGORIES.forEach { c ->
                        DropdownMenuItem(text = { Text(c) }, onClick = { category = c; menuOpen = false; status = null })
                    }
                }
            }
            OutlinedTextField(
                value = message,
                onValueChange = { message = it.take(Feedback.MAX_MESSAGE); status = null },
                label = { Text(stringResource(R.string.feedback_message)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(top = 10.dp),
                supportingText = { Text("${message.length} / ${Feedback.MAX_MESSAGE}") },
            )
            Row(Modifier.fillMaxWidth().clickable { withEmail = false }.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !withEmail, onClick = { withEmail = false })
                Text(stringResource(R.string.feedback_anonymous))
            }
            Row(Modifier.fillMaxWidth().clickable { withEmail = true }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = withEmail, onClick = { withEmail = true })
                Text(stringResource(R.string.feedback_with_email))
            }
            if (withEmail) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it.trim().take(200); status = null },
                    label = { Text(stringResource(R.string.feedback_email)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.feedback_email_use), style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 4.dp))
            }
            Button(
                onClick = {
                    sending = true
                    val input = Feedback.Input(category, message, if (withEmail) Feedback.Identity.EMAIL else Feedback.Identity.ANONYMOUS, email)
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            Feedback.send(input, app, counter, System.currentTimeMillis() / 86_400_000L) { Feedback.postToForm(it) }
                        }
                        status = r
                        if (r == Feedback.Result.Sent) { category = null; message = ""; email = ""; withEmail = false }
                        sending = false
                    }
                },
                enabled = !sending && Feedback.canSend(category, message),
                modifier = Modifier.padding(top = 10.dp),
            ) { Text(stringResource(R.string.feedback_send)) }
            Text(stringResource(R.string.feedback_sent_with), style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 6.dp))
            val note = when (status) {
                Feedback.Result.Sent -> stringResource(R.string.feedback_thanks)
                Feedback.Result.RiskyDigits -> stringResource(R.string.feedback_risky)
                Feedback.Result.Failed -> stringResource(R.string.feedback_failed)
                Feedback.Result.LimitReached -> stringResource(R.string.feedback_limit)
                Feedback.Result.BadEmail -> stringResource(R.string.feedback_email_bad)
                else -> null
            }
            if (note != null) {
                Text(
                    note, style = MaterialTheme.typography.bodyMedium,
                    color = if (status == Feedback.Result.Sent) Ink.green else Ink.red,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
