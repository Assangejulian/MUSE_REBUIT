package com.muse.app

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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class CotOverlay(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var thinkingView: TextView? = null
    private var toolView: TextView? = null
    private var scroll: ScrollView? = null
    var onStop: (() -> Unit)? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(app)

    fun isShowing(): Boolean = root != null

    fun show(theme: String = "cream") {
        main.post {
            if (!canDraw() || root != null) return@post
            val isClaude = theme.startsWith("claude")
            val density = app.resources.displayMetrics.density
            val pad = (12 * density).toInt()

            val panelBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(if (isClaude) 0xF61C1A18.toInt() else 0xF211111B.toInt())
                setStroke((1 * density).toInt(), if (isClaude) 0xFF3D3833.toInt() else 0xFF313244.toInt())
            }

            val panel = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                background = panelBg
                setPadding(pad, pad, pad, pad)
                elevation = 12 * density
            }
            val title = TextView(app).apply {
                text = "Muse · Thinking"
                setTextColor(if (isClaude) 0xFFD97757.toInt() else 0xFFCBA6F7.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = if (isClaude) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD
            }
            val tool = TextView(app).apply {
                text = "准备中"
                setTextColor(if (isClaude) 0xFFE5AA5C.toInt() else 0xFFA6ADC8.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            val think = TextView(app).apply {
                text = "…"
                setTextColor(if (isClaude) 0xFFEDE7DF.toInt() else 0xFFCDD6F4.toInt())
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
                setColor(if (isClaude) 0xFFD97757.toInt() else 0xFFF38BA8.toInt())
            }
            val stop = TextView(app).apply {
                text = "停止"
                setTextColor(if (isClaude) 0xFFFFFFFF.toInt() else 0xFF11111B.toInt())
                background = stopBg
                typeface = Typeface.DEFAULT_BOLD
                setPadding((14 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setOnClickListener { onStop?.invoke() }
            }
            panel.addView(title)
            panel.addView(tool, lp(top = 4))
            panel.addView(scroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (120 * density).toInt(),
            ).apply { topMargin = (6 * density).toInt() })
            panel.addView(stop, lp(top = 8))

            var lastX = 0
            var lastY = 0
            var paramY = dp(48)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
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
            ).apply {
                gravity = Gravity.TOP
                y = paramY
            }
            title.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX.toInt()
                        lastY = event.rawY.toInt()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY.toInt() - lastY
                        lastY = event.rawY.toInt()
                        lastX = event.rawX.toInt()
                        params.y = (params.y + dy).coerceAtLeast(0)
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
        }
    }

    fun update(thinking: String, tool: String) {
        main.post {
            toolView?.text = tool.ifBlank { "Thinking" }
            val clipped = if (thinking.length > 2400) thinking.takeLast(2400) else thinking
            thinkingView?.text = clipped.ifBlank { "…" }
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    fun hide() {
        main.post {
            val view = root ?: return@post
            runCatching { wm.removeView(view) }
            root = null
            thinkingView = null
            toolView = null
            scroll = null
        }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = (top * app.resources.displayMetrics.density).toInt() }

    private fun dp(v: Int) = (v * app.resources.displayMetrics.density).toInt()
}
