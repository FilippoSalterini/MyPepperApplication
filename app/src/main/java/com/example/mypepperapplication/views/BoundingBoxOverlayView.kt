package com.example.mypepperapplication.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.mypepperapplication.vision.BoundingBox

/**
 * View trasparente sovrapposta alla ImageView della camera.
 * Disegna le bounding boxes e le label di ogni detection.
 *
 * Utilizzo nel layout XML:
 *
 *   <FrameLayout
 *       android:layout_width="match_parent"
 *       android:layout_height="200dp">
 *
 *       <ImageView
 *           android:id="@+id/ivCameraPreview"
 *           android:layout_width="match_parent"
 *           android:layout_height="match_parent"
 *           android:scaleType="fitCenter" />
 *
 *       <com.example.mypepperapplication.views.BoundingBoxOverlayView
 *           android:id="@+id/overlayView"
 *           android:layout_width="match_parent"
 *           android:layout_height="match_parent"
 *           android:background="@android:color/transparent" />
 *   </FrameLayout>
 */
class BoundingBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Stato ─────────────────────────────────────────────────────────────────

    private var boxes: List<BoundingBox> = emptyList()
    private var imageWidth: Int  = 1
    private var imageHeight: Int = 1

    // ── Paint ─────────────────────────────────────────────────────────────────

    private val boxPaint = Paint().apply {
        style     = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color     = Color.WHITE
        textSize  = 32f
        isAntiAlias = true
    }

    // Palette colori per label diverse
    private val palette = listOf(
        Color.parseColor("#FF4444"),  // rosso
        Color.parseColor("#44FF44"),  // verde
        Color.parseColor("#4488FF"),  // blu
        Color.parseColor("#FFAA00"),  // arancio
        Color.parseColor("#FF44FF"),  // viola
        Color.parseColor("#00FFFF"),  // ciano
    )

    // ── API pubblica ──────────────────────────────────────────────────────────

    /**
     * Aggiorna le detections da disegnare.
     * Thread-safe: chiama invalidate() sul main thread.
     */
    fun update(newBoxes: List<BoundingBox>, imgW: Int, imgH: Int) {
        boxes       = newBoxes
        imageWidth  = imgW
        imageHeight = imgH
        postInvalidate()   // sicuro da thread background
    }

    fun clear() {
        boxes = emptyList()
        postInvalidate()
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (boxes.isEmpty()) return

        val vw = width.toFloat()
        val vh = height.toFloat()

        // Calcola il rettangolo in cui la ImageView (fitCenter) mostra l'immagine
        val imageAspect  = imageWidth.toFloat() / imageHeight
        val viewAspect   = vw / vh
        val (imgLeft, imgTop, imgRight, imgBottom) = if (imageAspect > viewAspect) {
            // pillarbox (barre sopra/sotto)
            val scaledH = vw / imageAspect
            val margin  = (vh - scaledH) / 2f
            listOf(0f, margin, vw, margin + scaledH)
        } else {
            // letterbox (barre sinistra/destra)
            val scaledW = vh * imageAspect
            val margin  = (vw - scaledW) / 2f
            listOf(margin, 0f, margin + scaledW, vh)
        }
        val imgW = imgRight - imgLeft
        val imgH = imgBottom - imgTop

        boxes.forEachIndexed { idx, box ->
            val color = palette[idx % palette.size]
            boxPaint.color = color
            bgPaint.color  = color and 0x80FFFFFF.toInt() // 50% alpha

            // Coordinate pixel nella View
            val left   = imgLeft + box.rect.left   * imgW
            val top    = imgTop  + box.rect.top    * imgH
            val right  = imgLeft + box.rect.right  * imgW
            val bottom = imgTop  + box.rect.bottom * imgH

            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Label background
            val label   = "${box.label} ${"%.0f".format(box.score * 100)}%"
            val textW   = textPaint.measureText(label)
            val textH   = textPaint.textSize
            val labelTop = if (top > textH + 4) top - textH - 4 else bottom

            canvas.drawRect(left, labelTop, left + textW + 8, labelTop + textH + 4, bgPaint)
            canvas.drawText(label, left + 4, labelTop + textH, textPaint)
        }
    }
}