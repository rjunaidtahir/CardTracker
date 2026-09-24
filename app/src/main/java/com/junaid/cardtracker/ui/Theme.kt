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
import androidx.compose.material.icons.filled.People
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** One colour theme. [accent] is used for money figures and selection; [accent2] is the second accent. */
data class Palette(
    val id: String,
    val name: String,
    val isLight: Boolean,
    val bg: Color,
    val bgTop: Color,
    val bgBottom: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val accent2: Color,
    val red: Color,
    val amber: Color,
)

/**
 * The themes offered in Settings. Text colours are chosen for at least 4.5:1 contrast on their surfaces
 * (muted text) and 3:1 for the faintest labels.
 */
object AppThemes {
    val all: List<Palette> = listOf(
        Palette(
            "neon", "Midnight Neon", false,
            bg = Color(0xFF07090C), bgTop = Color(0xFF0E1A14), bgBottom = Color(0xFF120D1F),
            surface = Color(0xFF12151A), surfaceHigh = Color(0xFF1B1F26), surfaceHighest = Color(0xFF262B34), border = Color(0xFF2C323C),
            text = Color(0xFFF4F6F8), muted = Color(0xFFB3BBC6), faint = Color(0xFF8A93A0),
            accent = Color(0xFF3BE37F), accentDim = Color(0xFF1C6B40), onAccent = Color(0xFF04210F),
            accent2 = Color(0xFFB39DFF), red = Color(0xFFFF7A7A), amber = Color(0xFFF5B942),
        ),
        Palette(
            "ocean", "Deep Ocean", false,
            bg = Color(0xFF050B14), bgTop = Color(0xFF0A1B2E), bgBottom = Color(0xFF071523),
            surface = Color(0xFF0E1826), surfaceHigh = Color(0xFF152234), surfaceHighest = Color(0xFF1E2E44), border = Color(0xFF243650),
            text = Color(0xFFF1F6FC), muted = Color(0xFFB0C0D4), faint = Color(0xFF8699B0),
            accent = Color(0xFF38D6F5), accentDim = Color(0xFF155E6E), onAccent = Color(0xFF02222A),
            accent2 = Color(0xFF8FA8FF), red = Color(0xFFFF7A7A), amber = Color(0xFFF5C451),
        ),
        Palette(
            "royal", "Royal Violet", false,
            bg = Color(0xFF0A0712), bgTop = Color(0xFF1A1030), bgBottom = Color(0xFF1C0C1B),
            surface = Color(0xFF161022), surfaceHigh = Color(0xFF20182F), surfaceHighest = Color(0xFF2B2140), border = Color(0xFF35294D),
            text = Color(0xFFF6F2FF), muted = Color(0xFFC2B8D8), faint = Color(0xFF978BB0),
            accent = Color(0xFFC6A6FF), accentDim = Color(0xFF4E3A80), onAccent = Color(0xFF1B0F3D),
            accent2 = Color(0xFFFF9EC4), red = Color(0xFFFF8080), amber = Color(0xFFF5C451),
        ),
        Palette(
            "amoled", "Pure Black", false,
            bg = Color(0xFF000000), bgTop = Color(0xFF000000), bgBottom = Color(0xFF000000),
            surface = Color(0xFF0D0D0F), surfaceHigh = Color(0xFF17171A), surfaceHighest = Color(0xFF222226), border = Color(0xFF2A2A2F),
            text = Color(0xFFFFFFFF), muted = Color(0xFFBDBDC4), faint = Color(0xFF8E8E96),
            accent = Color(0xFF3BE37F), accentDim = Color(0xFF1C6B40), onAccent = Color(0xFF04210F),
            accent2 = Color(0xFFB39DFF), red = Color(0xFFFF7A7A), amber = Color(0xFFF5B942),
        ),
        Palette(
            "light", "Daylight", true,
            bg = Color(0xFFF3F5F8), bgTop = Color(0xFFE6F4EC), bgBottom = Color(0xFFEEEAF8),
            surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF0F2F5), surfaceHighest = Color(0xFFE3E7EC), border = Color(0xFFD9DEE4),
            text = Color(0xFF0F172A), muted = Color(0xFF475569), faint = Color(0xFF64748B),
            accent = Color(0xFF0E8A4A), accentDim = Color(0xFFC9EBD7), onAccent = Color(0xFFFFFFFF),
            accent2 = Color(0xFF6D4AE0), red = Color(0xFFC62828), amber = Color(0xFFA35F00),
        ),
        Palette(
            "sand", "Warm Paper", true,
            bg = Color(0xFFF7F3EC), bgTop = Color(0xFFF4E9D8), bgBottom = Color(0xFFEFE7DA),
            surface = Color(0xFFFFFCF7), surfaceHigh = Color(0xFFF3EDE3), surfaceHighest = Color(0xFFE8E0D2), border = Color(0xFFDDD3C3),
            text = Color(0xFF231C12), muted = Color(0xFF5A4E3E), faint = Color(0xFF786A57),
            accent = Color(0xFFB4532A), accentDim = Color(0xFFF4D9CB), onAccent = Color(0xFFFFFFFF),
            accent2 = Color(0xFF2F6F8F), red = Color(0xFFB3261E), amber = Color(0xFF8A5A00),
        ),
    )

    fun byId(id: String?): Palette = all.firstOrNull { it.id == id } ?: all.first()

    /** The live theme; changing it recomposes the whole app. */
    var current by mutableStateOf(all.first())
}

/** Design tokens, read from the selected theme. `green` is the accent, `violet` the second accent (names kept for older code). */
object Ink {
    private val p get() = AppThemes.current
    val bg get() = p.bg
    val bgTop get() = p.bgTop
    val bgBottom get() = p.bgBottom
    val surface get() = p.surface
    val surfaceHigh get() = p.surfaceHigh
    val surfaceHighest get() = p.surfaceHighest
    val border get() = p.border
    val text get() = p.text
    val muted get() = p.muted
    val faint get() = p.faint
    val green get() = p.accent
    val greenDim get() = p.accentDim
    val onAccent get() = p.onAccent
    val violet get() = p.accent2
    val red get() = p.red
    val amber get() = p.amber
    val isLight get() = p.isLight
}

private fun schemeFor(p: Palette) = if (p.isLight) {
    lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentDim, onPrimaryContainer = p.text,
        secondary = p.accent2, onSecondary = Color.White, secondaryContainer = p.surfaceHighest, onSecondaryContainer = p.text,
        tertiary = p.amber, background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.surface, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHighest,
        outline = p.border, outlineVariant = p.border, error = p.red, onError = Color.White,
    )
} else {
    darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentDim, onPrimaryContainer = p.text,
        secondary = p.accent2, onSecondary = p.bg, secondaryContainer = p.surfaceHighest, onSecondaryContainer = p.text,
        tertiary = p.amber, background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHighest,
        outline = p.border, outlineVariant = p.border, error = p.red, onError = p.bg,
    )
}

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

/**
 * The app theme. Also sets the default text colour: screens sit on a transparent Scaffold over a gradient,
 * and without this any Text without an explicit colour would fall back to black.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val p = AppThemes.current
    MaterialTheme(colorScheme = schemeFor(p), typography = typography) {
        CompositionLocalProvider(LocalContentColor provides p.text, content = content)
    }
}

/** Screen background: a soft accent glow at the top and the second accent at the bottom. */
val screenBrush: Brush get() = Brush.verticalGradient(0f to Ink.bgTop, 0.35f to Ink.bg, 0.8f to Ink.bg, 1f to Ink.bgBottom)

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
    CompositionLocalProvider(LocalContentColor provides Ink.text) {
        Column(m.padding(padding), content = content)
    }
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
fun ThemedSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
        CategoryRules.INCOME to Color(0xFF22C55E),
        CategoryRules.FAMILY to Color(0xFFEC4899),
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
        CategoryRules.INCOME -> Icons.Filled.Payments
        CategoryRules.FAMILY -> Icons.Filled.People
        else -> Icons.Filled.Category
    }
}

/** Icon + colour for a transaction row: its category for spending, a direction icon otherwise. */
fun TransactionEntity.visual(): Pair<ImageVector, Color> = when (type) {
    TxnType.PAYMENT.name -> Icons.Filled.CreditCard to Ink.violet
    TxnType.TRANSFER_IN.name -> if (categoryId != null) CategoryStyle.icon(categoryId) to CategoryStyle.color(categoryId) else Icons.Filled.SouthWest to Ink.green
    TxnType.TRANSFER_OUT.name -> when {
        categoryId != null -> CategoryStyle.icon(categoryId) to CategoryStyle.color(categoryId)
        counterpartyKey != null -> Icons.Filled.CreditCard to Ink.violet
        else -> Icons.Filled.NorthEast to Ink.violet
    }
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
