package com.uaefinancial.tracker.ui

import androidx.core.graphics.scale
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.uaefinancial.tracker.data.CardEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Your own card pictures (for example a crop of the card from a wallet app or the bank's website).
 * Stored privately in the app's files, scaled to at most 1400 px wide. Not included in backups.
 */
object CardImages {
    const val PREFIX = "img:"
    private const val MAX_W = 1400

    private fun dir(ctx: Context) = File(ctx.filesDir, "card_images").apply { mkdirs() }

    /** Deletes this card's stored pictures (when you remove the picture). */
    fun delete(ctx: Context, cardKey: String) {
        val safe = cardKey.filter { it.isLetterOrDigit() }
        dir(ctx).listFiles { f -> f.name.startsWith("$safe-") }?.forEach { it.delete() }
    }

    fun fileFor(ctx: Context, card: CardEntity): File? =
        card.themeKey?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.let { File(dir(ctx), it) }?.takeIf { it.exists() }

    /** Copies and downsizes the picture; returns the stored file name. Old pictures of this card are removed. */
    fun save(ctx: Context, cardKey: String, uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit } ?: error("can't open it")
        require(bounds.outWidth > 0) { "not an image" }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_W) sample *= 2
        val decoded = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("can't read it")
        val bmp = if (decoded.width > MAX_W) {
            decoded.scale(MAX_W, (decoded.height.toLong() * MAX_W / decoded.width).toInt())
        } else decoded
        val safe = cardKey.filter { it.isLetterOrDigit() }
        dir(ctx).listFiles { f -> f.name.startsWith("$safe-") }?.forEach { it.delete() }
        val name = "$safe-${System.currentTimeMillis()}.jpg"
        File(dir(ctx), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return name
    }
}

@Composable
fun rememberCardImage(card: CardEntity, thumbnail: Boolean = false): ImageBitmap? {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, card.themeKey, thumbnail) {
        value = withContext(Dispatchers.IO) {
            // Thumbnails are decoded at 1/8 size to save memory.
            val opts = BitmapFactory.Options().apply { inSampleSize = if (thumbnail) 8 else 1 }
            CardImages.fileFor(ctx, card)?.let { f -> runCatching { BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap() }.getOrNull() }
        }
    }
    return img
}

/**
 * Card background: your picture (with a soft shade so the figures stay readable) or the drawn look.
 * Use inside a clipped Box; returns true when a picture is shown (text should then be white).
 */
@Composable
fun BoxScope.CardBackground(card: CardEntity, art: CardArt, image: ImageBitmap?) {
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.35f), 0.45f to Color.Black.copy(alpha = 0.10f), 1f to Color.Black.copy(alpha = 0.70f)),
            ),
        )
    } else {
        Box(Modifier.matchParentSize().cardArt(art))
    }
}
