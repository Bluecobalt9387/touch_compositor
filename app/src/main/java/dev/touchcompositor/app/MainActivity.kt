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
import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val workInFlight = AtomicBoolean(false)
    private lateinit var environment: RuntimeEnvironment

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var setupCard: View
    private lateinit var setupTitle: TextView
    private lateinit var setupDescription: TextView
    private lateinit var readyCard: View
    private lateinit var overlayDot: View
    private lateinit var overlayText: TextView
    private lateinit var startButton: Button

    private var runtimeReady = false
    private var destroyed = false

    private val bg = Color.rgb(246, 247, 249)
    private val card = Color.WHITE
    private val ink = Color.rgb(25, 28, 33)
    private val muted = Color.rgb(107, 113, 122)
    private val accent = Color.rgb(48, 99, 211)
    private val green = Color.rgb(42, 150, 98)
    private val red = Color.rgb(199, 66, 63)
    private val amber = Color.rgb(185, 126, 42)

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        if (!destroyed) runOnUiThread { refreshShizuku() }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        if (!destroyed) runOnUiThread { showNeedSetup("Shizuku 已停止，请重新启动") }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 100 && !destroyed) runOnUiThread {
            if (result == PackageManager.PERMISSION_GRANTED) ensureEnvironmentAsync()
            else showNeedSetup("未获得 Shizuku 授权，可在 Shizuku 的应用管理中修改")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        environment = RuntimeEnvironment(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR }
        setContentView(buildUi())
        refreshOverlayPermission()
        showNeedSetup("请先启动 Shizuku，再授权此应用")
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermission()
        refreshShizuku()
    }

    private fun refreshShizuku() {
        if (RuntimeEnvironment.authorized) ensureEnvironmentAsync()
        else showNeedSetup(if (RuntimeEnvironment.available) "请授权此应用使用 Shizuku" else "请先在 Shizuku 中启动服务")
    }

    override fun onDestroy() {
        destroyed = true
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        environment.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(40))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Touch Compositor"
            textSize = 28f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "通过 Shizuku 启动触控合成"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(22))
        })

        root.addView(statusCard())
        root.addView(gap())
        setupCard = setupCardView()
        root.addView(setupCard)
        root.addView(gap())
        readyCard = readyCardView()
        root.addView(readyCard)

        root.addView(TextView(this).apply {
            text = "无线调试的配对与启动由 Shizuku 管理。本应用仅请求 Shizuku 授权，以运行触控合成服务。"
            textSize = 11.5f
            setTextColor(muted)
            setLineSpacing(0f, 1.18f)
            setPadding(dp(3), dp(10), dp(3), 0)
        })
        return scroll
    }

    private fun statusCard(): View = cardLayout().apply {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this@MainActivity).apply { background = circle(amber) }
        row.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(11) })
        val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = TextView(this@MainActivity).apply {
            text = "正在检查"
            textSize = 16f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusDetail = TextView(this@MainActivity).apply {
            text = ""
            textSize = 11.5f
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
            maxLines = 4
        }
        texts.addView(statusTitle)
        texts.addView(statusDetail)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(row)
    }

    private fun setupCardView(): View = cardLayout().apply {
        setupTitle = cardTitle("Shizuku 设置")
        addView(setupTitle)
        setupDescription = bodyText("先在 Shizuku 中启动服务，再返回这里授权并启动。")
        addView(setupDescription)
        addView(primaryButton("授权并启动") {
            when {
                !RuntimeEnvironment.available -> showNeedSetup("请先打开 Shizuku 并启动服务")
                RuntimeEnvironment.authorized -> ensureEnvironmentAsync()
                else -> runCatching { Shizuku.requestPermission(100) }
                    .onFailure { showNeedSetup(it.message ?: "请求授权失败") }
            }
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        addView(secondaryButton("打开 Shizuku") { openShizuku() }, LinearLayout.LayoutParams(-1, dp(46)))
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (intent != null) startActivity(intent)
        else showNeedSetup("未找到 Shizuku，请先安装并启动")
    }

    private fun readyCardView(): View = cardLayout().apply {
        visibility = View.GONE
        addView(cardTitle("可以使用"))
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }
        overlayDot = View(this@MainActivity).apply { background = circle(Color.GRAY) }
        row.addView(overlayDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
        overlayText = TextView(this@MainActivity).apply {
            text = "检查悬浮窗权限"
            textSize = 13f
            setTextColor(ink)
        }
        row.addView(overlayText, LinearLayout.LayoutParams(0, dp(40), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        row.addView(textButton("设置") { requestOverlayPermission() })
        addView(row)

        startButton = primaryButton("启动悬浮工具") { launchOverlay() }
        addView(startButton, LinearLayout.LayoutParams(-1, dp(52)))

        val actions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }
        actions.addView(textButton("重启运行环境") { restartEnvironment() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        actions.addView(textButton("管理 Shizuku 授权") { openShizuku() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        addView(actions)
    }

    private fun ensureEnvironmentAsync() {
        if (!workInFlight.compareAndSet(false, true)) return
        showBusy("正在启动运行环境", "正在通过 Shizuku 启动触控服务…")
        io.execute {
            val result = environment.ensureReady()
            workInFlight.set(false)
            runOnUiThread {
                if (!destroyed) result.onSuccess { showReady() }
                    .onFailure { showNeedSetup(it.message ?: "无法启动触控服务") }
            }
        }
    }

    private fun restartEnvironment() {
        if (!workInFlight.compareAndSet(false, true)) return
        runtimeReady = false
        showBusy("正在重启运行环境", "")
        io.execute {
            val result = environment.restart()
            workInFlight.set(false)
            runOnUiThread {
                if (!destroyed) result.onSuccess { showReady() }
                    .onFailure { showNeedSetup(it.message ?: "重启失败") }
            }
        }
    }

    private fun launchOverlay() {
        if (!runtimeReady) {
            ensureEnvironmentAsync()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        moveTaskToBack(true)
    }

    private fun showReady() {
        runtimeReady = true
        statusDot.background = circle(green)
        statusTitle.text = "运行环境已就绪"
        statusTitle.setTextColor(green)
        statusDetail.text = "可以直接启动悬浮工具"
        setupCard.visibility = View.GONE
        readyCard.visibility = View.VISIBLE
        refreshOverlayPermission()
    }

    private fun showNeedSetup(detail: String) {
        runtimeReady = false
        statusDot.background = circle(amber)
        statusTitle.text = "需要 Shizuku 服务与授权"
        statusTitle.setTextColor(amber)
        statusDetail.text = detail
        setupCard.visibility = View.VISIBLE
        readyCard.visibility = View.GONE
    }

    private fun showBusy(title: String, detail: String) {
        runtimeReady = false
        statusDot.background = circle(amber)
        statusTitle.text = title
        statusTitle.setTextColor(amber)
        statusDetail.text = detail
        setupCard.visibility = View.GONE
        readyCard.visibility = View.GONE
    }

    private fun refreshOverlayPermission() {
        if (!::overlayDot.isInitialized) return
        val allowed = Settings.canDrawOverlays(this)
        overlayDot.background = circle(if (allowed) green else red)
        overlayText.text = if (allowed) "悬浮窗已授权" else "需要悬浮窗权限"
        overlayText.setTextColor(if (allowed) green else red)
        startButton.alpha = if (allowed) 1f else 0.75f
    }


    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(16), dp(17), dp(17))
        background = rounded(card, 17f, Color.rgb(226, 229, 234))
        elevation = dp(1).toFloat()
    }

    private fun cardTitle(value: String) = TextView(this).apply {
        text = value; textSize = 16f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
    }

    private fun bodyText(value: String) = TextView(this).apply {
        text = value; textSize = 12.5f; setTextColor(muted); setLineSpacing(0f, 1.18f)
    }

    private fun primaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
        background = rounded(accent, 12f); setOnClickListener { click() }
    }

    private fun secondaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(ink)
        background = rounded(Color.rgb(249, 250, 252), 12f, Color.rgb(215, 219, 225)); setOnClickListener { click() }
    }

    private fun textButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(accent)
        setPadding(dp(8), 0, dp(8), 0); setOnClickListener { click() }
    }

    private fun divider() = View(this).apply { setBackgroundColor(Color.rgb(234, 236, 240)) }
    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(14)) }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun circle(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
