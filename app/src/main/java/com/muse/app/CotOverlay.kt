package com.muse.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class CotOverlay(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var thinkingView: TextView? = null
    private var toolView: TextView? = null
    private var scroll: ScrollView? = null
    private var mode = Mode.Hidden
    private var theme = "cream"
    private var lastThink = ""
    private var lastTool = "Thinking"
    private var ballX = 0
    private var ballY = 0
    private var panelY = 0
    var onStop: (() -> Unit)? = null

    enum class Mode { Hidden, Panel, Ball }

    fun canDraw(): Boolean = Settings.canDrawOverlays(app)

    fun isShowing(): Boolean = mode == Mode.Panel

    fun isPresent(): Boolean = mode != Mode.Hidden

    fun mode(): Mode = mode

    fun show(theme: String = this.theme) {
        main.post {
            if (!canDraw()) return@post
            this.theme = theme
            attachPanel()
        }
    }

    fun collapse(theme: String = this.theme) {
        main.post {
            if (!canDraw()) return@post
            this.theme = theme
            attachBall()
        }
    }

    fun update(thinking: String, tool: String) {
        lastThink = thinking
        lastTool = tool.ifBlank { "Thinking" }
        main.post {
            toolView?.text = lastTool
            val clipped = if (thinking.length > 2400) thinking.takeLast(2400) else thinking
            thinkingView?.text = clipped.ifBlank { "…" }
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun hide() {
        main.post { detach() }
    }

    private fun attachPanel() {
        detach()
        val colors = OverlayTheme.colors(theme)
        val density = app.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val panelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(colors.panelBg)
            setStroke((1 * density).toInt(), colors.stroke)
        }
        val panel = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(pad, pad, pad, pad)
            elevation = 12 * density
        }
        val title = TextView(app).apply {
            text = "Muse · Thinking"
            setTextColor(colors.title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = if (colors.serif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD
        }
        val tool = TextView(app).apply {
            text = lastTool.ifBlank { "准备中" }
            setTextColor(colors.tool)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val think = TextView(app).apply {
            text = lastThink.ifBlank { "…" }.let { if (it.length > 2400) it.takeLast(2400) else it }
            setTextColor(colors.think)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.18f)
        }
        val scroller = ScrollView(app).apply {
            isFillViewport = true
            addView(
                think,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val stopBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(colors.stopBg)
        }
        val stop = TextView(app).apply {
            text = "停止"
            setTextColor(colors.stopFg)
            background = stopBg
            typeface = Typeface.DEFAULT_BOLD
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { onStop?.invoke() }
        }
        val minifyBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(colors.stroke)
        }
        val minify = TextView(app).apply {
            text = "收起"
            setTextColor(colors.think)
            background = minifyBg
            setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { attachBall() }
        }
        val actions = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(minify, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (8 * density).toInt()
            })
            addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel.addView(title)
        panel.addView(tool, lp(top = 4))
        panel.addView(
            scroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (120 * density).toInt(),
            ).apply { topMargin = (6 * density).toInt() },
        )
        panel.addView(actions, lp(top = 8))

        if (panelY == 0) panelY = dp(48)
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP
            y = panelY
        }
        var lastY = 0
        title.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY.toInt() - lastY
                    lastY = event.rawY.toInt()
                    params.y = (params.y + dy).coerceAtLeast(0)
                    panelY = params.y
                    runCatching { wm.updateViewLayout(panel, params) }
                    true
                }
                else -> false
            }
        }
        wm.addView(panel, params)
        root = panel
        thinkingView = think
        toolView = tool
        scroll = scroller
        mode = Mode.Panel
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBall() {
        detach()
        val colors = OverlayTheme.colors(theme)
        val size = dp(52)
        val density = app.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colors.ball)
        }
        val label = TextView(app).apply {
            text = "M"
            setTextColor(colors.ballFg)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            gravity = android.view.Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = if (colors.serif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD
            this.background = bg
            elevation = 10 * density
        }
        val wrap = FrameLayout(app).apply {
            addView(label, FrameLayout.LayoutParams(size, size))
        }
        val metrics = app.resources.displayMetrics
        if (ballX == 0 && ballY == 0) {
            ballX = metrics.widthPixels - size - dp(16)
            ballY = (metrics.heightPixels * 0.35f).toInt()
        }
        val params = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX.coerceIn(0, metrics.widthPixels - size)
            y = ballY.coerceIn(0, metrics.heightPixels - size)
        }
        var downX = 0
        var downY = 0
        var startX = 0
        var startY = 0
        var dragged = false
        wrap.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX.toInt()
                    downY = event.rawY.toInt()
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - downX
                    val dy = event.rawY.toInt() - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                    params.x = (startX + dx).coerceIn(0, metrics.widthPixels - size)
                    params.y = (startY + dy).coerceIn(0, metrics.heightPixels - size)
                    ballX = params.x
                    ballY = params.y
                    runCatching { wm.updateViewLayout(wrap, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragged) attachPanel()
                    true
                }
                else -> false
            }
        }
        wm.addView(wrap, params)
        root = wrap
        thinkingView = null
        toolView = null
        scroll = null
        mode = Mode.Ball
    }

    private fun detach() {
        val view = root ?: return
        runCatching { wm.removeView(view) }
        root = null
        thinkingView = null
        toolView = null
        scroll = null
        mode = Mode.Hidden
    }

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = (top * app.resources.displayMetrics.density).toInt() }

    private fun dp(v: Int) = (v * app.resources.displayMetrics.density).toInt()
}

internal object OverlayTheme {
    data class Colors(
        val panelBg: Int,
        val stroke: Int,
        val title: Int,
        val tool: Int,
        val think: Int,
        val stopBg: Int,
        val stopFg: Int,
        val ball: Int,
        val ballFg: Int,
        val serif: Boolean,
    )

    fun colors(theme: String): Colors {
        val key = theme.lowercase()
        return when {
            key == "claude_dark" || key == "claudedark" -> Colors(
                panelBg = 0xF61C1A18.toInt(),
                stroke = 0xFF3D3833.toInt(),
                title = 0xFFD97757.toInt(),
                tool = 0xFFE5AA5C.toInt(),
                think = 0xFFEDE7DF.toInt(),
                stopBg = 0xFFD97757.toInt(),
                stopFg = 0xFFFFFFFF.toInt(),
                ball = 0xFFD97757.toInt(),
                ballFg = 0xFFFFFFFF.toInt(),
                serif = true,
            )
            key.startsWith("claude") -> Colors(
                panelBg = 0xF6FAF7F2.toInt(),
                stroke = 0xFFDCD5C9.toInt(),
                title = 0xFFD97757.toInt(),
                tool = 0xFF706C64.toInt(),
                think = 0xFF1F1E1D.toInt(),
                stopBg = 0xFFD97757.toInt(),
                stopFg = 0xFFFFFFFF.toInt(),
                ball = 0xFFD97757.toInt(),
                ballFg = 0xFFFFFFFF.toInt(),
                serif = true,
            )
            key == "mocha" -> Colors(
                panelBg = 0xF211111B.toInt(),
                stroke = 0xFF313244.toInt(),
                title = 0xFFCBA6F7.toInt(),
                tool = 0xFFA6ADC8.toInt(),
                think = 0xFFCDD6F4.toInt(),
                stopBg = 0xFFF38BA8.toInt(),
                stopFg = 0xFF11111B.toInt(),
                ball = 0xFFCBA6F7.toInt(),
                ballFg = 0xFF11111B.toInt(),
                serif = false,
            )
            key == "latte" -> Colors(
                panelBg = 0xF6EFF1F5.toInt(),
                stroke = 0xFFCCD0DA.toInt(),
                title = 0xFF8839EF.toInt(),
                tool = 0xFF6C6F85.toInt(),
                think = 0xFF4C4F69.toInt(),
                stopBg = 0xFF8839EF.toInt(),
                stopFg = 0xFFFFFFFF.toInt(),
                ball = 0xFF8839EF.toInt(),
                ballFg = 0xFFFFFFFF.toInt(),
                serif = false,
            )
            else -> Colors(
                panelBg = 0xF6FBFAF6.toInt(),
                stroke = 0xFFEFE4D4.toInt(),
                title = 0xFFC4785A.toInt(),
                tool = 0xFF7A6B5D.toInt(),
                think = 0xFF3C322A.toInt(),
                stopBg = 0xFFC4785A.toInt(),
                stopFg = 0xFFFBFAF6.toInt(),
                ball = 0xFFC4785A.toInt(),
                ballFg = 0xFFFBFAF6.toInt(),
                serif = false,
            )
        }
    }
}
