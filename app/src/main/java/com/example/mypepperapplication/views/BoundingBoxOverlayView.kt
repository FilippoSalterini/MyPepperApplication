package com.example.mypepperapplication.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.mypepperapplication.vision.BoundingBox

/**
 * BoundingBoxOverlayView — disegna bounding boxes sopra la preview camera.
 *
 * Le coordinate delle BoundingBox sono normalizzate [0,1] (prodotte dal server PC).
 * Questo view le scala rispetto alle sue dimensioni effettive su schermo.
 *
 * FIX rispetto alla versione precedente:
 *   Le coordinate NON dipendono dalla risoluzione Pepper (640×480).
 *   Usano direttamente width/height del view → corretto su qualsiasi schermo.
 *
 * Usage in XML:
 *   <com.example.mypepperapplication.views.BoundingBoxOverlayView
 *       android:id="@+id/overlayView"
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent" />
 *
 * Sovrapponi questo view all'ImageView della preview con lo stesso size.
 */
class BoundingBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Stato ─────────────────────────────────────────────────────────────────

    private var boxes: List<BoundingBox> = emptyList()

    // ── Paint ─────────────────────────────────────────────────────────────────

    private val boxPaint = Paint().apply {
        style     = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint().apply {
        color     = Color.WHITE
        textSize  = 36f
        isAntiAlias = true
        isFakeBoldText = true
    }

    // Colori per source
    private val colorBySource = mapOf(
        "remote_yolo" to Color.parseColor("#00E676"),  // verde brillante
        "mock"        to Color.parseColor("#FF9100"),  // arancio
        "unknown"     to Color.parseColor("#40C4FF")   // azzurro
    )

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Aggiorna le bounding boxes e ridisegna.
     *
     * @param boxes   Lista di BoundingBox con coordinate normalizzate [0,1]
     * @param imgW    Larghezza frame originale (non usato, mantenuto per compatibilità)
     * @param imgH    Altezza frame originale (non usato, mantenuto per compatibilità)
     */
    fun update(boxes: List<BoundingBox>, imgW: Int = 0, imgH: Int = 0) {
        this.boxes = boxes
        invalidate()
    }

    fun clear() {
        boxes = emptyList()
        invalidate()
    }

    // ── Disegno ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return

        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw == 0f || vh == 0f) return

        for (box in boxes) {
            // Scala coordinate normalizzate → pixel del view
            val left   = box.rect.left   * vw
            val top    = box.rect.top    * vh
            val right  = box.rect.right  * vw
            val bottom = box.rect.bottom * vh
            val screenRect = RectF(left, top, right, bottom)

            // Colore per source
            val color = colorBySource[box.source] ?: colorBySource["unknown"]!!
            boxPaint.color = color

            // Disegna rettangolo
            canvas.drawRoundRect(screenRect, 8f, 8f, boxPaint)

            // Label: "person 92%"
            val labelText = "${box.label} ${"%.0f".format(box.score * 100)}%"
            val textWidth  = labelPaint.measureText(labelText)
            val textHeight = labelPaint.textSize

            val labelLeft   = left
            val labelTop    = (top - textHeight - 8f).coerceAtLeast(0f)
            val labelRight  = left + textWidth + 16f
            val labelBottom = labelTop + textHeight + 8f

            // Sfondo label
            labelBgPaint.color = color
            canvas.drawRoundRect(
                RectF(labelLeft, labelTop, labelRight, labelBottom),
                4f, 4f, labelBgPaint
            )

            // Testo label
            canvas.drawText(
                labelText,
                labelLeft + 8f,
                labelBottom - 8f,
                labelPaint
            )
        }
    }
}