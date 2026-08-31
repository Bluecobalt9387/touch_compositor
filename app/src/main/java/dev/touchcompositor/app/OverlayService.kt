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
import android.text.InputType
import android.widget.EditText
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
    private enum class UiMode { NONE, RECORDINGS, MACROS }
    private var uiMode = UiMode.NONE

    private var entryView: View? = null
    private val menuViews = mutableListOf<View>()
    private var recordingManagerView: View? = null
    private var macroManagerView: View? = null
    private var editorView: View? = null
    private var recordingPickerView: View? = null
    private var nameEditorView: View? = null
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
    private val recordingLabels by lazy { getSharedPreferences("recording_labels", MODE_PRIVATE) }

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
            hideRecordingManager()
            hideMacroManager()
            hideMacroEditor()
            hideRecordingPicker()
            hideNameEditor()
            editingMacroId = null
            selectedActionIndex = null
            if (positionEditorView != null) removePositionEditor()
            hideEntry()
            if (!recording) showEntry()
            if (recording) repositionRecordingStop() else {
                syncTriggerButtons()
                when (uiMode) {
                    UiMode.RECORDINGS -> showRecordingManager()
                    UiMode.MACROS -> showMacroManager()
                    UiMode.NONE -> Unit
                }
            }
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

    // Top entry + compact mode menu -------------------------------------------

    private fun showEntry() {
        if (!Settings.canDrawOverlays(this) || entryView != null || recording) return
        val w = dp(44)
        val h = dp(16)
        val view = TextView(this).apply {
            text = "⌄"
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(Color.argb(218, 255, 255, 255))
            background = rounded(Color.argb(112, 24, 27, 32), 8f)
            setOnClickListener { if (menuViews.isEmpty()) showMainMenu() else hideMainMenu() }
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
        val size = dp(48)
        val gap = dp(12)
        val total = size * 3 + gap * 2
        val startX = ((resources.displayMetrics.widthPixels - total) / 2).coerceAtLeast(0)
        val y = dp(28)
        val specs = listOf(
            Triple("录制", Color.argb(184, 190, 57, 55), {
                hideMainMenu(); uiMode = UiMode.RECORDINGS
                hideMacroManager(); hideMacroEditor(); showRecordingManager(); syncTriggerButtons()
            }),
            Triple("宏编辑", Color.argb(184, 52, 103, 215), {
                hideMainMenu(); uiMode = UiMode.MACROS
                hideRecordingManager(); showMacroManager(); syncTriggerButtons()
            }),
            Triple("隐藏", Color.argb(170, 42, 46, 53), {
                hideMainMenu(); hideRecordingManager(); hideMacroManager(); hideMacroEditor(); hideRecordingPicker(); hideNameEditor()
                uiMode = UiMode.NONE; editingMacroId = null; selectedActionIndex = null; syncTriggerButtons()
            }),
        )
        specs.forEachIndexed { index, (label, color, action) ->
            val v = bubble(label, color, dp(24).toFloat()) { action() }
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

    // Profiles / macro edit mode ---------------------------------------------

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

    private fun showMacroManager() {
        hideMacroManager()
        if (recording || uiMode != UiMode.MACROS) return
        val root = panelRoot()
        root.addView(panelHeader("宏编辑") { uiMode = UiMode.NONE; hideMacroManager(); syncTriggerButtons() })

        val profileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        profiles.forEach { profile ->
            profileRow.addView(pill(profile.name, profile.id == activeProfileId, false) {
                activeProfileId = profile.id
                macroStore.saveActiveProfileId(profile.id)
                editingMacroId = null; selectedActionIndex = null
                saveProfiles(); syncTriggerButtons(); showMacroManager()
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(5) })
        }
        profileRow.addView(pill("+", false, true) { createProfile() }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(profileRow, ViewGroup.LayoutParams(-2, dp(38)))
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })

        val active = activeProfile()
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nameInput = compactInput(active.name, "配置名称")
        nameRow.addView(nameInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        nameRow.addView(compactTextButton("保存") {
            active.name = nameInput.text.toString().trim().ifBlank { "配置" }
            saveProfiles(); showMacroManager()
        }, LinearLayout.LayoutParams(dp(58), dp(40)).apply { rightMargin = dp(5) })
        nameRow.addView(compactTextButton("删除") { deleteProfile(active) }, LinearLayout.LayoutParams(dp(58), dp(40)))
        root.addView(nameRow, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(7) })

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(compactTextButton("+ 新建宏") { createMacro() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(7) })
        bottom.addView(TextView(this).apply {
            text = "拖动触发器调整位置 · 双击编辑"
            textSize = 10.5f; setTextColor(muted); gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(42), 1.5f))
        root.addView(bottom, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })

        val width = minOf(dp(330), resources.displayMetrics.widthPixels - dp(20))
        val p = focusableOverlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { x = dp(10); y = dp(58) }
        wm.addView(root, p)
        macroManagerView = root
    }

    private fun hideMacroManager() {
        macroManagerView?.let { runCatching { wm.removeView(it) } }
        macroManagerView = null
    }

    private fun createProfile() {
        val profile = MacroProfile(name = "配置 ${profiles.size + 1}")
        profiles += profile
        activeProfileId = profile.id
        saveProfiles(); macroStore.saveActiveProfileId(profile.id)
        syncTriggerButtons(); showMacroManager()
    }

    private fun deleteProfile(profile: MacroProfile) {
        if (profiles.size <= 1) return
        if (profile.macros.any { it.id == runningMacroId }) macroRunner.stop()
        profiles.removeAll { it.id == profile.id }
        activeProfileId = profiles.first().id
        macroStore.saveActiveProfileId(activeProfileId!!)
        editingMacroId = null; selectedActionIndex = null
        saveProfiles(); syncTriggerButtons(); showMacroManager()
    }

    private fun createMacro() {
        val profile = activeProfile()
        val n = profile.macros.size + 1
        val offset = (profile.macros.size % 6) * 0.065f
        val macro = TouchMacro(
            name = "宏 $n",
            trigger = MacroTrigger(MacroTriggerMode.CLICK_ONCE, 0.88f, (0.34f + offset).coerceAtMost(0.80f), 46f),
        )
        profile.macros += macro
        saveProfiles(); syncTriggerButtons(); showMacroEditor(macro)
    }

    // Trigger buttons ----------------------------------------------------------

    private fun syncTriggerButtons() {
        hideTriggerButtons()
        if (!Settings.canDrawOverlays(this) || recording || positionEditorView != null) return
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
        val editing = uiMode == UiMode.MACROS && editingMacroId == macro.id
        val running = runningMacroId == macro.id
        val view = TextView(this).apply {
            text = (index + 1).toString() + if (macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) "↻" else ""
            gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            textSize = (macro.trigger.sizeDp * 0.27f).coerceIn(9f, 18f); typeface = Typeface.DEFAULT_BOLD
            background = triggerBackground(editing, running); elevation = dp(5).toFloat()
        }
        val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
            x = (macro.trigger.x * w - size / 2f).toInt().coerceIn(0, (w - size).coerceAtLeast(0))
            y = (macro.trigger.y * h - size / 2f).toInt().coerceIn(0, (h - size).coerceAtLeast(0))
        }
        installTriggerTouch(view, params, macro, size)
        wm.addView(view, params); triggerViews[macro.id] = view
    }

    private fun installTriggerTouch(view: View, params: WindowManager.LayoutParams, macro: TouchMacro, size: Int) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var holdStarted = false
        var lastTapAt = 0L; var pendingSingle: Runnable? = null
        val editable = { uiMode == UiMode.MACROS }
        val holdRunnable = Runnable {
            if (!moved && macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT && !macroRunner.isRunning) {
                holdStarted = startMacro(macro, true, view)
            }
        }
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; holdStarted = false
                    if (macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) main.postDelayed(holdRunnable, HOLD_TRIGGER_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (editable()) {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                            moved = true; main.removeCallbacks(holdRunnable); pendingSingle?.let { main.removeCallbacks(it) }; pendingSingle = null; lastTapAt = 0L
                        }
                        if (moved && !holdStarted) {
                            params.x = (startX + dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                            params.y = (startY + dy.toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                            runCatching { wm.updateViewLayout(view, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdRunnable)
                    when {
                        editable() && moved && !holdStarted -> {
                            val sw = resources.displayMetrics.widthPixels.toFloat(); val sh = resources.displayMetrics.heightPixels.toFloat()
                            macro.trigger.x = ((params.x + size / 2f) / sw).coerceIn(0f, 1f)
                            macro.trigger.y = ((params.y + size / 2f) / sh).coerceIn(0f, 1f); saveProfiles()
                        }
                        holdStarted -> macroRunner.stop()
                        e.actionMasked == MotionEvent.ACTION_UP -> {
                            if (!editable()) {
                                if (macro.trigger.mode == MacroTriggerMode.CLICK_ONCE && !macroRunner.isRunning) startMacro(macro, false, view)
                            } else {
                                val now = SystemClock.uptimeMillis()
                                if (lastTapAt != 0L && now - lastTapAt <= DOUBLE_TAP_MS) {
                                    pendingSingle?.let { main.removeCallbacks(it) }; pendingSingle = null; lastTapAt = 0L; showMacroEditor(macro)
                                } else {
                                    lastTapAt = now
                                    if (macro.trigger.mode == MacroTriggerMode.CLICK_ONCE) {
                                        val stamp = now
                                        val r = Runnable {
                                            if (lastTapAt == stamp) { lastTapAt = 0L; if (!macroRunner.isRunning) startMacro(macro, false, view) }
                                        }
                                        pendingSingle = r; main.postDelayed(r, DOUBLE_TAP_MS)
                                    }
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

    private fun startMacro(macro: TouchMacro, repeat: Boolean, source: View?): Boolean = runCatching {
        macroRunner.start(macro, repeat); runningMacroId = macro.id
        source?.background = triggerBackground(uiMode == UiMode.MACROS && editingMacroId == macro.id, true); true
    }.getOrElse {
        source?.background = rounded(Color.argb(205, 204, 66, 63), macro.trigger.sizeDp / 2f)
        source?.postDelayed({ source.background = triggerBackground(false, false) }, 550)
        runtimeStatus = "宏错误 · ${it.message ?: it.javaClass.simpleName}"; false
    }

    // Recording manager --------------------------------------------------------

    private fun showRecordingManager() {
        hideRecordingManager()
        if (recording || uiMode != UiMode.RECORDINGS) return
        val root = panelRoot()
        root.addView(panelHeader("录制动作") { uiMode = UiMode.NONE; hideRecordingManager() })

        val create = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labelInput = compactInput("", "新录制名称")
        create.addView(labelInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(7) })
        create.addView(compactTextButton("开始录制") {
            startOverlayRecording(labelInput.text.toString().trim())
        }, LinearLayout.LayoutParams(dp(86), dp(40)))
        root.addView(create, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaxHeightScrollView(this).apply { maxHeightPx = dp(360); addView(list, ViewGroup.LayoutParams(-1, -2)) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        requestAsync("LIST") { response ->
            if (uiMode != UiMode.RECORDINGS || recordingManagerView == null) return@requestAsync
            val names = parseRecordingNames(response)
            list.removeAllViews()
            if (names.isEmpty()) list.addView(note("还没有录制动作"))
            names.forEach { name -> list.addView(recordingManagerRow(name)) }
        }

        val width = minOf(dp(330), resources.displayMetrics.widthPixels - dp(20))
        val p = focusableOverlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { x = dp(10); y = dp(58) }
        wm.addView(root, p); recordingManagerView = root
    }

    private fun recordingManagerRow(name: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(5), dp(5)); background = rounded(Color.argb(220, 255, 255, 255), 11f, Color.argb(90, 120, 126, 138))
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@OverlayService).apply { text = recordingLabel(name); textSize = 12.5f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
            addView(TextView(this@OverlayService).apply { text = name; textSize = 9.5f; setTextColor(muted) })
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(smallAction("播放") { requestAsync("PLAY $name 1.0 1") {} })
        row.addView(smallAction("改名") { showNameEditor("录制名称", recordingLabel(name)) { setRecordingLabel(name, it); showRecordingManager() } })
        row.addView(smallAction("删除", danger = true) {
            requestAsync("DELETE $name") { recordingLabels.edit().remove(name).apply(); showRecordingManager() }
        })
        return row.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(6) } }
    }

    private fun hideRecordingManager() {
        recordingManagerView?.let { runCatching { wm.removeView(it) } }; recordingManagerView = null
    }

    // Trigger-bound macro editor ----------------------------------------------

    private fun showMacroEditor(macro: TouchMacro) {
        if (uiMode != UiMode.MACROS) return
        hideMacroManager(); hideMacroEditor(); hideRecordingPicker(); editingMacroId = macro.id
        if (selectedActionIndex != null && selectedActionIndex !in macro.actions.indices) selectedActionIndex = null
        syncTriggerButtons()

        val content = panelRoot()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val macroName = compactInput(macro.name, "宏名称")
        header.addView(macroName, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(6) })
        header.addView(compactTextButton("保存") { macro.name = macroName.text.toString().trim().ifBlank { "宏" }; saveProfiles(); showMacroEditor(macro) }, LinearLayout.LayoutParams(dp(56), dp(40)).apply { rightMargin = dp(5) })
        header.addView(compactTextButton("完成") { closeMacroEditor() }, LinearLayout.LayoutParams(dp(56), dp(40)))
        content.addView(header)

        val triggerSettings = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        triggerSettings.addView(TextView(this).apply { text = "触发"; textSize = 11f; setTextColor(muted) }, LinearLayout.LayoutParams(dp(40), dp(38)))
        triggerSettings.addView(modeChoice("点击单次", macro.trigger.mode == MacroTriggerMode.CLICK_ONCE) { macro.trigger.mode = MacroTriggerMode.CLICK_ONCE; saveProfiles(); syncTriggerButtons(); showMacroEditor(macro) })
        triggerSettings.addView(modeChoice("长按循环", macro.trigger.mode == MacroTriggerMode.HOLD_REPEAT) { macro.trigger.mode = MacroTriggerMode.HOLD_REPEAT; saveProfiles(); syncTriggerButtons(); showMacroEditor(macro) })
        triggerSettings.addView(smallAction("−") { resizeTrigger(macro, -4f) })
        triggerSettings.addView(TextView(this).apply { text = "${macro.trigger.sizeDp.toInt()}"; gravity = Gravity.CENTER; textSize = 10.5f; setTextColor(muted) }, LinearLayout.LayoutParams(dp(34), dp(38)))
        triggerSettings.addView(smallAction("+") { resizeTrigger(macro, 4f) })
        content.addView(triggerSettings, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })

        content.addView(sectionLabel("动作 · 顺序执行"), LinearLayout.LayoutParams(-1, dp(32)).apply { topMargin = dp(5) })
        val actionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        macro.actions.forEachIndexed { index, action -> actionList.addView(actionRow(macro, index, action)) }
        if (macro.actions.isEmpty()) actionList.addView(note("还没有动作"))
        content.addView(MaxHeightScrollView(this).apply { maxHeightPx = dp(300); addView(actionList, ViewGroup.LayoutParams(-1, -2)) })

        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(
            "+ 点击" to { showTapPositionEditor(macro, null) },
            "+ 拖动" to { showDragPositionEditor(macro, null) },
            "+ 等待" to { macro.actions += MacroAction.Wait(500); selectedActionIndex = macro.actions.lastIndex; saveProfiles(); showMacroEditor(macro) },
            "+ 录制" to { showRecordingPicker(macro, null) },
        ).forEachIndexed { i, (label, action) ->
            addRow.addView(compactTextButton(label) { action() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { if (i < 3) rightMargin = dp(5) })
        }
        content.addView(addRow, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(7) })

        selectedActionIndex?.let { index -> macro.actions.getOrNull(index)?.let { content.addView(actionDetail(macro, index, it), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) } }
        content.addView(compactTextButton("删除这个宏", danger = true) { deleteMacro(macro) }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })

        val width = minOf(dp(350), resources.displayMetrics.widthPixels - dp(18))
        val maxH = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        val scroll = MaxHeightScrollView(this).apply { maxHeightPx = maxH; addView(content, ViewGroup.LayoutParams(-1, -2)) }
        val p = focusableOverlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { x = dp(9); y = dp(52) }
        wm.addView(scroll, p); editorView = scroll
    }

    private fun actionRow(macro: TouchMacro, index: Int, action: MacroAction): View {
        val selected = selectedActionIndex == index
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(4), dp(4), dp(4)); background = rounded(if (selected) Color.argb(205, 234, 241, 255) else Color.argb(210, 255, 255, 255), 10f, Color.argb(80, 120, 126, 138))
            addView(LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@OverlayService).apply { text = "${index + 1}. ${actionTitle(action)}"; textSize = 12f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(this@OverlayService).apply { text = actionSummary(action); textSize = 10f; setTextColor(muted) })
                setOnClickListener { selectedActionIndex = index; showMacroEditor(macro) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(smallAction("↑") { moveAction(macro, index, -1) })
            addView(smallAction("↓") { moveAction(macro, index, 1) })
            addView(smallAction("×", danger = true) { macro.actions.removeAt(index); selectedActionIndex = null; saveProfiles(); showMacroEditor(macro) })
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) } }
    }

    private fun actionDetail(macro: TouchMacro, index: Int, action: MacroAction): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(9)); background = rounded(Color.argb(220, 246, 248, 251), 11f, Color.argb(75, 120, 126, 138))
        addView(sectionLabel("编辑 ${index + 1}. ${actionTitle(action)}"))
        when (action) {
            is MacroAction.Tap -> {
                addView(parameterRow("按压时间", "${action.holdMs} ms", { action.holdMs = (action.holdMs - 20).coerceAtLeast(20); saveProfiles(); showMacroEditor(macro) }, { action.holdMs = (action.holdMs + 20).coerceAtMost(5000); saveProfiles(); showMacroEditor(macro) }))
                addView(compactTextButton("可视化设置点击位置") { showTapPositionEditor(macro, index) }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
            }
            is MacroAction.Drag -> {
                addView(parameterRow("拖动时间", "${action.durationMs} ms", { action.durationMs = (action.durationMs - 100).coerceAtLeast(50); saveProfiles(); showMacroEditor(macro) }, { action.durationMs = (action.durationMs + 100).coerceAtMost(30000); saveProfiles(); showMacroEditor(macro) }))
                addView(compactTextButton("可视化设置起点、终点和方向") { showDragPositionEditor(macro, index) }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
            }
            is MacroAction.Wait -> addView(parameterRow("等待时间", "${action.durationMs} ms", { action.durationMs = (action.durationMs - 100).coerceAtLeast(0); saveProfiles(); showMacroEditor(macro) }, { action.durationMs = (action.durationMs + 100).coerceAtMost(60000); saveProfiles(); showMacroEditor(macro) }))
            is MacroAction.Recording -> {
                addView(parameterRow("播放速度", "${trimSpeed(action.speed)} ×", { action.speed = (action.speed / 2f).coerceAtLeast(0.25f); saveProfiles(); showMacroEditor(macro) }, { action.speed = (action.speed * 2f).coerceAtMost(8f); saveProfiles(); showMacroEditor(macro) }))
                addView(compactTextButton("更换录制动作 · ${recordingLabel(action.recordingName)}") { showRecordingPicker(macro, index) }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(5) })
            }
        }
    }

    private fun hideMacroEditor() { editorView?.let { runCatching { wm.removeView(it) } }; editorView = null }
    private fun closeMacroEditor() { hideMacroEditor(); hideRecordingPicker(); editingMacroId = null; selectedActionIndex = null; syncTriggerButtons(); if (uiMode == UiMode.MACROS) showMacroManager() }
    private fun resizeTrigger(macro: TouchMacro, delta: Float) { macro.trigger.sizeDp = (macro.trigger.sizeDp + delta).coerceIn(28f, 96f); saveProfiles(); syncTriggerButtons(); showMacroEditor(macro) }
    private fun deleteMacro(macro: TouchMacro) {
        if (runningMacroId == macro.id) macroRunner.stop(); activeProfile().macros.removeAll { it.id == macro.id }; saveProfiles()
        editingMacroId = null; selectedActionIndex = null; hideMacroEditor(); hideRecordingPicker(); syncTriggerButtons(); if (uiMode == UiMode.MACROS) showMacroManager()
    }
    private fun moveAction(macro: TouchMacro, index: Int, delta: Int) {
        if (macro.actions.isEmpty()) return; val to = (index + delta).coerceIn(0, macro.actions.lastIndex); if (to == index) return
        val value = macro.actions.removeAt(index); macro.actions.add(to, value); selectedActionIndex = to; saveProfiles(); showMacroEditor(macro)
    }

    // Recording action picker --------------------------------------------------

    private fun showRecordingPicker(macro: TouchMacro, replaceIndex: Int?) {
        hideRecordingPicker()
        requestAsync("LIST") { response ->
            if (editingMacroId != macro.id) return@requestAsync
            val names = parseRecordingNames(response)
            val root = panelRoot(); root.addView(panelHeader("选择录制动作") { hideRecordingPicker() })
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.forEach { name ->
                list.addView(compactTextButton(recordingLabel(name)) {
                    val old = replaceIndex?.let { macro.actions.getOrNull(it) as? MacroAction.Recording }
                    val value = MacroAction.Recording(name, old?.speed ?: 1f)
                    if (replaceIndex == null) { macro.actions += value; selectedActionIndex = macro.actions.lastIndex } else { macro.actions[replaceIndex] = value; selectedActionIndex = replaceIndex }
                    saveProfiles(); hideRecordingPicker(); showMacroEditor(macro)
                }, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(5) })
            }
            if (names.isEmpty()) list.addView(note("暂无录制动作，请先在录制模式创建"))
            root.addView(MaxHeightScrollView(this).apply { maxHeightPx = dp(320); addView(list, ViewGroup.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val width = minOf(dp(320), resources.displayMetrics.widthPixels - dp(24))
            val p = focusableOverlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {}
            wm.addView(root, p); recordingPickerView = root
        }
    }

    private fun hideRecordingPicker() { recordingPickerView?.let { runCatching { wm.removeView(it) } }; recordingPickerView = null }

    private fun showNameEditor(title: String, initial: String, onSave: (String) -> Unit) {
        hideNameEditor()
        val root = panelRoot(); root.addView(sectionLabel(title))
        val input = compactInput(initial, title)
        root.addView(input, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(7) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(compactTextButton("取消") { hideNameEditor() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        actions.addView(compactTextButton("保存") { val value = input.text.toString().trim(); if (value.isNotEmpty()) onSave(value); hideNameEditor() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(7) })
        val p = focusableOverlayParams(minOf(dp(300), resources.displayMetrics.widthPixels - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        wm.addView(root, p); nameEditorView = root
    }

    private fun hideNameEditor() { nameEditorView?.let { runCatching { wm.removeView(it) } }; nameEditorView = null }

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
        hideMacroEditor(); hideRecordingPicker(); hideMacroManager(); hideMainMenu(); hideTriggerButtons(); removePositionEditor()
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

    private fun startOverlayRecording(displayName: String = "") {
        if (!client.isConnected) { connectRuntime(); return }
        hideMainMenu(); hideRecordingManager(); hideMacroManager(); hideMacroEditor(); hideRecordingPicker(); hideNameEditor(); hideTriggerButtons(); hideEntry()
        val name = "rec_" + SimpleDateFormat("HHmmss", Locale.US).format(Date())
        setRecordingLabel(name, displayName.ifBlank { "录制 " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) })
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
            if (uiMode == UiMode.RECORDINGS) showRecordingManager()
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

    private fun recordingLabel(name: String): String = recordingLabels.getString(name, null)?.takeIf { it.isNotBlank() } ?: name
    private fun setRecordingLabel(name: String, label: String) { recordingLabels.edit().putString(name, label.trim().ifBlank { name }).apply() }


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
        hideMainMenu(); hideRecordingManager(); hideMacroManager(); hideMacroEditor(); hideRecordingPicker(); hideNameEditor(); hideTriggerButtons()
        removeRecordingStop(); removePositionEditor(); hideEntry()
    }

    private fun panelRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = rounded(Color.argb(236, 248, 249, 251), 16f, Color.argb(100, 112, 118, 128))
        elevation = dp(10).toFloat()
    }

    private fun panelHeader(title: String, close: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@OverlayService).apply { text = title; textSize = 15f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(36), 1f))
        addView(compactTextButton("关闭") { close() }, LinearLayout.LayoutParams(dp(58), dp(36)))
    }

    private fun compactInput(value: String, hintValue: String) = EditText(this).apply {
        setText(value); hint = hintValue; textSize = 13f; setTextColor(ink); setHintTextColor(Color.argb(145, 100, 106, 116))
        isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT; setPadding(dp(10), 0, dp(10), 0)
        background = rounded(Color.argb(220, 255, 255, 255), 10f, Color.argb(95, 115, 121, 132))
        setSelectAllOnFocus(false)
    }

    private fun compactTextButton(label: String, danger: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (danger) red else accent)
        background = rounded(Color.argb(190, 255, 255, 255), 10f, Color.argb(85, 120, 126, 138))
        setOnClickListener { click() }
    }

    private fun smallAction(label: String, danger: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 10.5f; setTextColor(if (danger) red else accent); setPadding(dp(7), 0, dp(7), 0); setOnClickListener { click() }
    }

    private fun modeChoice(label: String, active: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 10.5f; setTextColor(if (active) Color.WHITE else ink)
        background = rounded(if (active) Color.argb(190, 52, 103, 215) else Color.argb(175, 255, 255, 255), 9f, Color.argb(70, 120, 126, 138))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(72), dp(36)).apply { rightMargin = dp(5) }
    }

    private fun sectionLabel(value: String) = TextView(this).apply { text = value; textSize = 11.5f; setTextColor(muted); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL }

    private fun note(value: String) = TextView(this).apply { text = value; textSize = 11f; setTextColor(muted); setPadding(dp(4), dp(8), dp(4), dp(8)) }

    private fun parameterRow(label: String, value: String, minus: () -> Unit, plus: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@OverlayService).apply { text = label; textSize = 11.5f; setTextColor(ink) }, LinearLayout.LayoutParams(0, dp(38), 1f))
        addView(smallActionView("−", minus))
        addView(TextView(this@OverlayService).apply { text = value; gravity = Gravity.CENTER; textSize = 11f; setTextColor(muted) }, LinearLayout.LayoutParams(dp(84), dp(38)))
        addView(smallActionView("+", plus))
    }

    private fun smallActionView(label: String, click: () -> Unit) = TextView(this).apply { text = label; gravity = Gravity.CENTER; textSize = 15f; setTextColor(accent); setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) }

    private fun actionTitle(action: MacroAction): String = when (action) {
        is MacroAction.Tap -> "点击"
        is MacroAction.Drag -> "拖动"
        is MacroAction.Wait -> "等待"
        is MacroAction.Recording -> "播放录制"
    }

    private fun actionSummary(action: MacroAction): String = when (action) {
        is MacroAction.Tap -> "位置 ${(action.x * 100).toInt()}%, ${(action.y * 100).toInt()}% · ${action.holdMs}ms"
        is MacroAction.Drag -> "${action.durationMs}ms · ${(action.x1 * 100).toInt()}%,${(action.y1 * 100).toInt()}% → ${(action.x2 * 100).toInt()}%,${(action.y2 * 100).toInt()}%"
        is MacroAction.Wait -> "${action.durationMs}ms"
        is MacroAction.Recording -> "${recordingLabel(action.recordingName)} · ${trimSpeed(action.speed)}×"
    }

    private fun focusableOverlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

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
