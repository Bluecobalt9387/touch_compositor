package dev.touchcompositor.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity(), RuntimeClient.Listener {
    companion object {
        private const val CALCULATOR_PACKAGE = "com.android.bbkcalculator"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val client = RuntimeClient()
    private lateinit var runtimeDot: View
    private lateinit var runtimeText: TextView
    private lateinit var runtimeDetail: TextView
    private lateinit var overlayDot: View
    private lateinit var overlayText: TextView
    private lateinit var launchButton: Button

    private val bg = Color.rgb(246, 247, 249)
    private val card = Color.WHITE
    private val ink = Color.rgb(25, 28, 33)
    private val muted = Color.rgb(109, 115, 124)
    private val accent = Color.rgb(48, 99, 211)
    private val green = Color.rgb(43, 153, 101)
    private val red = Color.rgb(203, 70, 67)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client.listener = this
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildUi())
        connectRuntime()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermission()
        if (!client.isConnected) connectRuntime() else refreshRuntime()
    }

    override fun onDestroy() {
        client.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(36))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Touch Compositor"
            textSize = 28f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "连接输入引擎并启动目标应用"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(22))
        })

        root.addView(statusCard())
        root.addView(gap())
        root.addView(targetCard())
        root.addView(gap())
        root.addView(TextView(this).apply {
            text = "启动目标应用后，录制、录制重放和宏编辑都在悬浮工作台中完成。主界面不参与操作过程。"
            textSize = 12.5f
            setTextColor(muted)
            setLineSpacing(0f, 1.18f)
            setPadding(dp(4), dp(2), dp(4), 0)
        })
        return scroll
    }

    private fun statusCard(): View = cardLayout().apply {
        addView(cardTitle("启动检查"))
        addView(statusRow("Runtime", true).also { row ->
            runtimeDot = row.getChildAt(0)
            runtimeText = row.getChildAt(1) as TextView
            runtimeDetail = TextView(this@MainActivity).apply {
                text = "127.0.0.1:28183"
                textSize = 11.5f
                setTextColor(muted)
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            row.addView(runtimeDetail, LinearLayout.LayoutParams(0, dp(40), 1f))
            row.addView(textButton("重连") { connectRuntime() })
        })
        addView(divider())
        addView(statusRow("悬浮窗", false).also { row ->
            overlayDot = row.getChildAt(0)
            overlayText = row.getChildAt(1) as TextView
            row.addView(textButton("授权") { requestOverlayPermission() })
        })
    }

    private fun targetCard(): View = cardLayout().apply {
        addView(cardTitle("开发测试目标"))
        val box = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(14))
        }
        box.addView(TextView(this@MainActivity).apply {
            text = "计算器"
            textSize = 17f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(TextView(this@MainActivity).apply {
            text = CALCULATOR_PACKAGE
            textSize = 11f
            setTextColor(muted)
        })
        addView(box)
        launchButton = Button(this@MainActivity).apply {
            text = "启动计算器"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = rounded(accent, 13f)
            setOnClickListener { launchCalculator() }
        }
        addView(launchButton, LinearLayout.LayoutParams(-1, dp(50)))
        addView(TextView(this@MainActivity).apply {
            text = "启动后右侧会出现可拖动的 TC 悬浮球。"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        })
    }

    private fun statusRow(label: String, runtime: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dot = View(this@MainActivity).apply { background = circle(Color.GRAY) }
        addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
        addView(TextView(this@MainActivity).apply {
            text = if (runtime) "Runtime 检查中" else label
            textSize = 14f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(if (runtime) dp(170) else 0, dp(44), if (runtime) 0f else 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
    }

    private fun connectRuntime() {
        setRuntime(false, "Runtime 检查中", "127.0.0.1:28183")
        io.execute {
            runCatching { client.connect() }
                .onSuccess { refreshRuntime() }
                .onFailure { t -> runOnUiThread { setRuntime(false, "Runtime 未连接", t.message ?: "连接失败") } }
        }
    }

    private fun refreshRuntime() {
        io.execute {
            runCatching { client.request("STATUS") }
                .onSuccess { status -> runOnUiThread { setRuntime(true, "Runtime 已连接", status) } }
                .onFailure { t -> runOnUiThread { setRuntime(false, "Runtime 未连接", t.message ?: "连接失败") } }
        }
    }

    private fun refreshOverlayPermission() {
        val allowed = Settings.canDrawOverlays(this)
        overlayDot.background = circle(if (allowed) green else red)
        overlayText.text = if (allowed) "悬浮窗已授权" else "需要悬浮窗权限"
        overlayText.setTextColor(if (allowed) green else red)
        if (::launchButton.isInitialized) launchButton.alpha = if (allowed) 1f else 0.72f
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun launchCalculator() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        if (!client.isConnected) {
            connectRuntime()
            return
        }
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        val launch = packageManager.getLaunchIntentForPackage(CALCULATOR_PACKAGE)
        if (launch == null) {
            setRuntime(false, "找不到计算器", CALCULATOR_PACKAGE)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    override fun onConnected(ready: String) = runOnUiThread {
        setRuntime(true, "Runtime 已连接", ready.removePrefix("READY touch-runtime-v1 "))
    }

    override fun onDisconnected(message: String) = runOnUiThread {
        setRuntime(false, "Runtime 已断开", message)
    }

    override fun onEvent(line: String) = Unit

    private fun setRuntime(online: Boolean, text: String, detail: String) {
        if (!::runtimeDot.isInitialized) return
        runtimeDot.background = circle(if (online) green else red)
        runtimeText.text = text
        runtimeText.setTextColor(if (online) green else red)
        runtimeDetail.text = detail
    }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(16), dp(17), dp(17))
        background = rounded(card, 17f, Color.rgb(227, 229, 233))
        elevation = dp(1).toFloat()
    }

    private fun cardTitle(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 16f
        setTextColor(ink)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun textButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(accent)
        setPadding(dp(10), 0, dp(6), 0)
        setOnClickListener { click() }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.rgb(235, 237, 240))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(19) }
    }

    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(14)) }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun circle(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
