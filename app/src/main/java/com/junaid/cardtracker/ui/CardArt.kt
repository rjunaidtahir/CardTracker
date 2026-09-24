package com.junaid.cardtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junaid.cardtracker.data.CardEntity

/** Decoration drawn over a card's gradient. Simple shapes only: an impression of each card, not the bank's artwork. */
enum class ArtPattern { NONE, CURVES, SHARDS, BLOB, RING, WAVES, TRIANGLES, STRIPE }

/**
 * A card look. [lightText] = white text (dark card) or near-black text (light card).
 * [product] is shown after the bank name when the card has no nickname.
 */
data class CardArt(
    val key: String,
    val name: String,
    val colors: List<Color>,
    val lightText: Boolean,
    val pattern: ArtPattern = ArtPattern.NONE,
    val patternColor: Color = Color.White,
    val product: String? = null,
    val network: String? = null,
) {
    val content: Color get() = if (lightText) Color.White else Color(0xFF161616)
    val contentMuted: Color get() = if (lightText) Color.White.copy(alpha = 0.78f) else Color(0xFF161616).copy(alpha = 0.72f)
    val brush: Brush get() = Brush.linearGradient(colors)
}

object CardArts {
    val presets: List<CardArt> = listOf(
        // ---- your cards (from the Samsung Wallet screenshot)
        CardArt("enbd-platinum", "ENBD Platinum", listOf(Color(0xFF5E6166), Color(0xFF9EA1A6), Color(0xFF6A6D72)), true, ArtPattern.CURVES, product = "Platinum Credit", network = "mastercard"),
        CardArt("rak-air", "RAKBANK Air Rewards", listOf(Color(0xFF2E2F32), Color(0xFF4A4C50)), true, ArtPattern.STRIPE, Color(0xFFE53935), product = "Air Rewards", network = "mastercard"),
        CardArt("hsbc-cashback", "HSBC Cashback", listOf(Color(0xFFF1F2F4), Color(0xFFC3C6CB)), false, ArtPattern.SHARDS, Color(0xFF2B2B2E), product = "Cashback Credit", network = "VISA"),
        CardArt("talabat", "talabat Platinum", listOf(Color(0xFFE6F06A), Color(0xFFC5D92E)), false, ArtPattern.BLOB, Color(0xFF6B4A2A), product = "talabat Platinum", network = "mastercard"),
        CardArt("tabby", "Tabby", listOf(Color(0xFF0B0B0C), Color(0xFF2A2A2D)), true, ArtPattern.NONE, product = "Tabby Visa", network = "VISA"),
        CardArt("mashreq-neo", "Mashreq Neo", listOf(Color(0xFFE85A12), Color(0xFFF08A2C), Color(0xFFEFB04A)), true, ArtPattern.WAVES, Color(0xFF7A2E0B), product = "Neo Visa Debit", network = "VISA"),
        CardArt("mashreq-cashback", "Mashreq Cashback", listOf(Color(0xFFF6A51C), Color(0xFFEE6B1F)), true, ArtPattern.CURVES, product = "Cashback Credit", network = "VISA"),
        CardArt("alhilal-cashback", "Al Hilal Cashback", listOf(Color(0xFF343538), Color(0xFF55575B)), true, ArtPattern.RING, Color(0xFF6E7075), product = "Cashback Credit", network = "mastercard"),
        CardArt("fab-indulge", "FAB Rewards Indulge", listOf(Color(0xFF0E8C9E), Color(0xFF43B9C8), Color(0xFF8ED9E0)), true, ArtPattern.TRIANGLES, product = "Rewards Indulge", network = "mastercard"),
        CardArt("fab-blue", "FAB Blue Signature", listOf(Color(0xFF082552), Color(0xFF0C3B80)), true, ArtPattern.RING, Color(0xFF1FA2F0), product = "Blue Signature", network = "VISA"),
        // ---- bank defaults
        CardArt("bank-fab", "FAB blue", listOf(Color(0xFF1B3FA8), Color(0xFF4C7DF0)), true, ArtPattern.CURVES),
        CardArt("bank-enbd", "Emirates NBD navy", listOf(Color(0xFF0B2E5C), Color(0xFF2A73D8)), true, ArtPattern.CURVES),
        CardArt("bank-adcb", "ADCB red", listOf(Color(0xFF8C1022), Color(0xFFE0483E)), true, ArtPattern.CURVES),
        CardArt("bank-alhilal", "Al Hilal teal", listOf(Color(0xFF0B5E4A), Color(0xFF2BB38A)), true, ArtPattern.RING, Color(0xFFFFFFFF)),
        CardArt("bank-hsbc", "HSBC", listOf(Color(0xFF2B2E33), Color(0xFFC9102A)), true, ArtPattern.SHARDS, Color(0xFFFFFFFF)),
        CardArt("bank-mashreq", "Mashreq orange", listOf(Color(0xFFB2410B), Color(0xFFF59E0B)), true, ArtPattern.CURVES),
        // ---- general looks you can pick for any card
        CardArt("midnight", "Midnight", listOf(Color(0xFF0F1226), Color(0xFF2B2F6B)), true, ArtPattern.CURVES),
        CardArt("emerald", "Emerald", listOf(Color(0xFF064E3B), Color(0xFF10B981)), true, ArtPattern.WAVES, Color(0xFF022C22)),
        CardArt("rose-gold", "Rose gold", listOf(Color(0xFFB76E79), Color(0xFFEBC1B7)), false, ArtPattern.CURVES),
        CardArt("carbon", "Carbon", listOf(Color(0xFF111111), Color(0xFF3A3A3A)), true, ArtPattern.TRIANGLES),
        CardArt("sunset", "Sunset", listOf(Color(0xFF7C3AED), Color(0xFFEC4899), Color(0xFFF59E0B)), true, ArtPattern.BLOB, Color(0xFFFFFFFF)),
        CardArt("gold", "Gold", listOf(Color(0xFF8A6A1F), Color(0xFFE9C46A)), false, ArtPattern.CURVES),
        CardArt("violet", "Violet", listOf(Color(0xFF3F2A9C), Color(0xFF9B7BFF)), true, ArtPattern.CURVES),
    )

    /** Automatic look by last 4 digits (edit freely). */
    private val byLast4 = mapOf(
        "9940" to "enbd-platinum", "3944" to "enbd-platinum",
        "0552" to "rak-air",
        "5258" to "hsbc-cashback",
        "3538" to "talabat",
        "6870" to "tabby",
        "0933" to "mashreq-neo",
        "4680" to "mashreq-cashback",
        "3976" to "alhilal-cashback",
        "0831" to "fab-indulge",
        "4776" to "fab-blue",
    )

    fun byKey(key: String?): CardArt? = presets.firstOrNull { it.key == key }

    private fun bankDefault(bank: String): CardArt = byKey(
        when {
            bank.startsWith("FAB", true) -> "bank-fab"
            bank.contains("NBD", true) || bank.contains("Emirates", true) -> "bank-enbd"
            bank.startsWith("ADCB", true) -> "bank-adcb"
            bank.contains("Hilal", true) -> "bank-alhilal"
            bank.startsWith("HSBC", true) -> "bank-hsbc"
            bank.startsWith("Mashreq", true) -> "bank-mashreq"
            else -> "violet"
        },
    )!!

    /** Your pick > the look for these last 4 digits > the bank's default. */
    fun forCard(c: CardEntity): CardArt = byKey(c.themeKey) ?: c.last4?.let { byKey(byLast4[it]) } ?: bankDefault(c.bank)

    /** What the tile shows as the card's name. */
    fun displayName(c: CardEntity): String = c.nickname ?: forCard(c).product?.let { "${shortBank(c.bank)} $it" } ?: c.cardKey

    fun shortBank(bank: String): String = when {
        bank.contains("NBD", true) -> "ENBD"
        else -> bank
    }
}

/** Card gradient plus its pattern. Apply after clip(). */
fun Modifier.cardArt(art: CardArt): Modifier = this.background(art.brush).drawBehind { drawPattern(art) }

private fun DrawScope.drawPattern(art: CardArt) {
    val w = size.width
    val h = size.height
    val pc = art.patternColor
    when (art.pattern) {
        ArtPattern.NONE -> Unit
        ArtPattern.CURVES -> {
            drawCircle(Color.White.copy(alpha = 0.10f), radius = w * 0.55f, center = Offset(w * 1.05f, -h * 0.1f), style = Stroke(w * 0.08f))
            drawCircle(Color.White.copy(alpha = 0.07f), radius = w * 0.75f, center = Offset(w * 1.1f, h * 0.2f), style = Stroke(w * 0.04f))
            drawCircle(Color.Black.copy(alpha = 0.06f), radius = w * 0.35f, center = Offset(w * 0.1f, h * 1.2f))
        }
        ArtPattern.STRIPE -> {
            drawRect(pc, topLeft = Offset(0f, h * 0.12f), size = Size(w * 0.012f + 4f, h * 0.18f))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = w * 0.5f, center = Offset(w, h))
        }
        ArtPattern.SHARDS -> {
            val shards = listOf(
                listOf(0.45f to 0.25f, 0.62f to 0.50f, 0.45f to 0.75f),
                listOf(0.62f to 0.50f, 0.80f to 0.25f, 0.80f to 0.75f),
                listOf(0.52f to 0.20f, 0.70f to 0.20f, 0.61f to 0.42f),
                listOf(0.52f to 0.80f, 0.70f to 0.80f, 0.61f to 0.58f),
            )
            shards.forEachIndexed { i, pts ->
                val p = Path().apply {
                    moveTo(w * pts[0].first, h * pts[0].second)
                    pts.drop(1).forEach { lineTo(w * it.first, h * it.second) }
                    close()
                }
                drawPath(p, pc.copy(alpha = if (i % 2 == 0) 0.55f else 0.25f))
            }
        }
        ArtPattern.BLOB -> {
            drawCircle(pc.copy(alpha = 0.85f), radius = h * 0.42f, center = Offset(w * 0.30f, h * 0.55f))
            drawCircle(Color(0xFF8DB43A).copy(alpha = 0.55f), radius = h * 0.52f, center = Offset(w * 0.30f, h * 0.55f), style = Stroke(h * 0.08f))
        }
        ArtPattern.RING -> {
            drawCircle(pc.copy(alpha = 0.55f), radius = h * 0.55f, center = Offset(w * 0.78f, h * 0.62f), style = Stroke(h * 0.12f))
        }
        ArtPattern.WAVES -> {
            for (i in 0..3) {
                val p = Path().apply {
                    val y = h * (0.35f + i * 0.18f)
                    moveTo(0f, y)
                    cubicTo(w * 0.3f, y - h * 0.25f, w * 0.6f, y + h * 0.25f, w, y - h * 0.05f)
                }
                drawPath(p, pc.copy(alpha = 0.18f + 0.06f * i), style = Stroke(h * 0.07f))
            }
        }
        ArtPattern.TRIANGLES -> {
            val cols = 9
            val rows = 5
            val cw = w / cols
            val rh = h / rows
            for (r in 0 until rows) for (c in 0 until cols) {
                val a = ((r * 7 + c * 3) % 5) / 5f
                val p = Path().apply {
                    if ((r + c) % 2 == 0) {
                        moveTo(c * cw, r * rh); lineTo((c + 1) * cw, r * rh); lineTo(c * cw + cw / 2, (r + 1) * rh)
                    } else {
                        moveTo(c * cw + cw / 2, r * rh); lineTo((c + 1) * cw, (r + 1) * rh); lineTo(c * cw, (r + 1) * rh)
                    }
                    close()
                }
                drawPath(p, Color.White.copy(alpha = 0.04f + a * 0.12f))
            }
        }
    }
}

/** Small bank-name badge (plain text, not the bank's logo) for quick recognition. */
@Composable
fun BankBadge(bank: String, onDarkCard: Boolean) {
    val (label, color) = when {
        bank.startsWith("FAB", true) -> "FAB" to Color(0xFF1B3FA8)
        bank.contains("NBD", true) || bank.contains("Emirates", true) -> "Emirates NBD" to Color(0xFF0B2E5C)
        bank.startsWith("ADCB", true) -> "ADCB" to Color(0xFFB3122E)
        bank.contains("Hilal", true) -> "Al Hilal" to Color(0xFF0B6B53)
        bank.startsWith("HSBC", true) -> "HSBC" to Color(0xFFC9102A)
        bank.startsWith("Mashreq", true) -> "mashreq" to Color(0xFFD9540B)
        bank.contains("RAK", true) -> "RAKBANK" to Color(0xFFC62828)
        else -> bank.take(12) to Color(0xFF3F2A9C)
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.clip(shape).background(if (onDarkCard) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.75f))
            .border(1.dp, Color.Black.copy(alpha = 0.06f), shape).padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(label, color = color, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1)
    }
}

/** Network word in the corner ("VISA" / "mastercard"), plain text. */
@Composable
fun NetworkMark(network: String?, color: Color) {
    network ?: return
    Text(
        network,
        color = color,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = if (network == "VISA") FontStyle.Italic else FontStyle.Normal,
        fontSize = if (network == "VISA") 16.sp else 12.sp,
    )
}

/** A small card preview (theme picker, arrange list). */
@Composable
fun CardSwatch(art: CardArt, width: Dp = 64.dp, height: Dp = 40.dp, selected: Boolean = false) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(width, height).clip(shape).cardArt(art)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) Ink.green else Color.Black.copy(alpha = 0.15f), shape),
        contentAlignment = Alignment.BottomEnd,
    ) {
        if (art.network != null) {
            Text(
                art.network,
                color = art.content, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(4.dp),
            )
        }
    }
}

/** Small preview of a card: your picture if you set one, otherwise its drawn look. */
@Composable
fun CardThumb(card: CardEntity, width: Dp = 64.dp, height: Dp = 40.dp) {
    val img = rememberCardImage(card, thumbnail = true)
    if (img == null) {
        CardSwatch(CardArts.forCard(card), width, height)
    } else {
        androidx.compose.foundation.Image(
            img, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(width, height).clip(RoundedCornerShape(8.dp)),
        )
    }
}
