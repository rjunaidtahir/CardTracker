package com.junaid.cardtracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junaid.cardtracker.data.TransactionEntity
import com.junaid.cardtracker.parser.CategoryRules
import com.junaid.cardtracker.parser.TxnType

/** Design tokens: near-black canvas, neon green for money figures, violet as the second accent. */
object Ink {
    val bg = Color(0xFF07090C)
    val bgTop = Color(0xFF0E1A14)      // faint green glow at the top of each screen
    val bgBottom = Color(0xFF120D1F)   // faint violet at the bottom
    val surface = Color(0xFF12151A)
    val surfaceHigh = Color(0xFF1A1E25)
    val surfaceHighest = Color(0xFF242A33)
    val border = Color(0xFF262B34)
    val text = Color(0xFFF2F4F7)
    val muted = Color(0xFF9AA3AF)
    val faint = Color(0xFF5E6672)
    val green = Color(0xFF3BE37F)
    val greenDim = Color(0xFF1C6B40)
    val violet = Color(0xFFA78BFA)
    val red = Color(0xFFFF6B6B)
    val amber = Color(0xFFF5B942)
}

private val scheme = darkColorScheme(
    primary = Ink.green,
    onPrimary = Color(0xFF04210F),
    primaryContainer = Ink.greenDim,
    onPrimaryContainer = Color(0xFFD7FFE6),
    secondary = Ink.violet,
    onSecondary = Color(0xFF1B0F3D),
    secondaryContainer = Color(0xFF2E2458),
    onSecondaryContainer = Color(0xFFE9E1FF),
    tertiary = Ink.amber,
    background = Ink.bg,
    onBackground = Ink.text,
    surface = Ink.surface,
    onSurface = Ink.text,
    surfaceVariant = Ink.surfaceHigh,
    onSurfaceVariant = Ink.muted,
    surfaceContainerLowest = Ink.bg,
    surfaceContainerLow = Ink.surface,
    surfaceContainer = Ink.surface,
    surfaceContainerHigh = Ink.surfaceHigh,
    surfaceContainerHighest = Ink.surfaceHighest,
    outline = Ink.border,
    outlineVariant = Ink.border,
    error = Ink.red,
    onError = Color(0xFF3A0707),
)

private val typography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 1.2.sp),
    )
}

/** Always the dark finance look (the references are dark). */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/** Screen background: black with a soft green glow at the top and violet at the bottom. */
val screenBrush: Brush = Brush.verticalGradient(0f to Ink.bgTop, 0.35f to Ink.bg, 0.8f to Ink.bg, 1f to Ink.bgBottom)

/** Small caps label above a figure: "TOTAL SPENT". */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Ink.muted) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
}

/** Rounded dark panel with a hairline border, used for every section. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    brush: Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    var m = modifier.clip(shape)
    m = if (brush != null) m.background(brush) else m.background(Ink.surface)
    m = m.border(BorderStroke(1.dp, Ink.border), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(padding), content = content)
}

/** Section title with an optional trailing action. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Capsule chip: filled when selected. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Ink.green,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) tint.copy(alpha = 0.18f) else Ink.surfaceHigh)
            .border(1.dp, if (selected) tint.copy(alpha = 0.7f) else Ink.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = if (selected) tint else Ink.muted)
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Ink.text else Ink.muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Coloured circle with an icon, used in transaction rows and legends. */
@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = color)
    }
}

/** Surface for dialogs and sheets so they match the dark theme. */
@Composable
fun DarkSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, color = Ink.surface, contentColor = Ink.text, content = content)
}

// ------------------------------------------------------------------ categories

/**
 * One fixed colour per category id, so a category keeps its colour in every chart and period
 * (colour follows the entity, never its rank). The first eight are the validated dark-mode palette.
 */
object CategoryStyle {
    private val fixed = mapOf(
        CategoryRules.GROCERIES to Color(0xFF199E70),
        CategoryRules.DINING to Color(0xFFD95926),
        CategoryRules.TRANSPORT to Color(0xFFC98500),
        CategoryRules.UTILITIES to Color(0xFF3987E5),
        CategoryRules.SHOPPING to Color(0xFFD55181),
        CategoryRules.TRAVEL to Color(0xFF9085E9),
        CategoryRules.HEALTH to Color(0xFFE66767),
        CategoryRules.ENTERTAINMENT to Color(0xFF2FA84F),
        CategoryRules.GOVERNMENT to Color(0xFF7D8BA3),
        CategoryRules.EDUCATION to Color(0xFF3FB2C0),
        CategoryRules.EMI_LOANS to Color(0xFFB07CC6),
        CategoryRules.CASH to Color(0xFF9CA83A),
        CategoryRules.INSURANCE to Color(0xFF5C7CFA),
        CategoryRules.OTHER to Color(0xFF8A8F98),
    )
    private val extra = listOf(
        Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
        Color(0xFFD55181), Color(0xFF9085E9), Color(0xFFE66767),
    )

    fun color(id: Long?): Color = when (id) {
        null -> Color(0xFF6B7280)
        else -> fixed[id] ?: extra[Math.floorMod(id, extra.size.toLong()).toInt()]
    }

    fun icon(id: Long?): ImageVector = when (id) {
        CategoryRules.GROCERIES -> Icons.Filled.ShoppingCart
        CategoryRules.DINING -> Icons.Filled.Restaurant
        CategoryRules.TRANSPORT -> Icons.Filled.LocalGasStation
        CategoryRules.UTILITIES -> Icons.Filled.Lightbulb
        CategoryRules.SHOPPING -> Icons.Filled.ShoppingBag
        CategoryRules.TRAVEL -> Icons.Filled.Flight
        CategoryRules.HEALTH -> Icons.Filled.LocalHospital
        CategoryRules.ENTERTAINMENT -> Icons.Filled.Movie
        CategoryRules.GOVERNMENT -> Icons.Filled.Gavel
        CategoryRules.EDUCATION -> Icons.Filled.School
        CategoryRules.EMI_LOANS -> Icons.Filled.AccountBalance
        CategoryRules.CASH -> Icons.Filled.LocalAtm
        CategoryRules.INSURANCE -> Icons.Filled.Security
        else -> Icons.Filled.Category
    }
}

/** Icon + colour for a transaction row: its category for spending, a direction icon otherwise. */
fun TransactionEntity.visual(): Pair<ImageVector, Color> = when (type) {
    TxnType.PAYMENT.name -> Icons.Filled.CreditCard to Ink.violet
    TxnType.TRANSFER_IN.name -> Icons.Filled.SouthWest to Ink.green
    TxnType.TRANSFER_OUT.name -> (if (counterpartyKey != null) Icons.Filled.CreditCard else Icons.Filled.NorthEast) to Ink.violet
    TxnType.REFUND.name -> (if (categoryId == null) Icons.Filled.Undo else CategoryStyle.icon(categoryId)) to Ink.green
    else -> CategoryStyle.icon(categoryId) to CategoryStyle.color(categoryId)
}

/** Fallback when a transaction is manual or unknown. */
val TransferIcon: ImageVector get() = Icons.Filled.SwapHoriz
val PaymentsIcon: ImageVector get() = Icons.Filled.Payments

// ------------------------------------------------------------------ bank colours

/** Gradient for a card tile, by bank. */
fun bankBrush(bank: String): Brush {
    val (a, b) = when {
        bank.startsWith("FAB", true) -> Color(0xFF1B3FA8) to Color(0xFF4C7DF0)
        bank.contains("NBD", true) || bank.contains("Emirates", true) -> Color(0xFF0B2E5C) to Color(0xFF2A73D8)
        bank.startsWith("ADCB", true) -> Color(0xFF8C1022) to Color(0xFFE0483E)
        bank.contains("Hilal", true) -> Color(0xFF0B5E4A) to Color(0xFF2BB38A)
        bank.startsWith("HSBC", true) -> Color(0xFF2B2E33) to Color(0xFFC9102A)
        bank.startsWith("Mashreq", true) -> Color(0xFFB2410B) to Color(0xFFF59E0B)
        else -> Color(0xFF3F2A9C) to Color(0xFF9B7BFF)
    }
    return Brush.linearGradient(listOf(a, b))
}

val heroNumberStyle: TextStyle
    @Composable get() = MaterialTheme.typography.displaySmall.copy(color = Ink.green, fontWeight = FontWeight.Bold)
