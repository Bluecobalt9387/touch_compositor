package dev.touchcompositor.app

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayService : Service(), RuntimeClient.Listener, MacroRunner.Listener {
    companion object {
        const val ACTION_SHOW = "dev.touchcompositor.app.SHOW_OVERLAY"
        const val ACTION_HIDE = "dev.touchcompositor.app.HIDE_OVERLAY"
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val client = RuntimeClient()
    private lateinit var macroStore: MacroStore
    private lateinit var macroRunner: MacroRunner
    private lateinit var wm: WindowManager

    private var macros = mutableListOf<TouchMacro>()
    private var selectedMacroId: String? = null
    private var selectedTab = 0 // 0 recording, 1 macros
    private var runtimeStatus = "Runtime 未连接"
    private var recording = false
    private var activeRecordingName: String? = null

    private var ballView: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var recordingStopView: View? = null
    private var captureView: View? = null

    private val bg = Color.rgb(245, 246, 248)
    private val card = Color.WHITE
    private val ink = Color.rgb(28, 31, 36)
    private val muted = Color.rgb(111, 117, 126)
    private val accent = Color.rgb(52, 103, 215)
    private val red = Color.rgb(204, 66, 63)
    private val green = Color.rgb(44, 151, 99)

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        macroStore = MacroStore(this)
        macros = macroStore.load()
        macroRunner = MacroRunner(client).also { it.listener = this }
        client.listener = this
        connectRuntime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> removeAllWindows()
            else -> if (Settings.canDrawOverlays(this)) {
                syncDisplayConfig()
                showBall()
            } else stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        macroRunner.stop()
        removeAllWindows()
        client.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun connectRuntime() {
        io.execute {
            runCatching { client.connect() }
                .onSuccess { requestStatus() }
                .onFailure { t -> main.post { runtimeStatus = "Runtime 未连接 · ${t.message ?: "连接失败"}"; refreshPanelIfShown() } }
        }
    }

    override fun onConnected(ready: String) {
        main.post {
            runtimeStatus = ready.removePrefix("READY touch-runtime-v1 ")
            syncDisplayConfig()
            refreshPanelIfShown()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post {
            syncDisplayConfig()
            when {
                recordingStopView != null -> repositionRecordingStop()
                panelView != null -> showPanel()
                ballView != null -> { hideBall(); showBall() }
            }
        }
    }

    private fun syncDisplayConfig() {
        if (!client.isConnected) return
        val metrics = resources.displayMetrics
        val rotation = when (wm.defaultDisplay.rotation) {
            0 -> 0
            1 -> 90
            2 -> 180
            3 -> 270
            else -> 0
        }
        requestAsync("DISPLAY ${metrics.widthPixels} ${metrics.heightPixels} $rotation") { response ->
            runtimeStatus = response
            refreshPanelIfShown()
        }
    }

    private fun repositionRecordingStop() {
        if (!recording) return
        val name = activeRecordingName ?: return
        val w = dp(126)
        val h = dp(48)
        val right = dp(16)
        val top = dp(78)
        removeRecordingStop()
        showRecordingStop(name, w, h, right, top)
        val screenW = resources.displayMetrics.widthPixels
        val leftPx = screenW - right - w
        val rightPx = screenW - right
        requestAsync("RECORD_EXCLUDE $leftPx $top $rightPx ${top + h}") {}
    }

    override fun onDisconnected(message: String) {
        main.post {
            runtimeStatus = "Runtime 已断开"
            refreshPanelIfShown()
        }
    }

    override fun onEvent(line: String) {
        main.post {
            if (line.startsWith("PLAYBACK_DONE") || line.startsWith("PLAYBACK_ERROR")) {
                refreshPanelIfShown()
            }
        }
    }

    override fun onMacroStarted(name: String) {
        main.post {
            ballView?.text = "■"
            refreshPanelIfShown()
        }
    }

    override fun onMacroFinished(name: String, stopped: Boolean, error: String?) {
        main.post {
            ballView?.text = "TC"
            refreshPanelIfShown()
        }
    }

    private fun showBall() {
        if (!Settings.canDrawOverlays(this) || ballView != null || panelView != null || recordingStopView != null || captureView != null) return
        val size = dp(52)
        val view = TextView(this).apply {
            text = if (macroRunner.isRunning) "■" else "TC"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.argb(235, 34, 38, 45), 26f)
            elevation = dp(8).toFloat()
        }
        val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
            x = resources.displayMetrics.widthPixels - size - dp(16)
            y = dp(180)
        }
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    params.x = (startX + dx.toInt()).coerceIn(0, resources.displayMetrics.widthPixels - size)
                    params.y = (startY + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - size)
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showPanel()
                    true
                }
                else -> true
            }
        }
        wm.addView(view, params)
        ballView = view
        ballParams = params
    }

    private fun hideBall() {
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
        ballParams = null
    }

    private fun showPanel() {
        if (!Settings.canDrawOverlays(this)) return
        hideBall()
        removePanel()
        val panel = buildPanel()
        val width = minOf(dp(350), resources.displayMetrics.widthPixels - dp(24))
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            x = dp(12)
            y = dp(72)
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        wm.addView(panel, params)
        panelView = panel
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    private fun buildPanel(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = rounded(Color.argb(250, 250, 251, 252), 18f, Color.rgb(218, 221, 227))
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Touch Compositor"
            textSize = 16f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(smallTextButton(if (macroRunner.isRunning) "停止宏" else "收起") {
            if (macroRunner.isRunning) {
                io.execute { macroRunner.stop() }
            } else {
                removePanel(); showBall()
            }
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = runtimeStatus
            textSize = 11f
            setTextColor(if (client.isConnected) green else red)
            setPadding(0, dp(4), 0, dp(10))
        })

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tabButton("录制", selectedTab == 0) { selectedTab = 0; showPanel() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        tabs.addView(tabButton("宏", selectedTab == 1) { selectedTab = 1; showPanel() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(tabs)

        val content = if (selectedTab == 0) buildRecordingPanel() else buildMacroPanel()
        root.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return root
    }

    private fun buildRecordingPanel(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = "录制人手动作"
            textSize = 14f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = "开始后工作台自动收起，只保留结束按钮。"
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(10))
        })
        box.addView(primaryButton("开始录制") { startOverlayRecording() }, LinearLayout.LayoutParams(-1, dp(43)))

        val title = TextView(this).apply {
            text = "录制列表"
            textSize = 13f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(6))
        }
        box.addView(title)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        requestAsync("LIST") { response ->
            val names = response.removePrefix("RECORDINGS").trim().let { if (it.isBlank()) emptyList() else it.split(',') }
            list.removeAllViews()
            if (names.isEmpty()) {
                list.addView(note("暂无录制"))
            } else {
                names.take(8).forEach { name -> list.addView(recordingRow(name)) }
            }
        }
        return box
    }

    private fun recordingRow(name: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 11f, Color.rgb(226, 229, 234))
            setPadding(dp(10), dp(6), dp(6), dp(6))
        }
        row.addView(TextView(this).apply {
            text = name
            textSize = 12f
            setTextColor(ink)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(smallTextButton("播放") {
            requestAsync("PLAY $name 1.0 1") {
                removePanel(); showBall()
            }
        })
        row.addView(smallTextButton("删") {
            requestAsync("DELETE $name") { refreshPanelIfShown() }
        })
        return row.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(49)).apply { bottomMargin = dp(6) } }
    }

    private fun startOverlayRecording() {
        if (!client.isConnected) { connectRuntime(); return }
        val name = "rec_" + SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val w = dp(126)
        val h = dp(48)
        val right = dp(16)
        val top = dp(78)
        val screenW = resources.displayMetrics.widthPixels
        val leftPx = screenW - right - w
        val rightPx = screenW - right
        requestAsync("RECORD_EXCLUDE $leftPx $top $rightPx ${top + h}") {
            requestAsync("RECORD_START $name") {
                recording = true
                activeRecordingName = name
                removePanel()
                showRecordingStop(name, w, h, right, top)
            }
        }
    }

    private fun showRecordingStop(name: String, width: Int, height: Int, right: Int, top: Int) {
        removeRecordingStop()
        val view = TextView(this).apply {
            text = "■ 结束录制"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.argb(245, 195, 57, 55), 20f)
            elevation = dp(8).toFloat()
            setOnClickListener { stopOverlayRecording(name) }
        }
        val params = overlayParams(width, height, Gravity.TOP or Gravity.END).apply { x = right; y = top }
        wm.addView(view, params)
        recordingStopView = view
    }

    private fun stopOverlayRecording(name: String) {
        requestAsync("RECORD_STOP") {
            recording = false
            activeRecordingName = null
            requestAsync("RECORD_EXCLUDE_CLEAR") {}
            removeRecordingStop()
            selectedTab = 0
            showPanel()
        }
    }

    private fun removeRecordingStop() {
        recordingStopView?.let { runCatching { wm.removeView(it) } }
        recordingStopView = null
    }

    private fun buildMacroPanel(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val macroHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        macroHead.addView(TextView(this).apply {
            text = "宏"
            textSize = 14f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        macroHead.addView(smallTextButton("+ 新建") { createMacro() })
        outer.addView(macroHead)

        if (macros.isEmpty()) {
            outer.addView(note("还没有宏。新建后可添加点击、滑动和等待。"))
            return outer
        }
        if (selectedMacroId == null || macros.none { it.id == selectedMacroId }) selectedMacroId = macros.first().id

        val selector = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(7), 0, dp(8)) }
        macros.take(6).forEach { macro ->
            selector.addView(TextView(this).apply {
                text = macro.name + if (macro.id == selectedMacroId) "  ✓" else ""
                textSize = 12f
                setTextColor(if (macro.id == selectedMacroId) accent else ink)
                typeface = if (macro.id == selectedMacroId) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (macro.id == selectedMacroId) Color.rgb(237, 243, 255) else Color.WHITE, 9f, Color.rgb(228, 231, 236))
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { selectedMacroId = macro.id; refreshPanelIfShown() }
            }, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(4) })
        }
        outer.addView(selector)

        val macro = macros.first { it.id == selectedMacroId }
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actionRow.addView(primaryButton(if (macroRunner.isRunning) "停止" else "运行") {
            if (macroRunner.isRunning) macroRunner.stop() else runSelectedMacro(macro)
        }, LinearLayout.LayoutParams(0, dp(41), 1f).apply { rightMargin = dp(6) })
        actionRow.addView(secondaryButton("删除宏") { deleteMacro(macro) }, LinearLayout.LayoutParams(0, dp(41), 1f))
        outer.addView(actionRow)

        outer.addView(TextView(this).apply {
            text = "操作"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(12), 0, dp(5))
        })

        val stepsScroll = ScrollView(this).apply { isFillViewport = false }
        val stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stepsScroll.addView(stepsBox, ViewGroup.LayoutParams(-1, -2))
        macro.steps.forEachIndexed { index, step -> stepsBox.addView(stepRow(macro, index, step)) }
        if (macro.steps.isEmpty()) stepsBox.addView(note("暂无操作"))
        outer.addView(stepsScroll, LinearLayout.LayoutParams(-1, minOf(dp(265), dp(58) * maxOf(1, macro.steps.size))))

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        addRow.addView(compactButton("+ 点击") { captureTap(macro, null) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) })
        addRow.addView(compactButton("+ 滑动") { captureSwipe(macro, null) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) })
        addRow.addView(compactButton("+ 等待") {
            macro.steps += MacroStep.Wait(500)
            saveMacrosAndRefresh()
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        outer.addView(addRow)
        return outer
    }

    private fun stepRow(macro: TouchMacro, index: Int, step: MacroStep): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 10f, Color.rgb(226, 229, 234))
            setPadding(dp(9), dp(6), dp(7), dp(6))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "${index + 1}. ${stepSummary(step)}"
            textSize = 11.5f
            setTextColor(ink)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tinyButton("↑") {
            if (index > 0) { val v = macro.steps.removeAt(index); macro.steps.add(index - 1, v); saveMacrosAndRefresh() }
        })
        top.addView(tinyButton("↓") {
            if (index < macro.steps.lastIndex) { val v = macro.steps.removeAt(index); macro.steps.add(index + 1, v); saveMacrosAndRefresh() }
        })
        top.addView(tinyButton("×") { macro.steps.removeAt(index); saveMacrosAndRefresh() })
        box.addView(top)

        val edit = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
        when (step) {
            is MacroStep.Tap -> {
                edit.addView(smallTextButton("重选位置") { captureTap(macro, index) })
                edit.addView(smallTextButton("−20ms") { step.holdMs = (step.holdMs - 20).coerceAtLeast(20); saveMacrosAndRefresh() })
                edit.addView(smallTextButton("+20ms") { step.holdMs = (step.holdMs + 20).coerceAtMost(3000); saveMacrosAndRefresh() })
            }
            is MacroStep.Swipe -> {
                edit.addView(smallTextButton("重选轨迹") { captureSwipe(macro, index) })
                edit.addView(smallTextButton("−100") { step.durationMs = (step.durationMs - 100).coerceAtLeast(50); saveMacrosAndRefresh() })
                edit.addView(smallTextButton("+100") { step.durationMs = (step.durationMs + 100).coerceAtMost(5000); saveMacrosAndRefresh() })
            }
            is MacroStep.Wait -> {
                edit.addView(smallTextButton("−100ms") { step.durationMs = (step.durationMs - 100).coerceAtLeast(0); saveMacrosAndRefresh() })
                edit.addView(smallTextButton("+100ms") { step.durationMs = (step.durationMs + 100).coerceAtMost(10000); saveMacrosAndRefresh() })
            }
        }
        box.addView(edit)
        return box.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) } }
    }

    private fun stepSummary(step: MacroStep): String = when (step) {
        is MacroStep.Tap -> "点击 (${step.x.toInt()}, ${step.y.toInt()}) · ${step.holdMs}ms"
        is MacroStep.Swipe -> "滑动 (${step.x1.toInt()},${step.y1.toInt()}) → (${step.x2.toInt()},${step.y2.toInt()}) · ${step.durationMs}ms"
        is MacroStep.Wait -> "等待 ${step.durationMs}ms"
    }

    private fun createMacro() {
        val n = (macros.size + 1)
        val macro = TouchMacro(name = "宏 $n")
        macros += macro
        selectedMacroId = macro.id
        macroStore.save(macros)
        refreshPanelIfShown()
    }

    private fun deleteMacro(macro: TouchMacro) {
        macros.removeAll { it.id == macro.id }
        selectedMacroId = macros.firstOrNull()?.id
        macroStore.save(macros)
        refreshPanelIfShown()
    }

    private fun runSelectedMacro(macro: TouchMacro) {
        if (macro.steps.isEmpty()) return
        removePanel()
        showBall()
        runCatching { macroRunner.start(macro) }
    }

    private fun saveMacrosAndRefresh() {
        macroStore.save(macros)
        refreshPanelIfShown()
    }

    private fun captureTap(macro: TouchMacro, replaceIndex: Int?) {
        beginCapture("点击目标位置") { down, up, _ ->
            val step = MacroStep.Tap(up.first, up.second, (replaceIndex?.let { (macro.steps[it] as? MacroStep.Tap)?.holdMs } ?: 70L))
            if (replaceIndex == null) macro.steps += step else macro.steps[replaceIndex] = step
            macroStore.save(macros)
        }
    }

    private fun captureSwipe(macro: TouchMacro, replaceIndex: Int?) {
        beginCapture("在屏幕上滑出轨迹") { down, up, duration ->
            val oldDuration = replaceIndex?.let { (macro.steps[it] as? MacroStep.Swipe)?.durationMs }
            val step = MacroStep.Swipe(down.first, down.second, up.first, up.second, oldDuration ?: duration.coerceIn(80, 5000))
            if (replaceIndex == null) macro.steps += step else macro.steps[replaceIndex] = step
            macroStore.save(macros)
        }
    }

    private fun beginCapture(
        title: String,
        onCaptured: (Pair<Float, Float>, Pair<Float, Float>, Long) -> Unit,
    ) {
        removePanel()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(18, 0, 0, 0)) }
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            background = rounded(Color.argb(240, 30, 33, 39), 18f)
        }
        banner.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        banner.addView(TextView(this).apply {
            text = "取消"
            textSize = 12f
            setTextColor(Color.rgb(205, 219, 255))
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { finishCapture(false, null) }
        }, LinearLayout.LayoutParams(dp(64), -1))
        root.addView(banner, FrameLayout.LayoutParams(-1, dp(46), Gravity.TOP).apply {
            leftMargin = dp(24); rightMargin = dp(24); topMargin = dp(70)
        })

        var down: Pair<Float, Float>? = null
        var downAt = 0L
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = e.rawX to e.rawY
                    downAt = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val d = down ?: (e.rawX to e.rawY)
                    val u = e.rawX to e.rawY
                    val duration = (SystemClock.uptimeMillis() - downAt).coerceAtLeast(1)
                    finishCapture(true) { onCaptured(d, u, duration) }
                    true
                }
                else -> true
            }
        }
        val params = overlayParams(-1, -1, Gravity.TOP or Gravity.START).apply {
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        wm.addView(root, params)
        captureView = root
    }

    private fun finishCapture(success: Boolean, after: (() -> Unit)?) {
        captureView?.let { runCatching { wm.removeView(it) } }
        captureView = null
        if (success) after?.invoke()
        selectedTab = 1
        showPanel()
    }

    private fun finishCapture(success: Boolean, ignored: Nothing?) = finishCapture(success, null as (() -> Unit)?)

    private fun requestStatus() = requestAsync("STATUS") { runtimeStatus = it; refreshPanelIfShown() }

    private fun requestAsync(command: String, onSuccess: (String) -> Unit) {
        io.execute {
            runCatching {
                if (!client.isConnected) client.connect()
                client.request(command)
            }.onSuccess { result -> main.post { onSuccess(result) } }
                .onFailure { t -> main.post { runtimeStatus = "错误 · ${t.message ?: t.javaClass.simpleName}"; refreshPanelIfShown() } }
        }
    }

    private fun refreshPanelIfShown() {
        if (panelView != null) showPanel()
    }

    private fun removeAllWindows() {
        removePanel()
        hideBall()
        removeRecordingStop()
        captureView?.let { runCatching { wm.removeView(it) } }
        captureView = null
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun primaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(accent, 11f)
        setOnClickListener { click() }
    }

    private fun secondaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(ink)
        background = rounded(Color.WHITE, 11f, Color.rgb(218, 221, 227))
        setOnClickListener { click() }
    }

    private fun compactButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 11f
        setTextColor(accent)
        background = rounded(Color.WHITE, 9f, Color.rgb(218, 224, 237))
        setOnClickListener { click() }
    }

    private fun tabButton(label: String, active: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12.5f
        typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (active) Color.WHITE else muted)
        background = rounded(if (active) accent else Color.rgb(232, 234, 238), 10f)
        setOnClickListener { click() }
    }

    private fun smallTextButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 11f
        setTextColor(accent)
        setPadding(dp(7), 0, dp(7), 0)
        setOnClickListener { click() }
    }

    private fun tinyButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(muted)
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { click() }
    }

    private fun note(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 11.5f
        setTextColor(muted)
        setPadding(dp(4), dp(9), dp(4), dp(9))
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
