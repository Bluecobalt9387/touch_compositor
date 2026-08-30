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
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
        private const val HOLD_TRIGGER_DELAY_MS = 260L
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val client = RuntimeClient()
    private lateinit var macroStore: MacroStore
    private lateinit var macroRunner: MacroRunner
    private lateinit var wm: WindowManager

    private var macros = mutableListOf<TouchMacro>()
    private var selectedMacroId: String? = null
    private var selectedTab = 1 // 0 recordings, 1 macro editor
    private var pickerMacroId: String? = null
    private var pickerReplaceIndex: Int? = null
    private var runtimeStatus = "Runtime 未连接"
    private var recording = false
    private var activeRecordingName: String? = null

    private var ballView: TextView? = null
    private var panelView: View? = null
    private var recordingStopView: View? = null
    private var positionEditorView: View? = null
    private val triggerViews = linkedMapOf<String, View>()

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
        macroRunner = MacroRunner {
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }.also { it.listener = this }
        client.listener = this
        connectRuntime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> removeAllWindows()
            else -> if (Settings.canDrawOverlays(this)) {
                syncDisplayConfig()
                showBall()
                syncTriggerButtons()
            } else stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        macroRunner.close()
        removeAllWindows()
        client.close()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post {
            syncDisplayConfig()
            if (positionEditorView != null) {
                removePositionEditor()
                selectedTab = 1
                showPanel()
            } else {
                when {
                    recordingStopView != null -> repositionRecordingStop()
                    panelView != null -> showPanel()
                    ballView != null -> { hideBall(); showBall() }
                }
            }
            syncTriggerButtons()
        }
    }

    private fun connectRuntime() {
        io.execute {
            runCatching { client.connect() }
                .onSuccess { requestStatus() }
                .onFailure { t -> main.post {
                    runtimeStatus = "Runtime 未连接 · ${t.message ?: "连接失败"}"
                    refreshPanelIfShown()
                } }
        }
    }

    override fun onConnected(ready: String) {
        main.post {
            runtimeStatus = ready.removePrefix("READY touch-runtime-v1 ")
            syncDisplayConfig()
            refreshPanelIfShown()
        }
    }

    override fun onDisconnected(message: String) {
        main.post {
            runtimeStatus = "Runtime 已断开"
            refreshPanelIfShown()
        }
    }

    override fun onEvent(line: String) {
        main.post {
            if (line.startsWith("PLAYBACK_DONE") || line.startsWith("PLAYBACK_ERROR")) refreshPanelIfShown()
        }
    }

    override fun onMacroStarted(name: String) = Unit

    override fun onMacroFinished(name: String, stopped: Boolean, error: String?) {
        main.post {
            if (error != null) runtimeStatus = "宏错误 · $error"
            refreshPanelIfShown()
        }
    }

    private fun syncDisplayConfig() {
        if (!client.isConnected) return
        val metrics = resources.displayMetrics
        val rotation = when (wm.defaultDisplay.rotation) {
            0 -> 0; 1 -> 90; 2 -> 180; 3 -> 270; else -> 0
        }
        requestAsync("DISPLAY ${metrics.widthPixels} ${metrics.heightPixels} $rotation") { response ->
            runtimeStatus = response
            refreshPanelIfShown()
        }
    }

    // -------------------------------------------------------------------------
    // Floating editor entry and macro triggers
    // -------------------------------------------------------------------------

    private fun showBall() {
        if (!Settings.canDrawOverlays(this) || ballView != null || panelView != null || recording || positionEditorView != null) return
        val size = dp(50)
        val view = TextView(this).apply {
            text = "TC"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.argb(238, 32, 36, 43), 25f)
            elevation = dp(8).toFloat()
        }
        val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
            x = resources.displayMetrics.widthPixels - size - dp(14)
            y = dp(140)
        }
        installDraggableClick(view, params, onClick = { showPanel() })
        wm.addView(view, params)
        ballView = view
    }

    private fun hideBall() {
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
    }

    private fun syncTriggerButtons() {
        hideTriggerButtons()
        if (!Settings.canDrawOverlays(this) || recording || positionEditorView != null) return
        macros.forEachIndexed { index, macro -> showMacroTrigger(macro, index) }
    }

    private fun hideTriggerButtons() {
        triggerViews.values.forEach { runCatching { wm.removeView(it) } }
        triggerViews.clear()
    }

    private fun showMacroTrigger(macro: TouchMacro, index: Int) {
        val size = dp(46)
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val view = TextView(this).apply {
            text = (index + 1).toString() + if (macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) "↻" else ""
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(Color.argb(236, 52, 103, 215), 23f)
            elevation = dp(7).toFloat()
        }
        val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
            x = (macro.trigger.x * w - size / 2f).toInt().coerceIn(0, (w - size).coerceAtLeast(0))
            y = (macro.trigger.y * h - size / 2f).toInt().coerceIn(0, (h - size).coerceAtLeast(0))
        }
        installTriggerTouch(view, params, macro, size)
        wm.addView(view, params)
        triggerViews[macro.id] = view
    }

    private fun installTriggerTouch(view: View, params: WindowManager.LayoutParams, macro: TouchMacro, size: Int) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var holdStarted = false
        val holdRunnable = Runnable {
            if (!moved && macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT && !macroRunner.isRunning) {
                holdStarted = runCatching { macroRunner.start(macro, repeat = true); true }.getOrDefault(false)
            }
        }
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false; holdStarted = false
                    if (macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) main.postDelayed(holdRunnable, HOLD_TRIGGER_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        moved = true
                        main.removeCallbacks(holdRunnable)
                    }
                    if (moved && !holdStarted) {
                        params.x = (startX + dx.toInt()).coerceIn(0, resources.displayMetrics.widthPixels - size)
                        params.y = (startY + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - size)
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdRunnable)
                    if (moved && !holdStarted) {
                        val w = resources.displayMetrics.widthPixels.toFloat()
                        val h = resources.displayMetrics.heightPixels.toFloat()
                        macro.trigger.x = ((params.x + size / 2f) / w).coerceIn(0f, 1f)
                        macro.trigger.y = ((params.y + size / 2f) / h).coerceIn(0f, 1f)
                        macroStore.save(macros)
                    } else if (macro.trigger.mode == MacroTriggerMode.CLICK_ONCE && e.actionMasked == MotionEvent.ACTION_UP) {
                        if (!macroRunner.isRunning) runCatching { macroRunner.start(macro, repeat = false) }
                    } else if (holdStarted) {
                        macroRunner.stop()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun installDraggableClick(view: View, params: WindowManager.LayoutParams, onClick: () -> Unit) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    params.x = (startX + dx.toInt()).coerceIn(0, resources.displayMetrics.widthPixels - view.width.coerceAtLeast(1))
                    params.y = (startY + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - view.height.coerceAtLeast(1))
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onClick(); true }
                else -> true
            }
        }
    }

    // -------------------------------------------------------------------------
    // Editor panel
    // -------------------------------------------------------------------------

    private fun showPanel() {
        if (!Settings.canDrawOverlays(this) || recording || positionEditorView != null) return
        hideBall()
        removePanel()
        val panel = buildPanel()
        val width = minOf(dp(360), resources.displayMetrics.widthPixels - dp(20))
        val maxHeight = (resources.displayMetrics.heightPixels - dp(30)).coerceAtLeast(dp(180))
        val scroll = MaxHeightScrollView(this).apply {
            maxHeightPx = maxHeight
            isFillViewport = false
            addView(panel, ViewGroup.LayoutParams(-1, -2))
        }
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            x = dp(10); y = dp(10)
        }
        wm.addView(scroll, params)
        panelView = scroll
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    private fun buildPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(15))
        background = rounded(Color.argb(252, 249, 250, 252), 17f, Color.rgb(215, 219, 225))
        elevation = dp(12).toFloat()

        val header = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this@OverlayService).apply {
            text = "Touch Compositor"
            textSize = 16f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (macroRunner.isRunning) header.addView(smallTextButton("停止") { macroRunner.stop() })
        header.addView(smallTextButton("收起") { removePanel(); showBall() })
        addView(header)
        addView(TextView(this@OverlayService).apply {
            text = runtimeStatus; textSize = 10.5f
            setTextColor(if (client.isConnected) green else red)
            setPadding(0, dp(3), 0, dp(9))
        })

        val tabs = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tabButton("录制素材", selectedTab == 0) { selectedTab = 0; pickerMacroId = null; showPanel() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        tabs.addView(tabButton("宏编辑", selectedTab == 1) { selectedTab = 1; showPanel() }, LinearLayout.LayoutParams(0, dp(38), 1f))
        addView(tabs)
        addView(if (selectedTab == 0) buildRecordingPanel() else buildMacroPanel(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun buildRecordingPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionTitle("录制素材"))
        addView(note("录制是一种可复用动作，可在任意宏中插入并单独设置播放速度。"))
        addView(primaryButton("开始录制") { startOverlayRecording() }, LinearLayout.LayoutParams(-1, dp(43)))
        val list = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        addView(list)
        requestAsync("LIST") { response ->
            val names = parseRecordingNames(response)
            list.removeAllViews()
            if (names.isEmpty()) list.addView(note("暂无录制素材"))
            else names.take(12).forEach { list.addView(recordingRow(it)) }
        }
    }

    private fun recordingRow(name: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = rounded(Color.WHITE, 10f, Color.rgb(226, 229, 234))
        setPadding(dp(10), dp(5), dp(5), dp(5))
        addView(TextView(this@OverlayService).apply {
            text = name; textSize = 11.5f; setTextColor(ink); maxLines = 1
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(smallTextButton("播放") { requestAsync("PLAY $name 1.0 1") { removePanel(); showBall() } })
        if (macros.any { m -> m.actions.any { it is MacroAction.Recording && it.recordingName == name } }) {
            addView(TextView(this@OverlayService).apply { text = "已引用"; textSize = 10f; setTextColor(muted); setPadding(dp(6),0,dp(6),0) })
        } else {
            addView(smallTextButton("删") { requestAsync("DELETE $name") { refreshPanelIfShown() } })
        }
    }.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) } }

    private fun buildMacroPanel(): View {
        val pickerId = pickerMacroId
        if (pickerId != null) {
            val macro = macros.firstOrNull { it.id == pickerId }
            if (macro != null) return buildRecordingPicker(macro, pickerReplaceIndex)
            pickerMacroId = null
        }

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(sectionTitle("宏"), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(smallTextButton("+ 新建") { createMacro() })
        outer.addView(head)
        if (macros.isEmpty()) {
            outer.addView(note("新建一个宏后，会在屏幕上生成对应的悬浮触发按钮。"))
            return outer
        }
        if (selectedMacroId == null || macros.none { it.id == selectedMacroId }) selectedMacroId = macros.first().id

        val selectorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(9)) }
        macros.forEachIndexed { index, macro ->
            selectorRow.addView(TextView(this).apply {
                text = "${index + 1} ${macro.name}"
                textSize = 11f; gravity = Gravity.CENTER
                setTextColor(if (macro.id == selectedMacroId) Color.WHITE else ink)
                typeface = if (macro.id == selectedMacroId) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = rounded(if (macro.id == selectedMacroId) accent else Color.WHITE, 9f, Color.rgb(221, 225, 231))
                setOnClickListener { selectedMacroId = macro.id; showPanel() }
            }, LinearLayout.LayoutParams(dp(92), dp(36)).apply { if (index > 0) leftMargin = dp(4) })
        }
        outer.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(selectorRow, ViewGroup.LayoutParams(-2, -2))
        })

        val macro = macros.first { it.id == selectedMacroId }
        outer.addView(triggerEditor(macro))
        outer.addView(actionEditor(macro), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        return outer
    }

    private fun triggerEditor(macro: TouchMacro): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.rgb(242, 244, 248), 11f)
        setPadding(dp(10), dp(9), dp(10), dp(10))
        val row = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this@OverlayService).apply {
            text = "触发器"; textSize = 12.5f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(smallTextButton("运行") { if (!macroRunner.isRunning) runCatching { macroRunner.start(macro, false) } })
        row.addView(smallTextButton("删除宏") { deleteMacro(macro) })
        addView(row)
        addView(note("屏幕上的编号按钮就是触发器，可直接拖动位置。"))
        val modes = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL }
        modes.addView(modeButton("点击 · 单次", macro.trigger.mode == MacroTriggerMode.CLICK_ONCE) {
            macro.trigger.mode = MacroTriggerMode.CLICK_ONCE; saveMacrosAndRefresh(syncTriggers = true)
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) })
        modes.addView(modeButton("长按 · 循环", macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) {
            macro.trigger.mode = MacroTriggerMode.HOLD_REPEAT; saveMacrosAndRefresh(syncTriggers = true)
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
        addView(modes)
    }

    private fun actionEditor(macro: TouchMacro): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@OverlayService).apply {
            text = "动作 · 顺序执行"; textSize = 12.5f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        if (macro.actions.isEmpty()) addView(note("暂无动作。下方添加点击、拖动、等待或录制素材。"))
        macro.actions.forEachIndexed { index, action -> addView(actionRow(macro, index, action)) }

        val row1 = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, 0) }
        row1.addView(compactButton("+ 点击") { showTapPositionEditor(macro, null) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) })
        row1.addView(compactButton("+ 拖动") { showDragPositionEditor(macro, null) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        addView(row1)
        val row2 = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, 0) }
        row2.addView(compactButton("+ 等待") {
            macro.actions += MacroAction.Wait(500); saveMacrosAndRefresh()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) })
        row2.addView(compactButton("+ 播放录制") { openRecordingPicker(macro, null) }, LinearLayout.LayoutParams(0, dp(40), 1f))
        addView(row2)
    }

    private fun actionRow(macro: TouchMacro, index: Int, action: MacroAction): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 10f, Color.rgb(225, 228, 234))
        setPadding(dp(9), dp(7), dp(6), dp(7))
        val top = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this@OverlayService).apply {
            text = "${index + 1}. ${actionSummary(action)}"; textSize = 11.3f; setTextColor(ink)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(tinyButton("↑") { moveAction(macro, index, -1) })
        top.addView(tinyButton("↓") { moveAction(macro, index, +1) })
        top.addView(tinyButton("×") { macro.actions.removeAt(index); saveMacrosAndRefresh() })
        addView(top)

        val controls = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
        when (action) {
            is MacroAction.Tap -> {
                controls.addView(smallTextButton("位置") { showTapPositionEditor(macro, index) })
                controls.addView(smallTextButton("−20ms") { action.holdMs = (action.holdMs - 20).coerceAtLeast(20); saveMacrosAndRefresh() })
                controls.addView(smallTextButton("+20ms") { action.holdMs = (action.holdMs + 20).coerceAtMost(5_000); saveMacrosAndRefresh() })
            }
            is MacroAction.Drag -> {
                controls.addView(smallTextButton("轨迹") { showDragPositionEditor(macro, index) })
                controls.addView(smallTextButton("−100ms") { action.durationMs = (action.durationMs - 100).coerceAtLeast(50); saveMacrosAndRefresh() })
                controls.addView(smallTextButton("+100ms") { action.durationMs = (action.durationMs + 100).coerceAtMost(30_000); saveMacrosAndRefresh() })
            }
            is MacroAction.Wait -> {
                controls.addView(smallTextButton("−100ms") { action.durationMs = (action.durationMs - 100).coerceAtLeast(0); saveMacrosAndRefresh() })
                controls.addView(smallTextButton("+100ms") { action.durationMs = (action.durationMs + 100).coerceAtMost(60_000); saveMacrosAndRefresh() })
                controls.addView(smallTextButton("+1s") { action.durationMs = (action.durationMs + 1_000).coerceAtMost(60_000); saveMacrosAndRefresh() })
            }
            is MacroAction.Recording -> {
                controls.addView(smallTextButton("更换") { openRecordingPicker(macro, index) })
                controls.addView(smallTextButton("−速") { action.speed = (action.speed - 0.25f).coerceAtLeast(0.25f); saveMacrosAndRefresh() })
                controls.addView(smallTextButton("+速") { action.speed = (action.speed + 0.25f).coerceAtMost(4f); saveMacrosAndRefresh() })
            }
        }
        addView(controls)
    }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) } }

    private fun actionSummary(action: MacroAction): String = when (action) {
        is MacroAction.Tap -> "点击 · 按压 ${action.holdMs}ms"
        is MacroAction.Drag -> "拖动 · ${action.durationMs}ms"
        is MacroAction.Wait -> "等待 · ${action.durationMs}ms"
        is MacroAction.Recording -> "录制 ${action.recordingName} · ${formatSpeed(action.speed)}×"
    }

    private fun buildRecordingPicker(macro: TouchMacro, replaceIndex: Int?): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(sectionTitle(if (replaceIndex == null) "添加录制动作" else "更换录制素材"), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(smallTextButton("返回") { pickerMacroId = null; pickerReplaceIndex = null; showPanel() })
        addView(head)
        val list = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        addView(list)
        requestAsync("LIST") { response ->
            val names = parseRecordingNames(response)
            list.removeAllViews()
            if (names.isEmpty()) list.addView(note("暂无录制素材，请先到“录制素材”页录制。"))
            names.forEach { name ->
                list.addView(TextView(this@OverlayService).apply {
                    text = name; textSize = 12f; gravity = Gravity.CENTER_VERTICAL
                    setTextColor(ink); background = rounded(Color.WHITE, 9f, Color.rgb(224, 228, 234))
                    setPadding(dp(10), 0, dp(10), 0)
                    setOnClickListener {
                        val oldSpeed = replaceIndex?.let { (macro.actions.getOrNull(it) as? MacroAction.Recording)?.speed } ?: 1f
                        val value = MacroAction.Recording(name, oldSpeed)
                        if (replaceIndex == null) macro.actions += value else macro.actions[replaceIndex] = value
                        pickerMacroId = null; pickerReplaceIndex = null
                        saveMacrosAndRefresh()
                    }
                }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(5) })
            }
        }
    }

    // -------------------------------------------------------------------------
    // Visual position editor
    // -------------------------------------------------------------------------

    private fun showTapPositionEditor(macro: TouchMacro, replaceIndex: Int?) {
        val old = replaceIndex?.let { macro.actions.getOrNull(it) as? MacroAction.Tap }
        val initialX = old?.x ?: 0.5f
        val initialY = old?.y ?: 0.55f
        val hold = old?.holdMs ?: 70L
        showPositionEditor(
            title = "点击位置",
            start = initialX to initialY,
            end = null,
        ) { a, _ ->
            val value = MacroAction.Tap(a.first, a.second, hold)
            if (replaceIndex == null) macro.actions += value else macro.actions[replaceIndex] = value
            macroStore.save(macros)
        }
    }

    private fun showDragPositionEditor(macro: TouchMacro, replaceIndex: Int?) {
        val old = replaceIndex?.let { macro.actions.getOrNull(it) as? MacroAction.Drag }
        val start = (old?.x1 ?: 0.35f) to (old?.y1 ?: 0.55f)
        val end = (old?.x2 ?: 0.65f) to (old?.y2 ?: 0.55f)
        val duration = old?.durationMs ?: 450L
        showPositionEditor("拖动轨迹", start, end) { a, b ->
            val e = b ?: return@showPositionEditor
            val value = MacroAction.Drag(a.first, a.second, e.first, e.second, duration)
            if (replaceIndex == null) macro.actions += value else macro.actions[replaceIndex] = value
            macroStore.save(macros)
        }
    }

    private fun showPositionEditor(
        title: String,
        start: Pair<Float, Float>,
        end: Pair<Float, Float>?,
        onConfirm: (Pair<Float, Float>, Pair<Float, Float>?) -> Unit,
    ) {
        removePanel(); hideBall(); hideTriggerButtons(); removePositionEditor()
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        var sx = start.first.coerceIn(0f, 1f); var sy = start.second.coerceIn(0f, 1f)
        var ex = end?.first?.coerceIn(0f, 1f) ?: sx; var ey = end?.second?.coerceIn(0f, 1f) ?: sy

        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(18, 0, 0, 0)) }
        val guide = MacroGeometryGuideView(this).apply {
            arrowEnabled = end != null
        }
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))

        val handleSize = dp(48)
        fun makeHandle(label: String): TextView = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); background = rounded(Color.argb(245, 52, 103, 215), 24f)
            elevation = dp(8).toFloat()
        }
        val startHandle = makeHandle(if (end == null) "点" else "起")
        val endHandle = if (end != null) makeHandle("终") else null
        root.addView(startHandle, FrameLayout.LayoutParams(handleSize, handleSize))
        if (endHandle != null) root.addView(endHandle, FrameLayout.LayoutParams(handleSize, handleSize))

        fun update() {
            val spx = sx * w; val spy = sy * h; val epx = ex * w; val epy = ey * h
            guide.x1 = spx; guide.y1 = spy; guide.x2 = epx; guide.y2 = epy
            (startHandle.layoutParams as FrameLayout.LayoutParams).also {
                it.leftMargin = (spx - handleSize / 2f).toInt().coerceIn(0, w - handleSize)
                it.topMargin = (spy - handleSize / 2f).toInt().coerceIn(0, h - handleSize)
                startHandle.layoutParams = it
            }
            if (endHandle != null) (endHandle.layoutParams as FrameLayout.LayoutParams).also {
                it.leftMargin = (epx - handleSize / 2f).toInt().coerceIn(0, w - handleSize)
                it.topMargin = (epy - handleSize / 2f).toInt().coerceIn(0, h - handleSize)
                endHandle.layoutParams = it
            }
        }
        fun drag(handle: View, isStart: Boolean) {
            handle.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        val nx = (e.rawX / w).coerceIn(0f, 1f)
                        val ny = (e.rawY / h).coerceIn(0f, 1f)
                        if (isStart) { sx = nx; sy = ny } else { ex = nx; ey = ny }
                        update(); true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        drag(startHandle, true); if (endHandle != null) drag(endHandle, false)

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(6), 0)
            background = rounded(Color.argb(246, 30, 33, 39), 15f)
            elevation = dp(10).toFloat()
        }
        banner.addView(TextView(this).apply {
            text = if (end == null) "$title · 拖动指针" else "$title · 拖动起点和终点"
            textSize = 12f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        banner.addView(TextView(this).apply {
            text = "取消"; textSize = 11f; setTextColor(Color.rgb(205, 219, 255)); gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0); setOnClickListener { closePositionEditor(false, null) }
        }, LinearLayout.LayoutParams(-2, -1))
        banner.addView(TextView(this).apply {
            text = "确定"; textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0); setOnClickListener {
                val a = sx to sy; val b = if (end != null) ex to ey else null
                closePositionEditor(true) { onConfirm(a, b) }
            }
        }, LinearLayout.LayoutParams(-2, -1))
        root.addView(banner, FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP).apply {
            leftMargin = dp(18); rightMargin = dp(18); topMargin = dp(18)
        })

        val params = overlayParams(-1, -1, Gravity.TOP or Gravity.START).apply {
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        wm.addView(root, params)
        positionEditorView = root
        root.post { update() }
    }

    private fun closePositionEditor(save: Boolean, after: (() -> Unit)?) {
        removePositionEditor()
        if (save) after?.invoke()
        selectedTab = 1
        showPanel(); syncTriggerButtons()
    }

    private fun removePositionEditor() {
        positionEditorView?.let { runCatching { wm.removeView(it) } }
        positionEditorView = null
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    private fun startOverlayRecording() {
        if (!client.isConnected) { connectRuntime(); return }
        val name = "rec_" + SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val w = dp(126); val h = dp(48); val right = dp(16); val top = dp(62)
        val screenW = resources.displayMetrics.widthPixels
        val leftPx = screenW - right - w
        val rightPx = screenW - right
        requestAsync("RECORD_EXCLUDE $leftPx $top $rightPx ${top + h}") {
            requestAsync("RECORD_START $name") {
                recording = true; activeRecordingName = name
                removePanel(); hideBall(); hideTriggerButtons()
                showRecordingStop(name, w, h, right, top)
            }
        }
    }

    private fun showRecordingStop(name: String, width: Int, height: Int, right: Int, top: Int) {
        removeRecordingStop()
        val view = TextView(this).apply {
            text = "■ 结束录制"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD; background = rounded(Color.argb(246, 195, 57, 55), 20f); elevation = dp(8).toFloat()
            setOnClickListener { stopOverlayRecording(name) }
        }
        wm.addView(view, overlayParams(width, height, Gravity.TOP or Gravity.END).apply { x = right; y = top })
        recordingStopView = view
    }

    private fun stopOverlayRecording(name: String) {
        requestAsync("RECORD_STOP") {
            recording = false; activeRecordingName = null
            requestAsync("RECORD_EXCLUDE_CLEAR") {}
            removeRecordingStop(); selectedTab = 0
            showPanel(); syncTriggerButtons()
        }
    }

    private fun repositionRecordingStop() {
        if (!recording) return
        val name = activeRecordingName ?: return
        val w = dp(126); val h = dp(48); val right = dp(16); val top = dp(62)
        removeRecordingStop(); showRecordingStop(name, w, h, right, top)
        val screenW = resources.displayMetrics.widthPixels
        requestAsync("RECORD_EXCLUDE ${screenW - right - w} $top ${screenW - right} ${top + h}") {}
    }

    private fun removeRecordingStop() {
        recordingStopView?.let { runCatching { wm.removeView(it) } }
        recordingStopView = null
    }

    // -------------------------------------------------------------------------
    // Macro mutations / runtime helpers
    // -------------------------------------------------------------------------

    private fun createMacro() {
        val n = macros.size + 1
        val offset = (macros.size % 5) * 0.075f
        val macro = TouchMacro(
            name = "宏 $n",
            trigger = MacroTrigger(MacroTriggerMode.CLICK_ONCE, 0.88f, (0.36f + offset).coerceAtMost(0.82f)),
        )
        macros += macro; selectedMacroId = macro.id
        macroStore.save(macros); syncTriggerButtons(); showPanel()
    }

    private fun deleteMacro(macro: TouchMacro) {
        if (macroRunner.isRunning) macroRunner.stop()
        macros.removeAll { it.id == macro.id }
        selectedMacroId = macros.firstOrNull()?.id
        macroStore.save(macros); syncTriggerButtons(); showPanel()
    }

    private fun moveAction(macro: TouchMacro, index: Int, delta: Int) {
        val to = (index + delta).coerceIn(0, macro.actions.lastIndex)
        if (to == index) return
        val value = macro.actions.removeAt(index); macro.actions.add(to, value); saveMacrosAndRefresh()
    }

    private fun saveMacrosAndRefresh(syncTriggers: Boolean = false) {
        macroStore.save(macros)
        if (syncTriggers) syncTriggerButtons()
        refreshPanelIfShown()
    }

    private fun openRecordingPicker(macro: TouchMacro, replaceIndex: Int?) {
        pickerMacroId = macro.id; pickerReplaceIndex = replaceIndex; selectedTab = 1; showPanel()
    }

    private fun parseRecordingNames(response: String): List<String> =
        response.removePrefix("RECORDINGS").trim().let { if (it.isBlank()) emptyList() else it.split(',').filter(String::isNotBlank) }

    private fun requestStatus() = requestAsync("STATUS") { runtimeStatus = it; refreshPanelIfShown() }

    private fun requestAsync(command: String, onSuccess: (String) -> Unit) {
        io.execute {
            runCatching {
                if (!client.isConnected) client.connect()
                client.request(command)
            }.onSuccess { result -> main.post { onSuccess(result) } }
                .onFailure { t -> main.post {
                    runtimeStatus = "错误 · ${t.message ?: t.javaClass.simpleName}"
                    refreshPanelIfShown()
                } }
        }
    }

    private fun refreshPanelIfShown() { if (panelView != null) showPanel() }

    private fun removeAllWindows() {
        removePanel(); hideBall(); hideTriggerButtons(); removeRecordingStop(); removePositionEditor()
    }

    // -------------------------------------------------------------------------
    // Small UI helpers
    // -------------------------------------------------------------------------

    private fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun primaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
        background = rounded(accent, 11f); setOnClickListener { click() }
    }
    private fun compactButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 11f; setTextColor(accent)
        background = rounded(Color.WHITE, 9f, Color.rgb(218, 224, 237)); setOnClickListener { click() }
    }
    private fun tabButton(label: String, active: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 12f; typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (active) Color.WHITE else muted); background = rounded(if (active) accent else Color.rgb(232, 234, 238), 9f)
        setOnClickListener { click() }
    }
    private fun modeButton(label: String, active: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 11f; typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (active) Color.WHITE else ink); background = rounded(if (active) accent else Color.WHITE, 9f, Color.rgb(215, 220, 228))
        setOnClickListener { click() }
    }
    private fun smallTextButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 10.8f; setTextColor(accent); setPadding(dp(7), 0, dp(7), 0); setOnClickListener { click() }
    }
    private fun tinyButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 12f; setTextColor(muted); setPadding(dp(6), 0, dp(6), 0); setOnClickListener { click() }
    }
    private fun sectionTitle(textValue: String) = TextView(this).apply {
        text = textValue; textSize = 13f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
    }
    private fun note(textValue: String) = TextView(this).apply {
        text = textValue; textSize = 10.8f; setTextColor(muted); setPadding(dp(3), dp(6), dp(3), dp(8))
    }
    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else "%.2f".format(Locale.US, speed).trimEnd('0')
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
