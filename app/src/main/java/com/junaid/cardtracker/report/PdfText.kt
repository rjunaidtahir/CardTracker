package com.junaid.cardtracker.report

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/** Text of a PDF, one line per printed row. Nothing leaves the phone. */
object PdfText {
    class PasswordNeeded : Exception("This PDF is password protected")

    fun read(context: Context, uri: Uri, password: String?): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open the file")
        input.use { stream ->
            val doc = try {
                if (password.isNullOrEmpty()) PDDocument.load(stream) else PDDocument.load(stream, password)
            } catch (e: InvalidPasswordException) {
                throw PasswordNeeded()
            }
            doc.use {
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                return stripper.getText(it)
            }
        }
    }
}
