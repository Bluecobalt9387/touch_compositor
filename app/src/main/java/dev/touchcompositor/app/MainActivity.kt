package dev.touchcompositor.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.touchcompositor.adb.TouchAdbController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val workInFlight = AtomicBoolean(false)
    private lateinit var environment: RuntimeEnvironment

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var setupCard: View
    private lateinit var setupTitle: TextView
    private lateinit var setupDescription: TextView
    private lateinit var discoveryText: TextView
    private lateinit var pairAddressInput: EditText
    private lateinit var pairingCodeInput: EditText
    private lateinit var connectAddressInput: EditText
    private lateinit var pairButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var readyCard: View
    private lateinit var overlayDot: View
    private lateinit var overlayText: TextView
    private lateinit var startButton: Button

    private var runtimeReady = false
    private var destroyed = false
    private var pendingGuidedPairing = false

    private val bg = Color.rgb(246, 247, 249)
    private val card = Color.WHITE
    private val ink = Color.rgb(25, 28, 33)
    private val muted = Color.rgb(107, 113, 122)
    private val accent = Color.rgb(48, 99, 211)
    private val green = Color.rgb(42, 150, 98)
    private val red = Color.rgb(199, 66, 63)
    private val amber = Color.rgb(185, 126, 42)

    private val discoveryTick = object : Runnable {
        override fun run() {
            if (destroyed) return
            refreshDiscoveryUi()
            main.postDelayed(this, 500)
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
        showNeedSetup(if (environment.hasPairedIdentity) "正在恢复运行环境" else "需要首次配对")
        main.post(discoveryTick)
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayPermission()
        if (pendingGuidedPairing && Settings.canDrawOverlays(this) && !environment.hasPairedIdentity) {
            pendingGuidedPairing = false
            startGuidedPairing()
        } else if (!runtimeReady && environment.hasPairedIdentity) {
            ensureEnvironmentAsync()
        }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(discoveryTick)
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
            text = "一次配对，之后直接使用"
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
            text = "Touch Compositor 只在本机通过 Android 无线调试建立 shell 运行环境。配对身份保存在应用私有目录中。"
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
        setupTitle = cardTitle("首次设置")
        addView(setupTitle)
        setupDescription = bodyText("开启 Android 的“无线调试”，选择“使用配对码配对设备”。应用会自动寻找端口，你通常只需要输入 6 位配对码。")
        addView(setupDescription, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        addView(primaryButton("开始无线调试配对") { startGuidedPairing() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(13) })

        addView(TextView(this@MainActivity).apply {
            text = "如果悬浮配对面板无法自动发现端口，也可以在下面手动填写。"
            textSize = 11f
            setTextColor(muted)
            setPadding(dp(2), dp(10), dp(2), 0)
        })

        discoveryText = TextView(this@MainActivity).apply {
            text = "正在寻找无线调试端口…"
            textSize = 11.5f
            setTextColor(muted)
            setPadding(dp(2), dp(10), dp(2), dp(6))
        }
        addView(discoveryText)

        pairAddressInput = input("配对地址，例如 192.168.1.8:37123").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        addView(pairAddressInput, LinearLayout.LayoutParams(-1, dp(48)))

        pairingCodeInput = input("6 位配对码").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        addView(pairingCodeInput, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })

        pairButton = primaryButton("配对并启动") { pairAndStart() }
        addView(pairButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })

        addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(14) })
        addView(TextView(this@MainActivity).apply {
            text = "已配对但自动连接失败时"
            textSize = 12f
            setTextColor(muted)
        })
        connectAddressInput = input("连接地址（通常会自动发现）").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        addView(connectAddressInput, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        reconnectButton = secondaryButton("重新连接") { reconnectManual() }
        addView(reconnectButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
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
        actions.addView(textButton("忘记配对") { confirmForgetPairing() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        addView(actions)
    }

    private fun refreshDiscoveryUi() {
        if (!::discoveryText.isInitialized) return
        val snapshot = environment.discoverySnapshot()
        val pair = snapshot.pairing
        val connect = snapshot.connect
        if (!pairAddressInput.hasFocus() && pairAddressInput.text.isBlank() && pair != null) pairAddressInput.setText(pair.toString())
        if (!connectAddressInput.hasFocus() && connectAddressInput.text.isBlank()) {
            (connect ?: environment.savedConnectEndpoint())?.let { connectAddressInput.setText(it.toString()) }
        }
        discoveryText.text = buildString {
            append(if (pair != null) "已发现配对端口" else "等待配对端口")
            append(" · ")
            append(if (connect != null) "已发现连接端口" else "等待连接端口")
        }
    }

    private fun ensureEnvironmentAsync(manual: TouchAdbController.Endpoint? = null) {
        if (!workInFlight.compareAndSet(false, true)) return
        showBusy("正在启动运行环境", "正在连接本机无线调试…")
        io.execute {
            val result = environment.ensureReady(manual)
            workInFlight.set(false)
            runOnUiThread {
                result.onSuccess { showReady() }
                    .onFailure { showNeedSetup(it.message ?: "无法连接无线调试") }
            }
        }
    }

    private fun pairAndStart() {
        if (!workInFlight.compareAndSet(false, true)) return
        val endpoint = TouchAdbController.parseEndpoint(pairAddressInput.text.toString())
            ?: environment.discoverySnapshot().pairing
        if (endpoint == null) {
            workInFlight.set(false)
            showNeedSetup("没有找到配对端口。请保持“使用配对码配对设备”页面打开，或手动填写 IP:端口。")
            return
        }
        val code = pairingCodeInput.text.toString().trim()
        if (!code.matches(Regex("\\d{6}"))) {
            workInFlight.set(false)
            showNeedSetup("请输入系统显示的 6 位配对码")
            return
        }
        showBusy("正在配对", "配对完成后会自动启动运行环境…")
        io.execute {
            val result = environment.pairAndStart(endpoint, code)
            workInFlight.set(false)
            runOnUiThread {
                result.onSuccess {
                    pairingCodeInput.setText("")
                    showReady()
                }.onFailure { showNeedSetup(it.message ?: "配对失败") }
            }
        }
    }

    private fun reconnectManual() {
        val text = connectAddressInput.text.toString().trim()
        val endpoint = if (text.isBlank()) null else TouchAdbController.parseEndpoint(text)
        if (text.isNotBlank() && endpoint == null) {
            showNeedSetup("连接地址格式应为 IP:端口")
            return
        }
        ensureEnvironmentAsync(endpoint)
    }

    private fun restartEnvironment() {
        if (!workInFlight.compareAndSet(false, true)) return
        runtimeReady = false
        showBusy("正在重启运行环境", "")
        io.execute {
            val result = environment.restart()
            workInFlight.set(false)
            runOnUiThread {
                result.onSuccess { showReady() }
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
        statusDot.background = circle(if (environment.hasPairedIdentity) amber else red)
        statusTitle.text = if (environment.hasPairedIdentity) "需要重新连接" else "需要首次配对"
        statusTitle.setTextColor(if (environment.hasPairedIdentity) amber else red)
        statusDetail.text = detail
        setupTitle.text = if (environment.hasPairedIdentity) "恢复无线调试连接" else "首次设置"
        setupDescription.text = if (environment.hasPairedIdentity) {
            "配对身份已经保存。请确认“无线调试”已开启，然后重新连接；通常不需要再次输入配对码。"
        } else {
            "开启 Android 的“无线调试”，选择“使用配对码配对设备”。应用会自动寻找端口，你通常只需要输入 6 位配对码。"
        }
        pairButton.visibility = if (environment.hasPairedIdentity) View.GONE else View.VISIBLE
        pairingCodeInput.visibility = if (environment.hasPairedIdentity) View.GONE else View.VISIBLE
        pairAddressInput.visibility = if (environment.hasPairedIdentity) View.GONE else View.VISIBLE
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


    private fun startGuidedPairing() {
        if (!Settings.canDrawOverlays(this)) {
            pendingGuidedPairing = true
            requestOverlayPermission()
            return
        }
        startService(Intent(this, PairingSetupService::class.java).setAction(PairingSetupService.ACTION_SHOW))
        openWirelessDebuggingSettings()
    }
    private fun openWirelessDebuggingSettings() {
        val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        val fallback = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        runCatching { startActivity(direct) }.onFailure { startActivity(fallback) }
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun confirmForgetPairing() {
        AlertDialog.Builder(this)
            .setTitle("忘记配对")
            .setMessage("这会删除 Touch Compositor 保存的 ADB 身份。下次使用需要重新输入配对码。")
            .setNegativeButton("取消", null)
            .setPositiveButton("忘记") { _, _ ->
                environment.forgetPairing()
                runtimeReady = false
                pairAddressInput.setText("")
                connectAddressInput.setText("")
                showNeedSetup("配对身份已删除")
            }
            .show()
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

    private fun input(hintValue: String) = EditText(this).apply {
        hint = hintValue
        textSize = 14f
        setTextColor(ink)
        setHintTextColor(Color.rgb(153, 158, 166))
        setSingleLine(true)
        setPadding(dp(12), 0, dp(12), 0)
        background = rounded(Color.rgb(249, 250, 252), 11f, Color.rgb(215, 219, 225))
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
