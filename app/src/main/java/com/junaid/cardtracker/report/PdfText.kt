package com.junaid.cardtracker.report

import android.content.Context
import android.net.Uri
import com.junaid.cardtracker.core.Glyph
import com.junaid.cardtracker.core.PrintedLine
import com.junaid.cardtracker.core.StatementReader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Reads a PDF on the phone into printed lines with the position of every character, so the statement reader
 * can tell columns apart (Debit / Credit / Balance, labels above values). Nothing leaves the phone.
 */
object PdfText {
    class PasswordNeeded : Exception("This PDF is password protected")

    /** Collects every character with its page, position and size. */
    private class GlyphCollector : PDFTextStripper() {
        val glyphs = ArrayList<Glyph>()
        override fun processTextPosition(text: TextPosition) {
            var t = text.unicode
            // Fonts without a proper character map: fall back to the raw character code (often the Unicode value).
            if (t.isNullOrBlank() && text.characterCodes != null) {
                t = text.characterCodes.filter { it in 32..0xFFFF }.map { it.toChar() }.joinToString("")
            }
            if (!t.isNullOrEmpty()) {
                glyphs += Glyph(currentPageNo - 1, text.xDirAdj, text.yDirAdj, text.widthDirAdj, text.fontSizeInPt, t)
            }
        }
    }

    fun readLines(context: Context, uri: Uri, password: String?): List<PrintedLine> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
        input.use { stream ->
            val doc = try {
                if (password.isNullOrEmpty()) PDDocument.load(stream) else PDDocument.load(stream, password)
            } catch (e: InvalidPasswordException) {
                throw PasswordNeeded()
            }
            doc.use {
                val c = GlyphCollector()
                c.getText(it) // drives processTextPosition for every page
                return StatementReader.linesFromGlyphs(c.glyphs)
            }
        }
    }

    /** Plain text of the lines (for "Share extracted text"). */
    fun asText(lines: List<PrintedLine>): String = lines.joinToString("\n") { it.text }
}
