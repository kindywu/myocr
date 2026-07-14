package com.example.myocr.drugentry

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 选区裁剪覆盖层
 *
 * 在图片上绘制半透明遮罩 + 可拖拽的矩形选区。
 * 支持移动选区、拖拽四角/四边调整大小。
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 待裁剪的图片 */
    var sourceBitmap: Bitmap? = null

    /** 裁剪结果回调 */
    var onCropComplete: ((croppedBitmap: Bitmap) -> Unit)? = null

    // 绘制工具
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#C0000000")  // 半透明黑色遮罩
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#C96442")    // 品牌色边框
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 0f, Color.parseColor("#80000000"))
    }
    private val handleBorderPaint = Paint().apply {
        color = Color.parseColor("#C96442")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // 图片显示区域（fitCenter 后的实际位置）
    private var imageRect = RectF()
    private var scaledBitmap: Bitmap? = null

    // 选区（在 View 坐标系中）
    private val selectionRect = RectF()

    // 拖拽状态
    private var dragMode = DragMode.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private val dragThreshold = 40f  // 触摸判定距离(dp)
    private val minSelectionSize = 100f  // 最小选区

    private enum class DragMode {
        NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM,
        LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        updateImageRect()
    }

    /**
     * 计算图片 fitCenter 后的实际显示区域
     */
    private fun updateImageRect() {
        val bmp = sourceBitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()

        val scale = minOf(viewW / bmpW, viewH / bmpH)
        val drawW = bmpW * scale
        val drawH = bmpH * scale
        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f

        imageRect = RectF(left, top, left + drawW, top + drawH)

        // 创建缩放后的位图用于显示
        val scaled = Bitmap.createScaledBitmap(bmp, drawW.toInt(), drawH.toInt(), true)
        scaledBitmap?.recycle()
        scaledBitmap = scaled

        // 初始选区设为图片的 70%（居中）
        val marginW = drawW * 0.15f
        val marginH = drawH * 0.15f
        selectionRect.set(
            left + marginW,
            top + marginH,
            left + drawW - marginW,
            top + drawH - marginH
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = scaledBitmap ?: return

        // 1. 绘制图片
        canvas.drawBitmap(bmp, imageRect.left, imageRect.top, null)

        // 2. 绘制遮罩（选区之外的部分）
        // 上
        canvas.drawRect(0f, 0f, width.toFloat(), selectionRect.top, dimPaint)
        // 下
        canvas.drawRect(0f, selectionRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        // 左
        canvas.drawRect(0f, selectionRect.top, selectionRect.left, selectionRect.bottom, dimPaint)
        // 右
        canvas.drawRect(selectionRect.right, selectionRect.top, width.toFloat(), selectionRect.bottom, dimPaint)

        // 3. 绘制选区边框
        canvas.drawRect(selectionRect, borderPaint)

        // 4. 绘制四角拖拽手柄
        drawHandle(canvas, selectionRect.left, selectionRect.top)
        drawHandle(canvas, selectionRect.right, selectionRect.top)
        drawHandle(canvas, selectionRect.left, selectionRect.bottom)
        drawHandle(canvas, selectionRect.right, selectionRect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val r = 16f
        canvas.drawCircle(x, y, r, handlePaint)
        canvas.drawCircle(x, y, r, handleBorderPaint)
    }

    // ==================== 触摸处理 ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = detectDragMode(event.x, event.y)
                if (dragMode != DragMode.NONE) {
                    dragStartX = event.x
                    dragStartY = event.y
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY
                dragSelection(dx, dy)
                dragStartX = event.x
                dragStartY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                dragMode = DragMode.NONE
                return true
            }
        }
        return false
    }

    private fun detectDragMode(x: Float, y: Float): DragMode {
        val t = dragThreshold

        // 检查是否在角落（优先）
        val nearLeft = abs(x - selectionRect.left) < t
        val nearRight = abs(x - selectionRect.right) < t
        val nearTop = abs(y - selectionRect.top) < t
        val nearBottom = abs(y - selectionRect.bottom) < t

        if (nearLeft && nearTop) return DragMode.LEFT_TOP
        if (nearRight && nearTop) return DragMode.RIGHT_TOP
        if (nearLeft && nearBottom) return DragMode.LEFT_BOTTOM
        if (nearRight && nearBottom) return DragMode.RIGHT_BOTTOM
        if (nearLeft) return DragMode.LEFT
        if (nearRight) return DragMode.RIGHT
        if (nearTop) return DragMode.TOP
        if (nearBottom) return DragMode.BOTTOM

        // 检查是否在选区内部（移动）
        if (selectionRect.contains(x, y)) return DragMode.MOVE

        return DragMode.NONE
    }

    private fun dragSelection(dx: Float, dy: Float) {
        when (dragMode) {
            DragMode.MOVE -> {
                val newLeft = selectionRect.left + dx
                val newTop = selectionRect.top + dy
                val newRight = selectionRect.right + dx
                val newBottom = selectionRect.bottom + dy
                // 约束在图片区域内
                if (newLeft >= imageRect.left && newRight <= imageRect.right) {
                    selectionRect.left = newLeft
                    selectionRect.right = newRight
                }
                if (newTop >= imageRect.top && newBottom <= imageRect.bottom) {
                    selectionRect.top = newTop
                    selectionRect.bottom = newBottom
                }
            }
            DragMode.LEFT -> {
                val newLeft = selectionRect.left + dx
                if (newLeft >= imageRect.left && selectionRect.right - newLeft >= minSelectionSize) {
                    selectionRect.left = newLeft
                }
            }
            DragMode.RIGHT -> {
                val newRight = selectionRect.right + dx
                if (newRight <= imageRect.right && newRight - selectionRect.left >= minSelectionSize) {
                    selectionRect.right = newRight
                }
            }
            DragMode.TOP -> {
                val newTop = selectionRect.top + dy
                if (newTop >= imageRect.top && selectionRect.bottom - newTop >= minSelectionSize) {
                    selectionRect.top = newTop
                }
            }
            DragMode.BOTTOM -> {
                val newBottom = selectionRect.bottom + dy
                if (newBottom <= imageRect.bottom && newBottom - selectionRect.top >= minSelectionSize) {
                    selectionRect.bottom = newBottom
                }
            }
            DragMode.LEFT_TOP -> {
                val nl = selectionRect.left + dx
                val nt = selectionRect.top + dy
                if (nl >= imageRect.left && selectionRect.right - nl >= minSelectionSize) selectionRect.left = nl
                if (nt >= imageRect.top && selectionRect.bottom - nt >= minSelectionSize) selectionRect.top = nt
            }
            DragMode.RIGHT_TOP -> {
                val nr = selectionRect.right + dx
                val nt = selectionRect.top + dy
                if (nr <= imageRect.right && nr - selectionRect.left >= minSelectionSize) selectionRect.right = nr
                if (nt >= imageRect.top && selectionRect.bottom - nt >= minSelectionSize) selectionRect.top = nt
            }
            DragMode.LEFT_BOTTOM -> {
                val nl = selectionRect.left + dx
                val nb = selectionRect.bottom + dy
                if (nl >= imageRect.left && selectionRect.right - nl >= minSelectionSize) selectionRect.left = nl
                if (nb <= imageRect.bottom && nb - selectionRect.top >= minSelectionSize) selectionRect.bottom = nb
            }
            DragMode.RIGHT_BOTTOM -> {
                val nr = selectionRect.right + dx
                val nb = selectionRect.bottom + dy
                if (nr <= imageRect.right && nr - selectionRect.left >= minSelectionSize) selectionRect.right = nr
                if (nb <= imageRect.bottom && nb - selectionRect.top >= minSelectionSize) selectionRect.bottom = nb
            }
            else -> {}
        }
    }

    // ==================== 裁剪 ====================

    /**
     * 获取裁剪后的位图
     */
    fun cropBitmap(): Bitmap? {
        val source = sourceBitmap ?: return null
        if (selectionRect.width() < 10 || selectionRect.height() < 10) return null

        // 计算选区在原始位图中的比例坐标
        val scaleX = source.width.toFloat() / imageRect.width()
        val scaleY = source.height.toFloat() / imageRect.height()

        val bmpLeft = ((selectionRect.left - imageRect.left) * scaleX).toInt().coerceAtLeast(0)
        val bmpTop = ((selectionRect.top - imageRect.top) * scaleY).toInt().coerceAtLeast(0)
        val bmpRight = ((selectionRect.right - imageRect.left) * scaleX).toInt()
            .coerceAtMost(source.width)
        val bmpBottom = ((selectionRect.bottom - imageRect.top) * scaleY).toInt()
            .coerceAtMost(source.height)

        val width = (bmpRight - bmpLeft).coerceAtLeast(1)
        val height = (bmpBottom - bmpTop).coerceAtLeast(1)

        return Bitmap.createBitmap(source, bmpLeft, bmpTop, width, height)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scaledBitmap?.recycle()
        scaledBitmap = null
    }
}
