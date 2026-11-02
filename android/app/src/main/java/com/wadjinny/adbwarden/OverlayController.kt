package com.wadjinny.adbwarden

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * "Agent in control" indicator: a glowing screen border (touches pass through) and a draggable pill
 * with the last action and a Stop button. Needs "display over other apps"; without it the ongoing
 * notification is the only indicator.
 */
class OverlayController(private val ctx: Context, private val core: Core) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var border: BorderView? = null
    private var pill: LinearLayout? = null
    private var pillText: TextView? = null
    private var pillParams: WindowManager.LayoutParams? = null

    fun canShow() = Settings.canDrawOverlays(ctx)

    fun show(s: ActiveSession) = main.post {
        if (!canShow()) {
            core.log("warn", "Overlay permission missing: only the notification shows that an agent is in control")
            return@post
        }
        if (border == null) addBorder()
        if (pill == null) addPill()
        update(s)
    }

    fun update(s: ActiveSession) = main.post {
        pillText?.text = "Agent in control · ${s.lastAction ?: s.agent}"
    }

    fun hide() = main.post {
        border?.let { it.stop(); runCatching { wm.removeView(it) } }
        pill?.let { runCatching { wm.removeView(it) } }
        border = null; pill = null; pillText = null; pillParams = null
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private fun addBorder() {
        val v = BorderView(ctx, dp(3f), dp(28f))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Android 12+ only lets touches pass through other apps' overlays at ≤ 0.8 window alpha.
            alpha = 0.8f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "AdbWardenBorder"
        }
        runCatching { wm.addView(v, lp); v.start(); border = v }
            .onFailure { core.log("error", "Could not show the border overlay: ${it.message}") }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun addPill() {
        val bg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF4F46E5.toInt(), 0xFF9333EA.toInt()))
            .apply { cornerRadius = dp(999f) }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            elevation = dp(8f)
            setPadding(dp(14f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF4ADE80.toInt()) }
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply { marginEnd = dp(8f).toInt() })
        val text = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(220f).toInt(); setText("Agent in control")
        }
        row.addView(text)
        val stop = TextView(ctx).apply {
            setText("Stop"); setTextColor(0xFFBE123C.toInt()); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(0xFFFFE4E6.toInt()) }
            setPadding(dp(14f).toInt(), dp(6f).toInt(), dp(14f).toInt(), dp(6f).toInt())
            setOnClickListener { core.stopSession() }
        }
        row.addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(10f).toInt() })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44f).toInt()
            title = "AdbWardenPill"
        }
        // Drag anywhere on the pill except the Stop button.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        row.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > dp(6f) || abs(dy) > dp(6f))) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt(); lp.y = (startY + dy.toInt()).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(row, lp) }
                    }
                    true
                }
                else -> true
            }
        }
        runCatching { wm.addView(row, lp); pill = row; pillText = text; pillParams = lp }
            .onFailure { core.log("error", "Could not show the control pill: ${it.message}") }
    }

    private class BorderView(ctx: Context, private val stroke: Float, private val radius: Float) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
        private val rect = RectF()
        private var anim: ValueAnimator? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF6366F1.toInt(), 0xFFA855F7.toInt(), Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val h = stroke / 2
            rect.set(h, h, width - h, height - h)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        fun start() {
            anim = ValueAnimator.ofFloat(1f, 0.45f).apply {
                duration = 1400; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { alpha = it.animatedValue as Float }
                start()
            }
        }

        fun stop() { anim?.cancel() }
    }
}
