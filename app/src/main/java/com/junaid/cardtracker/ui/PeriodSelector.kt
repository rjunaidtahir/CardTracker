package com.junaid.cardtracker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.junaid.cardtracker.core.Period
import com.junaid.cardtracker.core.PeriodKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Period chips (Month · 1W · 1M · 3M · 6M · 12M · All · Custom) plus a line with arrows and the date range.
 * Custom opens a date-range calendar.
 */
@Composable
fun PeriodSelector(
    period: Period,
    onKind: (PeriodKind) -> Unit,
    onShift: (Int) -> Unit,
    onCustom: (LocalDate, LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        RangePickerDialog(
            initial = period,
            onDone = { a, b -> picking = false; onCustom(a, b) },
            onDismiss = { picking = false },
        )
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PeriodKind.entries.forEach { k ->
                Pill(
                    text = k.chip,
                    selected = period.kind == k,
                    onClick = { if (k == PeriodKind.CUSTOM) picking = true else onKind(k) },
                    icon = if (k == PeriodKind.CUSTOM) Icons.Filled.CalendarMonth else null,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            val canMove = period.isBounded
            IconButton(onClick = { onShift(-1) }, enabled = canMove) {
                Icon(Icons.Filled.KeyboardArrowLeft, "Earlier", tint = if (canMove) Ink.text else Ink.faint)
            }
            TextButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
                Text(period.label(), style = MaterialTheme.typography.titleSmall, color = Ink.text, textAlign = TextAlign.Center)
            }
            val canForward = canMove && period.end?.isBefore(LocalDate.now()) == true
            IconButton(onClick = { onShift(1) }, enabled = canForward) {
                Icon(Icons.Filled.KeyboardArrowRight, "Later", tint = if (canForward) Ink.text else Ink.faint)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(initial: Period, onDone: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    // The picker works in UTC millis at midnight.
    fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.start?.utcMillis(),
        initialSelectedEndDateMillis = initial.end?.utcMillis(),
    )
    val colors = DatePickerDefaults.colors(
        containerColor = Ink.surface,
        dayInSelectionRangeContainerColor = Ink.green.copy(alpha = 0.22f),
        selectedDayContainerColor = Ink.green,
        selectedDayContentColor = Ink.bg,
        todayDateBorderColor = Ink.green,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        colors = colors,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val a = state.selectedStartDateMillis?.utcDate() ?: return@TextButton
                    val b = state.selectedEndDateMillis?.utcDate() ?: a
                    onDone(a, b)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            colors = colors,
            modifier = Modifier.weight(1f),
            title = { Text("Choose a period", Modifier.padding(start = 24.dp, top = 16.dp), style = MaterialTheme.typography.titleMedium) },
        )
    }
}
