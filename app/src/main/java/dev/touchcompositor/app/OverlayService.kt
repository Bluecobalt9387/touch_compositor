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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Minimal overlay shell:
 * - tiny top handle -> three translucent bubbles: record / macros / hide
 * - profiles select which trigger set is visible
 * - macros are edited by double tapping their own trigger
 */
class OverlayService : Service(), RuntimeClient.Listener, MacroRunner.Listener {
    companion object {
        const val ACTION_SHOW = "dev.touchcompositor.app.SHOW_OVERLAY"
        const val ACTION_HIDE = "dev.touchcompositor.app.HIDE_OVERLAY"
        private const val HOLD_TRIGGER_DELAY_MS = 360L
        private const val DOUBLE_TAP_MS = 240L
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val client = RuntimeClient()
    private lateinit var macroStore: MacroStore
    private lateinit var macroRunner: MacroRunner
    private lateinit var wm: WindowManager

    private var profiles = mutableListOf<MacroProfile>()
    private var activeProfileId: String? = null
    private var runtimeStatus = "Runtime 未连接"
    private var recording = false
    private var activeRecordingName: String? = null
    private var overlaysSuppressed = false

    private var entryView: View? = null
    private val menuViews = mutableListOf<View>()
    private var profileBarView: View? = null
    private var editorView: View? = null
    private var recordingPickerView: View? = null
    private var recordingStopView: View? = null
    private var positionEditorView: View? = null
    private val triggerViews = linkedMapOf<String, View>()

    private var editingMacroId: String? = null
    private var selectedActionIndex: Int? = null
    private var runningMacroId: String? = null

    private val ink = Color.rgb(28, 31, 36)
    private val muted = Color.rgb(110, 116, 126)
    private val accent = Color.rgb(52, 103, 215)
    private val red = Color.rgb(204, 66, 63)
    private val green = Color.rgb(44, 151, 99)

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        macroStore = MacroStore(this)
        profiles = macroStore.loadProfiles()
        ensureProfile()
        activeProfileId = macroStore.activeProfileId()?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id.also { macroStore.saveActiveProfileId(it) }
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
                overlaysSuppressed = false
                syncDisplayConfig()
                showEntry()
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
            hideMainMenu()
            hideProfileSelector()
            hideMacroEditor()
            hideRecordingPicker()
            if (positionEditorView != null) removePositionEditor()
            hideEntry()
            if (!recording) showEntry()
            if (recording) repositionRecordingStop()
            else syncTriggerButtons()
        }
    }

    // Runtime -----------------------------------------------------------------

    private fun connectRuntime() {
        io.execute {
            runCatching { client.connect() }
                .onSuccess { requestStatus() }
                .onFailure { t -> main.post { runtimeStatus = "Runtime 未连接 · ${t.message ?: "连接失败"}" } }
        }
    }

    override fun onConnected(ready: String) {
        main.post {
            runtimeStatus = ready.removePrefix("READY touch-runtime-v1 ")
            syncDisplayConfig()
        }
    }

    override fun onDisconnected(message: String) {
        main.post { runtimeStatus = "Runtime 已断开" }
    }

    override fun onEvent(line: String) = Unit

    override fun onMacroStarted(name: String) = Unit

    override fun onMacroFinished(name: String, stopped: Boolean, error: String?) {
        main.post {
            runningMacroId = null
            if (error != null) runtimeStatus = "宏错误 · $error"
            syncTriggerButtons()
        }
    }

    private fun syncDisplayConfig() {
        if (!client.isConnected) return
        val metrics = resources.displayMetrics
        val rotation = when (wm.defaultDisplay.rotation) {
            0 -> 0; 1 -> 90; 2 -> 180; 3 -> 270; else -> 0
        }
        requestAsync("DISPLAY ${metrics.widthPixels} ${metrics.heightPixels} $rotation") {
            runtimeStatus = it
        }
    }

    // Tiny top entry + three-bubble menu --------------------------------------

    private fun showEntry() {
        if (!Settings.canDrawOverlays(this) || entryView != null || recording) return
        val w = dp(42)
        val h = dp(14)
        val view = TextView(this).apply {
            text = "•••"
            gravity = Gravity.CENTER
            textSize = 8f
            setTextColor(Color.argb(220, 255, 255, 255))
            background = rounded(Color.argb(118, 26, 29, 34), 8f)
            setOnClickListener {
                if (overlaysSuppressed) {
                    overlaysSuppressed = false
                    syncTriggerButtons()
                    showMainMenu()
                } else if (menuViews.isEmpty()) showMainMenu() else hideMainMenu()
            }
        }
        val params = overlayParams(w, h, Gravity.TOP or Gravity.START).apply {
            x = ((resources.displayMetrics.widthPixels - w) / 2).coerceAtLeast(0)
            y = dp(2)
        }
        wm.addView(view, params)
        entryView = view
    }

    private fun hideEntry() {
        entryView?.let { runCatching { wm.removeView(it) } }
        entryView = null
    }

    private fun showMainMenu() {
        hideMainMenu()
        hideProfileSelector()
        hideMacroEditor()
        hideRecordingPicker()
        if (recording) return
        val size = dp(44)
        val gap = dp(10)
        val total = size * 3 + gap * 2
        val startX = ((resources.displayMetrics.widthPixels - total) / 2).coerceAtLeast(0)
        val y = dp(26)
        val specs = listOf(
            Triple("录", Color.argb(184, 190, 57, 55), { hideMainMenu(); startOverlayRecording() }),
            Triple("宏", Color.argb(184, 52, 103, 215), { hideMainMenu(); showProfileSelector() }),
            Triple("隐", Color.argb(170, 42, 46, 53), {
                hideMainMenu(); hideProfileSelector(); hideMacroEditor(); hideRecordingPicker()
                hideTriggerButtons(); overlaysSuppressed = true
            }),
        )
        specs.forEachIndexed { index, (label, color, action) ->
            val v = bubble(label, color, dp(22).toFloat()) { action() }
            val p = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
                x = startX + index * (size + gap); this.y = y
            }
            wm.addView(v, p)
            menuViews += v
        }
    }

    private fun hideMainMenu() {
        menuViews.forEach { runCatching { wm.removeView(it) } }
        menuViews.clear()
    }

    // Profiles ----------------------------------------------------------------

    private fun activeProfile(): MacroProfile {
        ensureProfile()
        return profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first().also {
            activeProfileId = it.id
            macroStore.saveActiveProfileId(it.id)
        }
    }

    private fun ensureProfile() {
        if (profiles.isEmpty()) profiles += MacroProfile(name = "配置 1")
    }

    private fun showProfileSelector() {
        hideProfileSelector()
        hideMacroEditor()
        hideRecordingPicker()
        if (recording || overlaysSuppressed) return

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        profiles.forEachIndexed { index, profile ->
            val active = profile.id == activeProfileId
            row.addView(pill(
                label = (index + 1).toString(),
                active = active,
                compact = true,
            ) {
                activeProfileId = profile.id
                macroStore.saveActiveProfileId(profile.id)
                editingMacroId = null
                selectedActionIndex = null
                syncTriggerButtons()
                showProfileSelector()
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(6) })
        }
        row.addView(pill("+", false, true) { createProfile() }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(6) })
        row.addView(pill("+宏", false, false) { createMacro() }, LinearLayout.LayoutParams(dp(54), dp(40)).apply { rightMargin = dp(6) })
        row.addView(pill("×", false, true) { hideProfileSelector() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        scroll.addView(row, ViewGroup.LayoutParams(-2, dp(40)))

        val width = minOf(resources.displayMetrics.widthPixels - dp(16), dp(390)).coerceAtLeast(dp(120))
        val p = overlayParams(width, dp(44), Gravity.TOP or Gravity.START).apply {
            x = ((resources.displayMetrics.widthPixels - width) / 2).coerceAtLeast(0)
            y = dp(76)
        }
        wm.addView(scroll, p)
        profileBarView = scroll
    }

    private fun hideProfileSelector() {
        profileBarView?.let { runCatching { wm.removeView(it) } }
        profileBarView = null
    }

    private fun createProfile() {
        val profile = MacroProfile(name = "配置 ${profiles.size + 1}")
        profiles += profile
        activeProfileId = profile.id
        saveProfiles()
        macroStore.saveActiveProfileId(profile.id)
        syncTriggerButtons()
        showProfileSelector()
    }

    private fun createMacro() {
        val profile = activeProfile()
        val n = profile.macros.size + 1
        val offset = (profile.macros.size % 6) * 0.065f
        val macro = TouchMacro(
            name = "宏 $n",
            trigger = MacroTrigger(
                mode = MacroTriggerMode.CLICK_ONCE,
                x = 0.88f,
                y = (0.34f + offset).coerceAtMost(0.80f),
                sizeDp = 46f,
            ),
        )
        profile.macros += macro
        saveProfiles()
        hideProfileSelector()
        syncTriggerButtons()
        showMacroEditor(macro)
    }

    // Trigger buttons ----------------------------------------------------------

    private fun syncTriggerButtons() {
        hideTriggerButtons()
        if (!Settings.canDrawOverlays(this) || recording || overlaysSuppressed || positionEditorView != null) return
        activeProfile().macros.forEachIndexed { index, macro -> showMacroTrigger(macro, index) }
    }

    private fun hideTriggerButtons() {
        triggerViews.values.forEach { runCatching { wm.removeView(it) } }
        triggerViews.clear()
    }

    private fun showMacroTrigger(macro: TouchMacro, index: Int) {
        val size = dp(macro.trigger.sizeDp).coerceAtLeast(dp(28))
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val editing = editingMacroId == macro.id
        val running = runningMacroId == macro.id
        val view = TextView(this).apply {
            text = (index + 1).toString() + if (macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) "↻" else ""
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = (macro.trigger.sizeDp * 0.27f).coerceIn(9f, 18f)
            typeface = Typeface.DEFAULT_BOLD
            background = triggerBackground(editing, running)
            elevation = dp(5).toFloat()
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
        var lastTapAt = 0L
        var pendingSingle: Runnable? = null

        val holdRunnable = Runnable {
            if (!moved && macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT && !macroRunner.isRunning) {
                holdStarted = startMacro(macro, repeat = true, source = view)
            }
        }

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false; holdStarted = false
                    if (macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) {
                        main.postDelayed(holdRunnable, HOLD_TRIGGER_DELAY_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        moved = true
                        main.removeCallbacks(holdRunnable)
                        pendingSingle?.let { main.removeCallbacks(it) }
                        pendingSingle = null
                        lastTapAt = 0L
                    }
                    if (moved && !holdStarted) {
                        params.x = (startX + dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdRunnable)
                    when {
                        moved && !holdStarted -> {
                            val sw = resources.displayMetrics.widthPixels.toFloat()
                            val sh = resources.displayMetrics.heightPixels.toFloat()
                            macro.trigger.x = ((params.x + size / 2f) / sw).coerceIn(0f, 1f)
                            macro.trigger.y = ((params.y + size / 2f) / sh).coerceIn(0f, 1f)
                            saveProfiles()
                        }
                        holdStarted -> macroRunner.stop()
                        e.actionMasked == MotionEvent.ACTION_UP -> {
                            val now = SystemClock.uptimeMillis()
                            if (lastTapAt != 0L && now - lastTapAt <= DOUBLE_TAP_MS) {
                                pendingSingle?.let { main.removeCallbacks(it) }
                                pendingSingle = null
                                lastTapAt = 0L
                                showMacroEditor(macro)
                            } else {
                                lastTapAt = now
                                if (macro.trigger.mode == MacroTriggerMode.CLICK_ONCE) {
                                    val stamp = now
                                    val r = Runnable {
                                        if (lastTapAt == stamp) {
                                            lastTapAt = 0L
                                            if (!macroRunner.isRunning) startMacro(macro, repeat = false, source = view)
                                        }
                                    }
                                    pendingSingle = r
                                    main.postDelayed(r, DOUBLE_TAP_MS)
                                }
                            }
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun startMacro(macro: TouchMacro, repeat: Boolean, source: View?): Boolean {
        return runCatching {
            macroRunner.start(macro, repeat)
            runningMacroId = macro.id
            source?.background = triggerBackground(editingMacroId == macro.id, running = true)
            true
        }.getOrElse {
            source?.background = rounded(Color.argb(205, 204, 66, 63), macro.trigger.sizeDp / 2f)
            source?.postDelayed({ source.background = triggerBackground(editingMacroId == macro.id, running = false) }, 550)
            runtimeStatus = "宏错误 · ${it.message ?: it.javaClass.simpleName}"
            false
        }
    }

    // Direct trigger-bound macro editor ---------------------------------------

    private fun showMacroEditor(macro: TouchMacro) {
        hideMainMenu()
        hideProfileSelector()
        hideRecordingPicker()
        hideMacroEditor()
        editingMacroId = macro.id
        if (selectedActionIndex != null && selectedActionIndex !in macro.actions.indices) selectedActionIndex = null
        syncTriggerButtons()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        root.addView(horizontalStrip(buildList {
            add(pill(macro.name, true, false) {})
            add(pill(if (macro.trigger.mode == MacroTriggerMode.CLICK_ONCE) "单次" else "长按", false, false) {
                macro.trigger.mode = if (macro.trigger.mode == MacroTriggerMode.CLICK_ONCE) MacroTriggerMode.HOLD_REPEAT else MacroTriggerMode.CLICK_ONCE
                saveProfiles(); syncTriggerButtons(); showMacroEditor(macro)
            })
            add(pill("−", false, true) { resizeTrigger(macro, -4f) })
            add(pill("+", false, true) { resizeTrigger(macro, 4f) })
            add(pill("删", false, true) { deleteMacro(macro) })
            add(pill("✓", false, true) { closeMacroEditor() })
        }))

        root.addView(horizontalStrip(buildList {
            macro.actions.forEachIndexed { index, action ->
                add(pill("${index + 1}${actionShort(action)}", selectedActionIndex == index, false) {
                    selectedActionIndex = index
                    showMacroEditor(macro)
                })
            }
            add(pill("+点", false, false) { showTapPositionEditor(macro, null) })
            add(pill("+拖", false, false) { showDragPositionEditor(macro, null) })
            add(pill("+等", false, false) {
                macro.actions += MacroAction.Wait(500)
                selectedActionIndex = macro.actions.lastIndex
                saveProfiles(); showMacroEditor(macro)
            })
            add(pill("+录", false, false) { showRecordingPicker(macro, null) })
        }, topMargin = dp(5)))

        selectedActionIndex?.let { index ->
            macro.actions.getOrNull(index)?.let { action ->
                root.addView(horizontalStrip(actionEditControls(macro, index, action)), LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
            }
        }

        val width = (resources.displayMetrics.widthPixels - dp(12)).coerceAtLeast(dp(180))
        val params = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            x = dp(6); y = dp(72)
        }
        wm.addView(root, params)
        editorView = root
    }

    private fun actionEditControls(macro: TouchMacro, index: Int, action: MacroAction): List<View> = buildList {
        when (action) {
            is MacroAction.Tap -> {
                add(pill("位置", false, false) { showTapPositionEditor(macro, index) })
                add(pill("−20", false, false) { action.holdMs = (action.holdMs - 20).coerceAtLeast(20); saveProfiles(); showMacroEditor(macro) })
                add(valuePill("${action.holdMs}ms"))
                add(pill("+20", false, false) { action.holdMs = (action.holdMs + 20).coerceAtMost(5000); saveProfiles(); showMacroEditor(macro) })
            }
            is MacroAction.Drag -> {
                add(pill("轨迹", false, false) { showDragPositionEditor(macro, index) })
                add(pill("−100", false, false) { action.durationMs = (action.durationMs - 100).coerceAtLeast(50); saveProfiles(); showMacroEditor(macro) })
                add(valuePill("${action.durationMs}ms"))
                add(pill("+100", false, false) { action.durationMs = (action.durationMs + 100).coerceAtMost(30000); saveProfiles(); showMacroEditor(macro) })
            }
            is MacroAction.Wait -> {
                add(pill("−100", false, false) { action.durationMs = (action.durationMs - 100).coerceAtLeast(0); saveProfiles(); showMacroEditor(macro) })
                add(valuePill("${action.durationMs}ms"))
                add(pill("+100", false, false) { action.durationMs = (action.durationMs + 100).coerceAtMost(60000); saveProfiles(); showMacroEditor(macro) })
            }
            is MacroAction.Recording -> {
                add(pill("换录", false, false) { showRecordingPicker(macro, index) })
                add(pill("速度", false, false) {
                    action.speed = when {
                        action.speed < 0.75f -> 1f
                        action.speed < 1.5f -> 2f
                        action.speed < 3f -> 4f
                        else -> 0.5f
                    }
                    saveProfiles(); showMacroEditor(macro)
                })
                add(valuePill("${trimSpeed(action.speed)}×"))
            }
        }
        add(pill("←", false, true) { moveAction(macro, index, -1) })
        add(pill("→", false, true) { moveAction(macro, index, 1) })
        add(pill("×", false, true) {
            macro.actions.removeAt(index)
            selectedActionIndex = null
            saveProfiles(); showMacroEditor(macro)
        })
    }

    private fun hideMacroEditor() {
        editorView?.let { runCatching { wm.removeView(it) } }
        editorView = null
    }

    private fun closeMacroEditor() {
        hideMacroEditor()
        hideRecordingPicker()
        editingMacroId = null
        selectedActionIndex = null
        syncTriggerButtons()
    }

    private fun resizeTrigger(macro: TouchMacro, delta: Float) {
        macro.trigger.sizeDp = (macro.trigger.sizeDp + delta).coerceIn(28f, 96f)
        saveProfiles()
        syncTriggerButtons()
        showMacroEditor(macro)
    }

    private fun deleteMacro(macro: TouchMacro) {
        if (runningMacroId == macro.id) macroRunner.stop()
        activeProfile().macros.removeAll { it.id == macro.id }
        saveProfiles()
        editingMacroId = null
        selectedActionIndex = null
        hideMacroEditor()
        hideRecordingPicker()
        syncTriggerButtons()
    }

    private fun moveAction(macro: TouchMacro, index: Int, delta: Int) {
        if (macro.actions.isEmpty()) return
        val to = (index + delta).coerceIn(0, macro.actions.lastIndex)
        if (to == index) return
        val value = macro.actions.removeAt(index)
        macro.actions.add(to, value)
        selectedActionIndex = to
        saveProfiles(); showMacroEditor(macro)
    }

    // Recording action picker --------------------------------------------------

    private fun showRecordingPicker(macro: TouchMacro, replaceIndex: Int?) {
        hideRecordingPicker()
        requestAsync("LIST") { response ->
            if (editingMacroId != macro.id) return@requestAsync
            val names = parseRecordingNames(response)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(3), 0, dp(3), 0)
            }
            if (names.isEmpty()) row.addView(valuePill("暂无录制"))
            names.take(18).forEach { name ->
                row.addView(pill(name, false, false) {
                    val old = replaceIndex?.let { macro.actions.getOrNull(it) as? MacroAction.Recording }
                    val value = MacroAction.Recording(name, old?.speed ?: 1f)
                    if (replaceIndex == null) {
                        macro.actions += value
                        selectedActionIndex = macro.actions.lastIndex
                    } else {
                        macro.actions[replaceIndex] = value
                        selectedActionIndex = replaceIndex
                    }
                    saveProfiles(); hideRecordingPicker(); showMacroEditor(macro)
                }, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(5) })
            }
            row.addView(pill("×", false, true) { hideRecordingPicker() }, LinearLayout.LayoutParams(dp(38), dp(38)))
            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
                addView(row, ViewGroup.LayoutParams(-2, dp(38)))
            }
            val width = (resources.displayMetrics.widthPixels - dp(12)).coerceAtLeast(dp(180))
            val p = overlayParams(width, dp(42), Gravity.TOP or Gravity.START).apply { x = dp(6); y = dp(204) }
            wm.addView(scroll, p)
            recordingPickerView = scroll
        }
    }

    private fun hideRecordingPicker() {
        recordingPickerView?.let { runCatching { wm.removeView(it) } }
        recordingPickerView = null
    }

    // Visual tap/drag position editor -----------------------------------------

    private fun showTapPositionEditor(macro: TouchMacro, replaceIndex: Int?) {
        val old = replaceIndex?.let { macro.actions.getOrNull(it) as? MacroAction.Tap }
        val start = (old?.x ?: 0.5f) to (old?.y ?: 0.5f)
        val hold = old?.holdMs ?: 70L
        showPositionEditor("点击", start, null) { a, _ ->
            val value = MacroAction.Tap(a.first, a.second, hold)
            if (replaceIndex == null) {
                macro.actions += value
                selectedActionIndex = macro.actions.lastIndex
            } else {
                macro.actions[replaceIndex] = value
                selectedActionIndex = replaceIndex
            }
            saveProfiles()
        }
    }

    private fun showDragPositionEditor(macro: TouchMacro, replaceIndex: Int?) {
        val old = replaceIndex?.let { macro.actions.getOrNull(it) as? MacroAction.Drag }
        val start = (old?.x1 ?: 0.35f) to (old?.y1 ?: 0.55f)
        val end = (old?.x2 ?: 0.65f) to (old?.y2 ?: 0.55f)
        val duration = old?.durationMs ?: 450L
        showPositionEditor("拖动", start, end) { a, b ->
            val e = b ?: return@showPositionEditor
            val value = MacroAction.Drag(a.first, a.second, e.first, e.second, duration)
            if (replaceIndex == null) {
                macro.actions += value
                selectedActionIndex = macro.actions.lastIndex
            } else {
                macro.actions[replaceIndex] = value
                selectedActionIndex = replaceIndex
            }
            saveProfiles()
        }
    }

    private fun showPositionEditor(
        title: String,
        start: Pair<Float, Float>,
        end: Pair<Float, Float>?,
        onConfirm: (Pair<Float, Float>, Pair<Float, Float>?) -> Unit,
    ) {
        hideMacroEditor(); hideRecordingPicker(); hideProfileSelector(); hideMainMenu(); hideTriggerButtons(); removePositionEditor()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        var globalStartX = start.first.coerceIn(0f, 1f) * screenW
        var globalStartY = start.second.coerceIn(0f, 1f) * screenH
        var globalEndX = (end?.first ?: start.first).coerceIn(0f, 1f) * screenW
        var globalEndY = (end?.second ?: start.second).coerceIn(0f, 1f) * screenH

        val root = FrameLayout(this).apply { setBackgroundColor(Color.argb(12, 0, 0, 0)) }
        val guide = MacroGeometryGuideView(this).apply { arrowEnabled = end != null }
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))

        val handleSize = dp(46)
        fun makeHandle(label: String) = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); background = rounded(Color.argb(205, 52, 103, 215), 23f)
            elevation = dp(7).toFloat()
        }
        val startHandle = makeHandle(if (end == null) "点" else "起")
        val endHandle = if (end != null) makeHandle("终") else null
        root.addView(startHandle, FrameLayout.LayoutParams(handleSize, handleSize))
        if (endHandle != null) root.addView(endHandle, FrameLayout.LayoutParams(handleSize, handleSize))

        fun origin(): Pair<Int, Int> {
            val loc = IntArray(2); root.getLocationOnScreen(loc); return loc[0] to loc[1]
        }
        fun update() {
            val (ox, oy) = origin()
            val sx = globalStartX - ox; val sy = globalStartY - oy
            val ex = globalEndX - ox; val ey = globalEndY - oy
            guide.x1 = sx; guide.y1 = sy; guide.x2 = ex; guide.y2 = ey
            (startHandle.layoutParams as FrameLayout.LayoutParams).also {
                it.leftMargin = (sx - handleSize / 2f).toInt().coerceIn(0, (root.width - handleSize).coerceAtLeast(0))
                it.topMargin = (sy - handleSize / 2f).toInt().coerceIn(0, (root.height - handleSize).coerceAtLeast(0))
                startHandle.layoutParams = it
            }
            if (endHandle != null) (endHandle.layoutParams as FrameLayout.LayoutParams).also {
                it.leftMargin = (ex - handleSize / 2f).toInt().coerceIn(0, (root.width - handleSize).coerceAtLeast(0))
                it.topMargin = (ey - handleSize / 2f).toInt().coerceIn(0, (root.height - handleSize).coerceAtLeast(0))
                endHandle.layoutParams = it
            }
        }
        fun installDrag(handle: View, isStart: Boolean) {
            var downX = 0f; var downY = 0f; var initialX = 0f; var initialY = 0f
            handle.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        initialX = if (isStart) globalStartX else globalEndX
                        initialY = if (isStart) globalStartY else globalEndY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val gx = (initialX + e.rawX - downX).coerceIn(0f, screenW.toFloat())
                        val gy = (initialY + e.rawY - downY).coerceIn(0f, screenH.toFloat())
                        if (isStart) { globalStartX = gx; globalStartY = gy } else { globalEndX = gx; globalEndY = gy }
                        update(); true
                    }
                    else -> true
                }
            }
        }
        installDrag(startHandle, true); if (endHandle != null) installDrag(endHandle, false)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(pill(title, true, false) {})
            addView(pill("取消", false, false) {
                removePositionEditor(); syncTriggerButtons(); editingMacroId?.let { findMacro(it)?.let(::showMacroEditor) }
            })
            addView(pill("确定", false, false) {
                val a = (globalStartX / screenW).coerceIn(0f, 1f) to (globalStartY / screenH).coerceIn(0f, 1f)
                val b = if (end != null) (globalEndX / screenW).coerceIn(0f, 1f) to (globalEndY / screenH).coerceIn(0f, 1f) else null
                onConfirm(a, b)
                removePositionEditor(); syncTriggerButtons(); editingMacroId?.let { findMacro(it)?.let(::showMacroEditor) }
            })
        }
        root.addView(controls, FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })

        val params = overlayParams(-1, -1, Gravity.TOP or Gravity.START).apply {
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        wm.addView(root, params)
        positionEditorView = root
        root.post { update() }
    }

    private fun removePositionEditor() {
        positionEditorView?.let { runCatching { wm.removeView(it) } }
        positionEditorView = null
    }

    // Recording ---------------------------------------------------------------

    private fun startOverlayRecording() {
        if (!client.isConnected) { connectRuntime(); return }
        hideMainMenu(); hideProfileSelector(); hideMacroEditor(); hideRecordingPicker(); hideTriggerButtons(); hideEntry()
        val name = "rec_" + SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val width = dp(94); val height = dp(34); val right = dp(10); val top = dp(22)
        val view = TextView(this).apply {
            text = "■ 结束"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD; background = rounded(Color.argb(198, 195, 57, 55), 17f)
            setOnClickListener { if (recording) stopOverlayRecording(name) }
        }
        val params = overlayParams(width, height, Gravity.TOP or Gravity.END).apply { x = right; y = top }
        wm.addView(view, params)
        recordingStopView = view
        view.post {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            requestAsync("RECORD_EXCLUDE ${loc[0]} ${loc[1]} ${loc[0] + view.width} ${loc[1] + view.height}") {
                requestAsync("RECORD_START $name") {
                    recording = true
                    activeRecordingName = name
                }
            }
        }
    }

    private fun stopOverlayRecording(name: String) {
        requestAsync("RECORD_STOP") {
            recording = false; activeRecordingName = null
            requestAsync("RECORD_EXCLUDE_CLEAR") {}
            removeRecordingStop(); showEntry(); syncTriggerButtons()
        }
    }

    private fun repositionRecordingStop() {
        val name = activeRecordingName ?: return
        removeRecordingStop()
        val width = dp(94); val height = dp(34); val right = dp(10); val top = dp(22)
        val view = TextView(this).apply {
            text = "■ 结束"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 11f
            typeface = Typeface.DEFAULT_BOLD; background = rounded(Color.argb(198, 195, 57, 55), 17f)
            setOnClickListener { stopOverlayRecording(name) }
        }
        wm.addView(view, overlayParams(width, height, Gravity.TOP or Gravity.END).apply { x = right; y = top })
        recordingStopView = view
        view.post {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            requestAsync("RECORD_EXCLUDE ${loc[0]} ${loc[1]} ${loc[0] + view.width} ${loc[1] + view.height}") {}
        }
    }

    private fun removeRecordingStop() {
        recordingStopView?.let { runCatching { wm.removeView(it) } }
        recordingStopView = null
    }

    // Persistence / lookup -----------------------------------------------------

    private fun findMacro(id: String): TouchMacro? = profiles.asSequence().flatMap { it.macros.asSequence() }.firstOrNull { it.id == id }

    private fun saveProfiles() = macroStore.saveProfiles(profiles)

    private fun parseRecordingNames(response: String): List<String> =
        response.removePrefix("RECORDINGS").trim().let { if (it.isBlank()) emptyList() else it.split(',').filter(String::isNotBlank) }

    private fun requestStatus() = requestAsync("STATUS") { runtimeStatus = it }

    private fun requestAsync(command: String, onSuccess: (String) -> Unit) {
        io.execute {
            runCatching {
                if (!client.isConnected) client.connect()
                client.request(command)
            }.onSuccess { result -> main.post { onSuccess(result) } }
                .onFailure { t -> main.post { runtimeStatus = "错误 · ${t.message ?: t.javaClass.simpleName}" } }
        }
    }

    private fun removeAllWindows() {
        hideMainMenu(); hideProfileSelector(); hideMacroEditor(); hideRecordingPicker(); hideTriggerButtons()
        removeRecordingStop(); removePositionEditor(); hideEntry()
    }

    // Small UI primitives ------------------------------------------------------

    private fun bubble(label: String, fill: Int, radius: Float, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); background = rounded(fill, radius); elevation = dp(5).toFloat()
        setOnClickListener { click() }
    }

    private fun pill(label: String, active: Boolean, compact: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = if (compact) 11f else 10.8f
        typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (active) Color.WHITE else ink)
        setPadding(dp(if (compact) 7 else 10), 0, dp(if (compact) 7 else 10), 0)
        background = rounded(
            if (active) Color.argb(190, 52, 103, 215) else Color.argb(172, 245, 247, 250),
            18f,
            if (active) null else Color.argb(110, 120, 126, 138),
        )
        setOnClickListener { click() }
    }

    private fun valuePill(label: String) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 10.5f; setTextColor(muted)
        setPadding(dp(9), 0, dp(9), 0)
        background = rounded(Color.argb(145, 238, 240, 244), 18f)
    }

    private fun horizontalStrip(views: List<View>, topMargin: Int = 0): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        views.forEach { v -> row.addView(v, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(5) }) }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            addView(row, ViewGroup.LayoutParams(-2, dp(38)))
            if (topMargin > 0) setPadding(0, topMargin, 0, 0)
        }
    }

    private fun actionShort(action: MacroAction): String = when (action) {
        is MacroAction.Tap -> "点"
        is MacroAction.Drag -> "拖"
        is MacroAction.Wait -> "等"
        is MacroAction.Recording -> "录"
    }

    private fun trimSpeed(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else value.toString()

    private fun triggerBackground(editing: Boolean, running: Boolean): GradientDrawable = when {
        running -> rounded(Color.argb(205, 44, 151, 99), 48f)
        editing -> rounded(Color.argb(205, 52, 103, 215), 48f, Color.argb(230, 255, 255, 255))
        else -> rounded(Color.argb(172, 52, 103, 215), 48f)
    }

    private fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
