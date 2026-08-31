package dev.touchcompositor.app

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.touchcompositor.adb.TouchAdbController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Small setup panel shown over Android's Wireless debugging settings. */
class PairingSetupService : Service() {
    companion object {
        const val ACTION_SHOW = "dev.touchcompositor.app.SHOW_PAIRING_SETUP"
        const val ACTION_HIDE = "dev.touchcompositor.app.HIDE_PAIRING_SETUP"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pairingInFlight = AtomicBoolean(false)
    private lateinit var wm: WindowManager
    private lateinit var environment: RuntimeEnvironment

    private var panel: View? = null
    private var statusView: TextView? = null
    private var addressInput: EditText? = null
    private var codeInput: EditText? = null
    private var pairButton: TextView? = null
    @Volatile private var finished = false

    private val tick = object : Runnable {
        override fun run() {
            if (finished || panel == null) return
            val endpoint = environment.discoverySnapshot().pairing
            val address = addressInput
            if (endpoint != null && address != null && !address.hasFocus() && address.text.isBlank()) {
                address.setText(endpoint.toString())
            }
            statusView?.text = if (endpoint != null) {
                "已发现配对端口 · 输入设置页显示的 6 位配对码"
            } else {
                "等待设置页的配对端口…请保持“使用配对码配对设备”窗口打开"
            }
            main.postDelayed(this, 350)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        environment = RuntimeEnvironment(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> stopSelf()
            else -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                } else {
                    finished = false
                    showPanel()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        finished = true
        main.removeCallbacks(tick)
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        environment.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun showPanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(Color.argb(242, 249, 250, 252), 16f, Color.argb(155, 180, 184, 192))
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "无线调试配对"
            textSize = 15f
            setTextColor(Color.rgb(28, 31, 36))
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        header.addView(TextView(this).apply {
            text = "×"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(105, 110, 120))
            setOnClickListener { stopSelf() }
        }, LinearLayout.LayoutParams(dp(42), dp(38)))
        root.addView(header)

        statusView = TextView(this).apply {
            text = "正在寻找配对端口…"
            textSize = 11f
            setTextColor(Color.rgb(105, 110, 120))
            setPadding(dp(2), 0, dp(2), dp(8))
        }.also(root::addView)

        addressInput = EditText(this).apply {
            hint = "配对地址（通常会自动填写）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
            setSingleLine(true)
            textSize = 13f
            setTextColor(Color.rgb(28, 31, 36))
            setHintTextColor(Color.rgb(145, 150, 159))
            setPadding(dp(11), 0, dp(11), 0)
            background = rounded(Color.argb(230, 255, 255, 255), 10f, Color.argb(110, 160, 165, 174))
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, dp(44))) }

        codeInput = EditText(this).apply {
            hint = "6 位配对码"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            textSize = 15f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(28, 31, 36))
            setHintTextColor(Color.rgb(145, 150, 159))
            setPadding(dp(11), 0, dp(11), 0)
            background = rounded(Color.argb(230, 255, 255, 255), 10f, Color.argb(110, 160, 165, 174))
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { startPairing(); true } else false
            }
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7) }) }

        pairButton = TextView(this).apply {
            text = "配对并启动"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(225, 48, 99, 211), 11f)
            setOnClickListener { startPairing() }
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(9) }) }

        val width = minOf(dp(330), resources.displayMetrics.widthPixels - dp(20))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(18)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(root, params)
        panel = root
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun startPairing() {
        if (!pairingInFlight.compareAndSet(false, true)) return
        val endpoint = TouchAdbController.parseEndpoint(addressInput?.text?.toString().orEmpty())
            ?: environment.discoverySnapshot().pairing
        val code = codeInput?.text?.toString()?.trim().orEmpty()
        when {
            endpoint == null -> {
                pairingInFlight.set(false)
                statusView?.text = "还没有发现配对端口，请保持系统配对码窗口打开"
                return
            }
            !code.matches(Regex("\\d{6}")) -> {
                pairingInFlight.set(false)
                statusView?.text = "请输入系统显示的 6 位配对码"
                return
            }
        }

        val targetEndpoint = endpoint ?: return
        pairButton?.isEnabled = false
        pairButton?.alpha = 0.62f
        pairButton?.text = "正在配对…"
        statusView?.text = "正在建立本机 ADB 身份并启动运行环境…"
        io.execute {
            val result = environment.pairAndStart(targetEndpoint, code)
            pairingInFlight.set(false)
            main.post {
                result.onSuccess {
                    finished = true
                    main.removeCallbacks(tick)
                    codeInput?.setText("")
                    statusView?.text = "配对完成，运行环境已就绪"
                    pairButton?.apply {
                        isEnabled = true
                        alpha = 1f
                        text = "返回 Touch Compositor"
                        setOnClickListener {
                            val launch = Intent(this@PairingSetupService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(launch)
                            stopSelf()
                        }
                    }
                }.onFailure { error ->
                    statusView?.text = friendlyError(error)
                    pairButton?.isEnabled = true
                    pairButton?.alpha = 1f
                    pairButton?.text = "重新配对"
                }
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("pair", true) || raw.contains("配对") -> "配对失败。请确认 6 位码仍有效，并保持系统配对窗口打开。"
            raw.contains("timed", true) || raw.contains("超时") -> "连接超时。请确认无线调试仍然开启。"
            else -> "未能完成设置：${raw.ifBlank { error.javaClass.simpleName }.take(130)}"
        }
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
