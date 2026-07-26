package com.fingerly.app.highway

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.fingerly.app.log.RemoteLog
import com.fingerly.core.notation.Staff

/**
 * Draws one notated pitch on a five-line staff (SPEC §4a-F item F1).
 *
 * Deliberately small: a staff, a clef, ledger lines and a single black notehead.
 * That is the whole vocabulary the landmark atoms need, and adding engraving
 * features nobody is reading yet is how this kind of module stops shipping.
 *
 * Glyphs come from Bravura (SMuFL reference font, SIL OFL) in `assets/`. SMuFL
 * defines 1 em = 4 staff spaces and anchors each clef to the line it names, so
 * placement is arithmetic rather than eyeballed nudges.
 *
 * Hot-path rules (SPEC §1): every paint, rect and buffer here is allocated once
 * at construction. [draw] runs inside the frame loop and allocates nothing.
 */
class StaffRenderer(context: Context) {

    private val typeface: Typeface? = load(context)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(196, 206, 212)
        strokeWidth = 2f
    }
    private val ledgerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(196, 206, 212)
        strokeWidth = 2.5f
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 240, 244)
        typeface = this@StaffRenderer.typeface
    }
    private val noteheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        typeface = this@StaffRenderer.typeface
    }
    /** Fallback notehead when the font is missing: an oval, not a crash. */
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
    }

    private val ledgerBuf = IntArray(Staff.MAX_LEDGER_LINES)
    private val oval = RectF()

    /**
     * @param centerY y of the MIDDLE staff line (half-space 4).
     * @param staffSpace pixels between two adjacent staff lines.
     * @param staffWidth horizontal extent of the five lines, centred on [centerX].
     */
    fun draw(
        canvas: Canvas,
        midi: Int,
        clef: Int,
        centerX: Float,
        centerY: Float,
        staffSpace: Float,
        staffWidth: Float,
    ) {
        val half = staffSpace / 2f
        val left = centerX - staffWidth / 2f
        val right = centerX + staffWidth / 2f

        var h = 0
        while (h <= Staff.TOP_HALF_SPACE) {
            val y = centerY - (h - 4) * half
            canvas.drawLine(left, y, right, y, linePaint)
            h += 2
        }

        // The notehead sits right of centre so the clef has room at the left.
        val noteX = centerX + staffWidth * 0.18f
        val noteY = centerY - (Staff.halfSpaces(midi, clef) - 4) * half

        val ledgerCount = Staff.ledgerLines(midi, clef, ledgerBuf)
        val ledgerHalfWidth = staffSpace * 0.95f
        for (i in 0 until ledgerCount) {
            val y = centerY - (ledgerBuf[i] - 4) * half
            canvas.drawLine(noteX - ledgerHalfWidth, y, noteX + ledgerHalfWidth, y, ledgerPaint)
        }

        if (typeface != null) {
            // SMuFL: 1 em = 4 staff spaces, clef baseline sits on its named line.
            val em = staffSpace * 4f
            glyphPaint.textSize = em
            noteheadPaint.textSize = em
            val clefGlyph = if (clef == Staff.CLEF_BASS) GLYPH_F_CLEF else GLYPH_G_CLEF
            val clefY = centerY - (Staff.clefAnchorHalfSpaces(clef) - 4) * half
            canvas.drawText(clefGlyph, left + staffSpace * 0.5f, clefY, glyphPaint)
            // Notehead origin is its left edge, vertically centred on the pitch.
            canvas.drawText(
                GLYPH_NOTEHEAD_BLACK,
                noteX - noteheadPaint.measureText(GLYPH_NOTEHEAD_BLACK) / 2f,
                noteY,
                noteheadPaint,
            )
        } else {
            // No font: still readable. An oval on the right line, and the line the
            // clef would have named drawn heavy so the landmark rule survives.
            val anchorY = centerY - (Staff.clefAnchorHalfSpaces(clef) - 4) * half
            ledgerPaint.strokeWidth = 6f
            canvas.drawLine(left, anchorY, left + staffSpace * 2f, anchorY, ledgerPaint)
            ledgerPaint.strokeWidth = 2.5f
            oval.set(
                noteX - staffSpace * 0.62f, noteY - half,
                noteX + staffSpace * 0.62f, noteY + half,
            )
            canvas.drawOval(oval, fallbackPaint)
        }
    }

    private companion object {
        const val ASSET = "Bravura.otf"

        // SMuFL codepoints, escaped: these live in the Private Use Area and
        // must not depend on source-file encoding surviving a round trip.
        const val GLYPH_G_CLEF = "\uE050"
        const val GLYPH_F_CLEF = "\uE062"
        const val GLYPH_NOTEHEAD_BLACK = "\uE0A4"

        /** Loaded once per renderer; a missing font degrades, never crashes. */
        fun load(context: Context): Typeface? = try {
            Typeface.createFromAsset(context.assets, ASSET)
        } catch (e: RuntimeException) {
            RemoteLog.log("staff", "Bravura load failed: ${e.message}")
            null
        }
    }
}
