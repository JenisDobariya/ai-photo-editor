package com.photo_editor.my_photo_editor.ui.textlens

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.photo_editor.my_photo_editor.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Google-Lens-style text picker: draws the photo fitted to the view (pinch-zoom, pan, fling,
 * double-tap zoom), a soft highlight over every recognised word, and the selected words in accent
 * with two draggable handles.
 *
 * Gestures:
 *  - tap a word → selects its line (tap again inside that exact line → its whole block);
 *  - drag starting on a word, or long-press anywhere near text then drag → reading-order range
 *    from the first word to the word nearest the finger;
 *  - drag a handle → moves that end of the selection (handles may cross);
 *  - tap empty space → clears; drag on empty space → pans; two fingers → zoom + pan.
 *
 * The transform is a plain scale + translate (image px → view px). Word rects in view space are
 * recomputed only when the transform or the words change, so [onDraw] does no mapping or allocation.
 */
class TextSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** User-driven selection changes (reading-order, inclusive); (-1, -1) when cleared. */
    var onSelectionChanged: ((start: Int, end: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---- Content ----
    private var bitmap: Bitmap? = null
    private var words: List<Word> = emptyList()
    private var wordCount = 0
    private var imgRects: Array<RectF> = emptyArray()
    /** [imgRects] mapped to view space and padded; refreshed in [onTransformChanged]. */
    private var viewRects: Array<RectF> = emptyArray()
    private var lineIds = IntArray(0)
    private var blockIds = IntArray(0)

    // ---- Selection ----
    private var selStart = -1
    private var selEnd = -1
    /** One merged rect per selected line run (pooled, grown outside onDraw). */
    private val selSegments = ArrayList<RectF>()
    private var selSegmentCount = 0
    private var startHandleX = 0f
    private var startHandleTop = 0f
    private var startHandleBottom = 0f
    private var endHandleX = 0f
    private var endHandleTop = 0f
    private var endHandleBottom = 0f

    // ---- Transform ----
    private var scale = 1f
    private var transX = 0f
    private var transY = 0f
    private var fitScale = 1f
    private var minScale = 1f
    private var maxScale = 1f
    /** Space covered by the top/bottom bars; the fitted photo stays between them. */
    private var fitTop = 0f
    private var fitBottom = 0f

    // ---- Look ----
    private val accent = ContextCompat.getColor(context, R.color.lumina_accent)
    private val boxRadius = resources.getDimension(R.dimen.radius_s)
    private val boxPad = dp(2f)
    private val handleRadius = dp(7f)
    private val handleTouchRadius = dp(26f)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.BLACK }
    private val wordFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val wordStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val selFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; alpha = SEL_FILL_ALPHA; style = Paint.Style.FILL
    }
    private val selStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.FILL }
    private val handleStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND
    }

    /** 0..1 photo dim (animated). */
    private var dim = 0f
    private var dimAnimator: ValueAnimator? = null
    /** 0..1 fade-in of the word highlights when they first arrive. */
    private var reveal = 0f
    private var revealAnimator: ValueAnimator? = null
    private var zoomAnimator: ValueAnimator? = null
    private val scroller = OverScroller(context)

    // ---- Touch ----
    private enum class Mode { NONE, PENDING, PAN, SCALE, SELECT, HANDLE }

    private var mode = Mode.NONE
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downWord = -1
    /** The fixed end of a drag selection. */
    private var anchor = -1
    private var draggingStartHandle = false
    /** Finger → text offset while dragging a handle (so the text under the handle is what counts). */
    private var handleDx = 0f
    private var handleDy = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var tapOnEmpty = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = Mode.SCALE
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            // Two-finger pan: follow the focus point.
            transX += detector.focusX - lastFocusX
            transY += detector.focusY - lastFocusY
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            clampTranslation()
            onTransformChanged()
            return true
        }
    }).apply {
        // Double-tap is our zoom toggle; quick-scale would fight it.
        isQuickScaleEnabled = false
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        // Word taps react on finger-up (no double-tap delay) so selection feels instant.
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode != Mode.PENDING) return false
            if (downWord >= 0) {
                tapOnEmpty = false
                tapWord(downWord)
            } else {
                tapOnEmpty = true
            }
            return true
        }

        // Clearing waits for the double-tap timeout so double-tap-to-zoom keeps the selection.
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tapOnEmpty && selStart >= 0) applySelection(-1, -1, notify = true)
            tapOnEmpty = false
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            tapOnEmpty = false
            toggleZoom(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode != Mode.PENDING || wordCount == 0) return
            val word = if (downWord >= 0) downWord else nearestWord(e.x, e.y, dp(48f))
            if (word < 0) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            beginRangeSelect(word)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (mode != Mode.PAN) return false
            fling(velocityX, velocityY)
            return true
        }
    })

    init {
        isClickable = true
        contentDescription = "Photo with selectable text"
    }

    // ------------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------------

    fun setImage(bitmap: Bitmap?) {
        if (this.bitmap === bitmap) return
        this.bitmap = bitmap
        stopAnimations()
        resetToFit()
    }

    /** Sets the recognised words; the current selection is kept if it still fits. */
    fun setWords(words: List<Word>) {
        if (this.words === words) return
        this.words = words
        wordCount = words.size
        imgRects = Array(wordCount) { RectF(words[it].box) }
        viewRects = Array(wordCount) { RectF() }
        lineIds = IntArray(wordCount) { words[it].lineId }
        blockIds = IntArray(wordCount) { words[it].blockId }
        if (selEnd >= wordCount) {
            selStart = -1
            selEnd = -1
        }
        revealAnimator?.cancel()
        if (wordCount > 0) {
            reveal = 0f
            revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320
                interpolator = DecelerateInterpolator()
                addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        onTransformChanged()
    }

    /** Programmatic selection (from the ViewModel); does not call [onSelectionChanged]. */
    fun setSelection(start: Int, end: Int) {
        // Before words arrive just remember it; setWords() validates.
        if (wordCount == 0) {
            selStart = start
            selEnd = end
            return
        }
        val valid = start in 0..end && end < wordCount
        applySelection(if (valid) start else -1, if (valid) end else -1, notify = false)
    }

    /** How much to darken the photo (0..1), animated. */
    fun setDimLevel(level: Float) {
        if (dimAnimator?.isRunning != true && abs(dim - level) < 0.001f) return
        dimAnimator?.cancel()
        dimAnimator = ValueAnimator.ofFloat(dim, level).apply {
            duration = 250
            addUpdateListener { dim = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Heights (px) covered by overlaid bars; the photo is fitted between them. */
    fun setFitInsets(top: Float, bottom: Float) {
        if (abs(top - fitTop) < 0.5f && abs(bottom - fitBottom) < 0.5f) return
        val wasFit = abs(scale - fitScale) < 0.0001f * fitScale || bitmap == null
        fitTop = top
        fitBottom = bottom
        if (bitmap == null || width == 0) return
        computeFitScale()
        if (wasFit) {
            // Glide to the new fit (e.g. when the action chips appear) instead of jumping.
            animateZoomTo(fitScale, width / 2f, height / 2f)
        } else {
            scale = scale.coerceIn(minScale, maxScale)
            clampTranslation()
            onTransformChanged()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Transform
    // ------------------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stopAnimations()
        resetToFit()
    }

    private fun computeFitScale() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val availH = max(height - fitTop - fitBottom, height * 0.4f)
        fitScale = min(width / b.width.toFloat(), availH / b.height.toFloat())
        minScale = fitScale
        // Enough to read small print: 6x the fit, and at least 2 screen px per photo px.
        maxScale = max(fitScale * 6f, 2f)
    }

    private fun resetToFit() {
        if (bitmap == null || width == 0) {
            invalidate()
            return
        }
        computeFitScale()
        scale = fitScale
        clampTranslation()
        onTransformChanged()
    }

    private fun clampX(tx: Float, s: Float): Float {
        val b = bitmap ?: return tx
        val cw = b.width * s
        return if (cw <= width) (width - cw) / 2f else tx.coerceIn(width - cw, 0f)
    }

    private fun clampY(ty: Float, s: Float): Float {
        val b = bitmap ?: return ty
        val ch = b.height * s
        val top = fitTop
        val bottom = height - fitBottom
        val avail = bottom - top
        return if (ch <= avail) top + (avail - ch) / 2f else ty.coerceIn(bottom - ch, top)
    }

    private fun clampTranslation() {
        transX = clampX(transX, scale)
        transY = clampY(transY, scale)
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        val f = newScale / scale
        transX = fx - (fx - transX) * f
        transY = fy - (fy - transY) * f
        scale = newScale
    }

    private fun panBy(dx: Float, dy: Float) {
        transX += dx
        transY += dy
        clampTranslation()
        onTransformChanged()
    }

    private fun toggleZoom(fx: Float, fy: Float) {
        if (bitmap == null) return
        if (scale > fitScale * 1.05f) {
            animateZoomTo(fitScale, fx, fy)
        } else {
            animateZoomTo(min(fitScale * 2.5f, maxScale), fx, fy)
        }
    }

    /** Animates to [target] scale keeping the photo point under (fx, fy) in place (then clamped). */
    private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
        if (bitmap == null) return
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
        val s0 = scale
        val x0 = transX
        val y0 = transY
        val ratio = target / s0
        val x1 = clampX(fx - (fx - x0) * ratio, target)
        val y1 = clampY(fy - (fy - y0) * ratio, target)
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                scale = s0 + (target - s0) * t
                transX = x0 + (x1 - x0) * t
                transY = y0 + (y1 - y0) * t
                onTransformChanged()
            }
            start()
        }
    }

    private fun fling(vx: Float, vy: Float) {
        val b = bitmap ?: return
        val cw = b.width * scale
        val ch = b.height * scale
        val minX = if (cw > width) (width - cw).toInt() else transX.toInt()
        val maxX = if (cw > width) 0 else transX.toInt()
        val bottom = height - fitBottom
        val minY = if (ch > bottom - fitTop) (bottom - ch).toInt() else transY.toInt()
        val maxY = if (ch > bottom - fitTop) fitTop.toInt() else transY.toInt()
        scroller.fling(transX.toInt(), transY.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            transX = scroller.currX.toFloat()
            transY = scroller.currY.toFloat()
            clampTranslation()
            onTransformChanged()
            postInvalidateOnAnimation()
        }
    }

    private fun stopAnimations() {
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
    }

    /** Re-derives everything that depends on the transform, then redraws. */
    private fun onTransformChanged() {
        for (i in 0 until wordCount) {
            val src = imgRects[i]
            viewRects[i].set(
                src.left * scale + transX - boxPad,
                src.top * scale + transY - boxPad,
                src.right * scale + transX + boxPad,
                src.bottom * scale + transY + boxPad
            )
        }
        updateSelectionGeometry()
        invalidate()
    }

    // ------------------------------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------------------------------

    private fun applySelection(start: Int, end: Int, notify: Boolean) {
        if (start == selStart && end == selEnd) return
        selStart = start
        selEnd = end
        updateSelectionGeometry()
        invalidate()
        if (notify) {
            if ((mode == Mode.SELECT || mode == Mode.HANDLE) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
            }
            onSelectionChanged?.invoke(start, end)
        }
    }

    /** Merges selected words into one rect per line run and places the handles. */
    private fun updateSelectionGeometry() {
        selSegmentCount = 0
        if (selStart < 0 || selEnd >= wordCount) return
        var current: RectF? = null
        for (i in selStart..selEnd) {
            val r = viewRects[i]
            if (current == null || lineIds[i] != lineIds[i - 1]) {
                if (selSegmentCount == selSegments.size) selSegments.add(RectF())
                current = selSegments[selSegmentCount++]
                current.set(r)
            } else {
                current.union(r)
            }
        }
        val first = viewRects[selStart]
        val last = viewRects[selEnd]
        startHandleX = first.left
        startHandleTop = first.top
        startHandleBottom = first.bottom
        endHandleX = last.right
        endHandleTop = last.top
        endHandleBottom = last.bottom
    }

    /** Tap on a word: its line; tapping inside an exactly-selected line widens to the block. */
    private fun tapWord(word: Int) {
        val (lineStart, lineEnd) = runOf(word, lineIds)
        if (selStart == lineStart && selEnd == lineEnd) {
            val (blockStart, blockEnd) = runOf(word, blockIds)
            applySelection(blockStart, blockEnd, notify = true)
        } else {
            applySelection(lineStart, lineEnd, notify = true)
        }
    }

    /** Contiguous run of words sharing [ids][word] (lines/blocks are contiguous in reading order). */
    private fun runOf(word: Int, ids: IntArray): Pair<Int, Int> {
        var s = word
        var e = word
        while (s > 0 && ids[s - 1] == ids[word]) s--
        while (e < wordCount - 1 && ids[e + 1] == ids[word]) e++
        return s to e
    }

    private fun beginRangeSelect(word: Int) {
        zoomAnimator?.cancel()
        mode = Mode.SELECT
        anchor = word
        parent?.requestDisallowInterceptTouchEvent(true)
        applySelection(word, word, notify = true)
    }

    private fun updateRangeSelect(x: Float, y: Float) {
        val word = nearestWord(x, y, Float.MAX_VALUE)
        if (word < 0) return
        applySelection(min(anchor, word), max(anchor, word), notify = true)
    }

    private fun updateHandleDrag(x: Float, y: Float) {
        if (selStart < 0) return
        val word = nearestWord(x + handleDx, y + handleDy, Float.MAX_VALUE)
        if (word < 0) return
        val fixed = if (draggingStartHandle) selEnd else selStart
        // Handles may cross: the dragged one then becomes the other end.
        if (draggingStartHandle && word > fixed) draggingStartHandle = false
        else if (!draggingStartHandle && word < fixed) draggingStartHandle = true
        applySelection(min(fixed, word), max(fixed, word), notify = true)
    }

    // ------------------------------------------------------------------------------------------
    // Hit testing
    // ------------------------------------------------------------------------------------------

    /** Word whose (slop-expanded) box contains the point; the closest one if several do. */
    private fun wordAt(x: Float, y: Float, slop: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until wordCount) {
            val r = viewRects[i]
            if (x < r.left - slop || x > r.right + slop || y < r.top - slop || y > r.bottom + slop) continue
            val d = distanceSq(r, x, y)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /** Nearest word, favouring the same line (vertical distance weighs more). */
    private fun nearestWord(x: Float, y: Float, maxDist: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until wordCount) {
            val d = distanceSq(viewRects[i], x, y)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (maxDist == Float.MAX_VALUE || bestDist <= maxDist * maxDist) best else -1
    }

    private fun distanceSq(r: RectF, x: Float, y: Float): Float {
        val dx = max(max(r.left - x, 0f), x - r.right)
        val dy = max(max(r.top - y, 0f), y - r.bottom) * VERTICAL_WEIGHT
        return dx * dx + dy * dy
    }

    /** -1 start handle, 1 end handle, 0 none. Start knob sits above the text, end knob below. */
    private fun handleAt(x: Float, y: Float): Int {
        if (selStart < 0 || selEnd >= wordCount) return 0
        val ds = hypot(x - startHandleX, y - (startHandleTop - handleRadius))
        val de = hypot(x - endHandleX, y - (endHandleBottom + handleRadius))
        val hitStart = ds <= handleTouchRadius
        val hitEnd = de <= handleTouchRadius
        return when {
            hitStart && hitEnd -> if (ds <= de) -1 else 1
            hitStart -> -1
            hitEnd -> 1
            else -> 0
        }
    }

    // ------------------------------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        // Stop motion first so a double-tap started by this DOWN can animate.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) stopAnimations()
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                val handle = handleAt(event.x, event.y)
                if (handle != 0) {
                    mode = Mode.HANDLE
                    draggingStartHandle = handle < 0
                    val anchorX = if (draggingStartHandle) startHandleX else endHandleX
                    val anchorY = if (draggingStartHandle) (startHandleTop + startHandleBottom) / 2f
                    else (endHandleTop + endHandleBottom) / 2f
                    handleDx = anchorX - event.x
                    handleDy = anchorY - event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    downWord = if (wordCount > 0) wordAt(event.x, event.y, dp(6f)) else -1
                    mode = Mode.PENDING
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger always means zoom/pan; any drag-selection so far is kept.
                mode = Mode.SCALE
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                when (mode) {
                    Mode.PENDING -> if (hypot(x - downX, y - downY) > touchSlop) {
                        if (downWord >= 0) {
                            beginRangeSelect(downWord)
                            updateRangeSelect(x, y)
                        } else {
                            zoomAnimator?.cancel()
                            mode = Mode.PAN
                            lastX = x
                            lastY = y
                        }
                    }
                    Mode.PAN -> {
                        panBy(x - lastX, y - lastY)
                        lastX = x
                        lastY = y
                    }
                    Mode.SELECT -> updateRangeSelect(x, y)
                    Mode.HANDLE -> updateHandleDrag(x, y)
                    else -> Unit
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                if (event.getPointerId(upIndex) == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newIndex)
                }
                // Back to one finger after pinching: keep panning with it.
                if (event.pointerCount - 1 == 1 && mode == Mode.SCALE) mode = Mode.PAN
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) {
                    lastX = event.getX(index)
                    lastY = event.getY(index)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                activePointerId = MotionEvent.INVALID_POINTER_ID
                downWord = -1
            }
        }
        return true
    }

    // ------------------------------------------------------------------------------------------
    // Drawing (no allocation)
    // ------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled) return

        canvas.save()
        canvas.translate(transX, transY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(b, 0f, 0f, bitmapPaint)
        canvas.restore()

        if (dim > 0f) {
            dimPaint.alpha = (dim * 255).toInt()
            canvas.drawRect(transX, transY, transX + b.width * scale, transY + b.height * scale, dimPaint)
        }

        if (wordCount == 0 || reveal <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()

        wordFillPaint.alpha = (WORD_FILL_ALPHA * reveal).toInt()
        wordStrokePaint.alpha = (WORD_STROKE_ALPHA * reveal).toInt()
        for (i in 0 until wordCount) {
            if (i in selStart..selEnd) continue
            val r = viewRects[i]
            if (r.right < 0f || r.left > w || r.bottom < 0f || r.top > h) continue
            canvas.drawRoundRect(r, boxRadius, boxRadius, wordFillPaint)
            canvas.drawRoundRect(r, boxRadius, boxRadius, wordStrokePaint)
        }

        if (selSegmentCount == 0) return
        for (k in 0 until selSegmentCount) {
            val r = selSegments[k]
            if (r.right < 0f || r.left > w || r.bottom < 0f || r.top > h) continue
            canvas.drawRoundRect(r, boxRadius, boxRadius, selFillPaint)
            canvas.drawRoundRect(r, boxRadius, boxRadius, selStrokePaint)
        }

        // Handles: start knob above the first word, end knob below the last one.
        canvas.drawLine(startHandleX, startHandleTop - handleRadius, startHandleX, startHandleBottom, handleStemPaint)
        canvas.drawCircle(startHandleX, startHandleTop - handleRadius, handleRadius, handlePaint)
        canvas.drawLine(endHandleX, endHandleTop, endHandleX, endHandleBottom + handleRadius, handleStemPaint)
        canvas.drawCircle(endHandleX, endHandleBottom + handleRadius, handleRadius, handlePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimations()
        dimAnimator?.cancel()
        revealAnimator?.cancel()
    }

    private companion object {
        const val WORD_FILL_ALPHA = 51      // ~20% white
        const val WORD_STROKE_ALPHA = 64    // faint edge so boxes read on bright photos
        const val SEL_FILL_ALPHA = 115      // ~45% accent
        const val VERTICAL_WEIGHT = 2f
    }
}
