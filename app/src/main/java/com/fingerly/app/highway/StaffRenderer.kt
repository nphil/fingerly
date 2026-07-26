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

    /** Uniform ink for excerpt noteheads — the cursor is the only accent. */
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 240, 244)
        typeface = this@StaffRenderer.typeface
    }
    private val hollowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 240, 244)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val ledgerBuf = IntArray(Staff.MAX_LEDGER_LINES)
    private val barlineBuf = DoubleArray(Staff.MAX_BARLINES)
    private val oval = RectF()

    /**
     * @param midiCenterY y of MIDDLE C — the centre of the grand staff, and the
     *   line the whole landmark system is measured from.
     * @param staffSpace pixels between two adjacent staff lines.
     * @param staffWidth horizontal extent of the lines, centred on [centerX].
     */
    fun draw(
        canvas: Canvas,
        midi: Int,
        clef: Int,
        centerX: Float,
        midiCenterY: Float,
        staffSpace: Float,
        staffWidth: Float,
    ) {
        val half = staffSpace / 2f
        val left = centerX - staffWidth / 2f
        val right = centerX + staffWidth / 2f
        fun yOf(ruler: Int) = midiCenterY - ruler * half

        // BOTH staves, always. The middle-C rule is only teachable if the thing
        // it refers to is on screen: C4's ledger line falls exactly halfway
        // between them, which is visible rather than asserted. The staff being
        // read is full strength; the other is dimmed so the eye knows where to go.
        drawStaffLines(canvas, Staff.TREBLE_LINE_STEPS, left, right, ::yOf, clef == Staff.CLEF_TREBLE)
        drawStaffLines(canvas, Staff.BASS_LINE_STEPS, left, right, ::yOf, clef == Staff.CLEF_BASS)

        // The notehead sits right of centre so the clefs have room at the left.
        val noteX = centerX + staffWidth * 0.20f
        val noteY = yOf(Staff.stepsFromMiddleC(midi))

        val ledgerCount = Staff.ledgerLines(midi, clef, ledgerBuf)
        val ledgerHalfWidth = staffSpace * 0.95f
        for (i in 0 until ledgerCount) {
            val y = yOf(Staff.rulerOfHalfSpaces(ledgerBuf[i], clef))
            canvas.drawLine(noteX - ledgerHalfWidth, y, noteX + ledgerHalfWidth, y, ledgerPaint)
        }

        val trebleAnchorY = yOf(
            Staff.rulerOfHalfSpaces(Staff.clefAnchorHalfSpaces(Staff.CLEF_TREBLE), Staff.CLEF_TREBLE),
        )
        val bassAnchorY = yOf(
            Staff.rulerOfHalfSpaces(Staff.clefAnchorHalfSpaces(Staff.CLEF_BASS), Staff.CLEF_BASS),
        )

        if (typeface != null) {
            // SMuFL: 1 em = 4 staff spaces, clef baseline sits on its named line.
            val em = staffSpace * 4f
            glyphPaint.textSize = em
            noteheadPaint.textSize = em
            val clefX = left + staffSpace * 0.5f
            glyphPaint.alpha = if (clef == Staff.CLEF_TREBLE) 255 else DIM_ALPHA
            canvas.drawText(GLYPH_G_CLEF, clefX, trebleAnchorY, glyphPaint)
            glyphPaint.alpha = if (clef == Staff.CLEF_BASS) 255 else DIM_ALPHA
            canvas.drawText(GLYPH_F_CLEF, clefX, bassAnchorY, glyphPaint)
            glyphPaint.alpha = 255
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
            val anchorY = if (clef == Staff.CLEF_BASS) bassAnchorY else trebleAnchorY
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

    /**
     * A whole excerpt: both staves, barlines, and every note laid out with x as
     * time (SPEC §4 — notation is a chart). This is the cold-read surface.
     *
     * Notes are passed as parallel arrays rather than objects because this runs
     * per frame and the hot-path rule forbids allocating (SPEC §1). [count] may
     * be smaller than the arrays.
     *
     * Deliberately absent: beams, dots, tuplets, accidentals, key signatures,
     * rests, slurs, dynamics. A four-bar diatonic C3–G4 excerpt needs none of
     * them, and an engraving engine is where this kind of module stops shipping.
     */
    fun drawExcerpt(
        canvas: Canvas,
        midi: IntArray,
        startBeats: DoubleArray,
        durationBeats: DoubleArray,
        clefs: ByteArray,
        count: Int,
        totalBeats: Double,
        beatsPerBar: Int,
        centerX: Float,
        midiCenterY: Float,
        staffSpace: Float,
        staffWidth: Float,
        /** Index of the note to highlight as "next", or -1 for none. */
        cursor: Int = -1,
    ) {
        val half = staffSpace / 2f
        val left = centerX - staffWidth / 2f
        val right = centerX + staffWidth / 2f
        fun yOf(ruler: Int) = midiCenterY - ruler * half
        fun xOf(beat: Double) =
            left + (Staff.xFraction(beat, totalBeats) * staffWidth).toFloat()

        // Both staves at full strength: an excerpt is read across the pair.
        linePaint.alpha = 255
        for (s in Staff.TREBLE_LINE_STEPS) canvas.drawLine(left, yOf(s), right, yOf(s), linePaint)
        for (s in Staff.BASS_LINE_STEPS) canvas.drawLine(left, yOf(s), right, yOf(s), linePaint)

        // The brace: one vertical rule joining the staves, which is what makes
        // the pair read as a single system rather than two unrelated staves.
        val topY = yOf(Staff.TREBLE_LINE_STEPS.last())
        val bottomY = yOf(Staff.BASS_LINE_STEPS.first())
        canvas.drawLine(left, topY, left, bottomY, linePaint)

        val barCount = Staff.barlineFractions(totalBeats, beatsPerBar, barlineBuf)
        for (i in 0 until barCount) {
            val x = left + (barlineBuf[i] * staffWidth).toFloat()
            canvas.drawLine(x, topY, x, bottomY, linePaint)
        }

        if (typeface != null) {
            val em = staffSpace * 4f
            glyphPaint.textSize = em
            glyphPaint.alpha = 255
            val clefX = left + staffSpace * 0.5f
            canvas.drawText(
                GLYPH_G_CLEF, clefX,
                yOf(
                    Staff.rulerOfHalfSpaces(
                        Staff.clefAnchorHalfSpaces(Staff.CLEF_TREBLE), Staff.CLEF_TREBLE,
                    ),
                ),
                glyphPaint,
            )
            canvas.drawText(
                GLYPH_F_CLEF, clefX,
                yOf(
                    Staff.rulerOfHalfSpaces(
                        Staff.clefAnchorHalfSpaces(Staff.CLEF_BASS), Staff.CLEF_BASS,
                    ),
                ),
                glyphPaint,
            )
            noteheadPaint.textSize = em
        }

        var i = 0
        while (i < count) {
            val clef = clefs[i].toInt()
            val ruler = Staff.stepsFromMiddleC(midi[i])
            val x = xOf(startBeats[i])
            val y = yOf(ruler)

            val ledgerCount = Staff.ledgerLines(midi[i], clef, ledgerBuf)
            val ledgerHalfWidth = staffSpace * 0.95f
            for (k in 0 until ledgerCount) {
                val ly = yOf(Staff.rulerOfHalfSpaces(ledgerBuf[k], clef))
                canvas.drawLine(x - ledgerHalfWidth, ly, x + ledgerHalfWidth, ly, ledgerPaint)
            }

            val head = Staff.headForBeats(durationBeats[i])
            // The cursor is the only colour difference: read position, not a
            // verdict. Everything else is uniform ink.
            val paint = if (i == cursor) noteheadPaint else inkPaint
            if (typeface != null) {
                val glyph = when (head) {
                    Staff.HEAD_WHOLE -> GLYPH_NOTEHEAD_WHOLE
                    Staff.HEAD_HALF -> GLYPH_NOTEHEAD_HALF
                    else -> GLYPH_NOTEHEAD_BLACK
                }
                paint.textSize = staffSpace * 4f
                val w = paint.measureText(glyph)
                canvas.drawText(glyph, x - w / 2f, y, paint)
                if (head != Staff.HEAD_WHOLE) {
                    // Stem turns at the staff's own middle line, per clef.
                    val fromMiddle = Staff.halfSpaces(midi[i], clef) - 4
                    val up = Staff.stemUp(fromMiddle)
                    val sx = if (up) x + w / 2f else x - w / 2f
                    val sy = if (up) y - staffSpace * 3.5f else y + staffSpace * 3.5f
                    canvas.drawLine(sx, y, sx, sy, ledgerPaint)
                }
            } else {
                oval.set(x - staffSpace * 0.62f, y - half, x + staffSpace * 0.62f, y + half)
                if (head == Staff.HEAD_QUARTER) canvas.drawOval(oval, paint) else {
                    canvas.drawOval(oval, hollowPaint)
                }
            }
            i++
        }
    }

    private inline fun drawStaffLines(
        canvas: Canvas,
        steps: IntArray,
        left: Float,
        right: Float,
        yOf: (Int) -> Float,
        active: Boolean,
    ) {
        linePaint.alpha = if (active) 255 else DIM_ALPHA
        for (s in steps) {
            val y = yOf(s)
            canvas.drawLine(left, y, right, y, linePaint)
        }
        linePaint.alpha = 255
    }

    private companion object {
        const val ASSET = "Bravura.otf"

        /** The staff not being read stays visible but recedes. */
        const val DIM_ALPHA = 70

        // SMuFL codepoints, escaped: these live in the Private Use Area and
        // must not depend on source-file encoding surviving a round trip.
        const val GLYPH_G_CLEF = "\uE050"
        const val GLYPH_F_CLEF = "\uE062"
        const val GLYPH_NOTEHEAD_BLACK = "\uE0A4"
        const val GLYPH_NOTEHEAD_HALF = "\uE0A3"
        const val GLYPH_NOTEHEAD_WHOLE = "\uE0A2"

        /** Loaded once per renderer; a missing font degrades, never crashes. */
        fun load(context: Context): Typeface? = try {
            Typeface.createFromAsset(context.assets, ASSET)
        } catch (e: RuntimeException) {
            RemoteLog.log("staff", "Bravura load failed: ${e.message}")
            null
        }
    }
}
