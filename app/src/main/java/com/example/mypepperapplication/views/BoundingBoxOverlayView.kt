package com.example.mypepperapplication.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.mypepperapplication.vision.BoundingBox
// ===========================================================================
// BOUNDING BOX OVERLAY VIEW
// ===========================================================================

/**
 * BoundingBoxOverlayView — disegna bounding boxes sulla preview camera.
 * Ratio Pepper top camera: 1280×960 = 4:3
 */
class BoundingBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Stato ─────────────────────────────────────────────────────────────────

    private var boxes: List<BoundingBox> = emptyList()

    // Ratio immagine Pepper (1280×960)
    private val imageRatio = 4f / 3f

    // ── Paint ─────────────────────────────────────────────────────────────────

    private val boxPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint().apply {
        color         = Color.WHITE
        textSize      = 36f
        isAntiAlias   = true
        isFakeBoldText = true
    }

    private val colorBySource = mapOf(
        "remote_yolo" to Color.parseColor("#00E676"),
        "mock"        to Color.parseColor("#FF9100"),
        "unknown"     to Color.parseColor("#40C4FF")
    )

    // ── API pubblica ──────────────────────────────────────────────────────────

    fun update(boxes: List<BoundingBox>, imgW: Int = 0, imgH: Int = 0) {
        if (imgW > 0 && imgH > 0) {
            val incomingRatio = imgW.toFloat() / imgH
            if(Math.abs(incomingRatio - imageRatio) > 0.01f)
                Log.w("BoundingBoxOverlayView", "Ratio Mismatch: waited $imageRatio, received $incomingRatio")
        }
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
        val viewRatio = vw / vh
        val imgLeft: Float
        val imgTop: Float
        val imgWidth: Float
        val imgHeight: Float

        if (viewRatio < imageRatio) {
            // Bande nere laterali — l'immagine occupa tutta l'altezza
            imgWidth  = vw
            imgHeight = vw / imageRatio
            imgLeft   = 0f
            imgTop    = (vh - imgHeight) / 2f
        } else {
            // Bande nere sopra/sotto — l'immagine occupa tutta la larghezza
            imgHeight = vh
            imgWidth  = vh * imageRatio
            imgLeft   = (vw - imgWidth) / 2f
            imgTop    = 0f
        }

        // ── Disegna ogni box ──────────────────────────────────────────────────

        for (box in boxes) {
            // Scala coordinate normalizzate → pixel nell'area effettiva immagine
            val left   = imgLeft + box.rect.left   * imgWidth
            val top    = imgTop  + box.rect.top    * imgHeight
            val right  = imgLeft + box.rect.right  * imgWidth
            val bottom = imgTop  + box.rect.bottom * imgHeight
            val rect   = RectF(left, top, right, bottom)

            val color = colorBySource[box.source] ?: Color.parseColor("#40C4FF")
            boxPaint.color = color

            canvas.drawRoundRect(rect, 8f, 8f, boxPaint)

            // Label: "person 92%"
            val labelText  = "${box.label} ${"%.0f".format(box.score * 100)}%"
            val textWidth  = labelPaint.measureText(labelText)
            val textHeight = labelPaint.textSize

            val labelLeft   = left
            val labelTop    = (top - textHeight - 8f).coerceAtLeast(0f)
            val labelRight  = left + textWidth + 16f
            val labelBottom = labelTop + textHeight + 8f

            labelBgPaint.color = color
            canvas.drawRoundRect(
                RectF(labelLeft, labelTop, labelRight, labelBottom),
                4f, 4f, labelBgPaint
            )
            canvas.drawText(labelText, labelLeft + 8f, labelBottom - 8f, labelPaint)
        }
    }
}