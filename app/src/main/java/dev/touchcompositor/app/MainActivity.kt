package dev.touchcompositor.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), RuntimeClient.Listener {
    private val io = Executors.newSingleThreadExecutor()
    private val client = RuntimeClient()

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var recordName: EditText
    private lateinit var recordButton: Button
    private lateinit var recordingHint: TextView
    private lateinit var recordingsBox: LinearLayout
    private lateinit var selectedText: TextView
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var stopButton: Button
    private lateinit var speedSpinner: Spinner
    private lateinit var repeatSpinner: Spinner
    private lateinit var logText: TextView

    private var selectedRecording: String? = null
    private var recording = false
    private var paused = false

    private val bg = Color.rgb(246, 247, 249)
    private val card = Color.WHITE
    private val ink = Color.rgb(25, 28, 33)
    private val muted = Color.rgb(110, 116, 126)
    private val accent = Color.rgb(47, 99, 209)
    private val green = Color.rgb(40, 153, 101)
    private val red = Color.rgb(202, 70, 68)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client.listener = this
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildUi())
        connect()
    }

    override fun onResume() {
        super.onResume()
        if (!client.isConnected) connect() else refreshAll()
    }

    override fun onDestroy() {
        client.close()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(40))
            isFocusableInTouchMode = true
            requestFocus()
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))

        root.addView(TextView(this).apply {
            text = "Touch Compositor"
            textSize = 29f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "触摸录制、重放与实时合成"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(18))
        })

        root.addView(statusCard())
        root.addView(sectionGap())
        root.addView(recordCard())
        root.addView(sectionGap())
        root.addView(recordingsCard())
        root.addView(sectionGap())
        root.addView(playbackCard())
        root.addView(sectionGap())
        root.addView(diagnosticsCard())
        scroll.post { scroll.scrollTo(0, 0) }
        return scroll
    }

    private fun statusCard(): View = cardLayout().apply {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this@MainActivity).apply { background = circle(Color.GRAY) }
        row.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })
        statusText = TextView(this@MainActivity).apply {
            text = "正在连接…"
            textSize = 16f
            setTextColor(ink)
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(textButton("重连") { connect() })
        addView(row)
        detailText = TextView(this@MainActivity).apply {
            text = "127.0.0.1:28183"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        }
        addView(detailText)
    }

    private fun recordCard(): View = cardLayout().apply {
        addView(cardTitle("录制人手动作"))
        addView(TextView(this@MainActivity).apply {
            text = "正常操作屏幕即可。支持多指、轨迹与原始时间间隔。"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(14))
        })
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recordName = EditText(this@MainActivity).apply {
            hint = "录制名称"
            setText(defaultRecordingName())
            textSize = 15f
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(32))
            background = rounded(Color.rgb(245, 246, 248), 12f, Color.TRANSPARENT)
            setPadding(dp(13), 0, dp(13), 0)
        }
        row.addView(recordName, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(10) })
        recordButton = primaryButton("开始录制") { toggleRecording() }
        row.addView(recordButton, LinearLayout.LayoutParams(-2, dp(46)))
        addView(row)
        recordingHint = TextView(this@MainActivity).apply {
            text = "未录制"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        }
        addView(recordingHint)
    }

    private fun recordingsCard(): View = cardLayout().apply {
        val head = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(cardTitle("录制列表"), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(textButton("刷新") { refreshRecordings() })
        addView(head)
        recordingsBox = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        addView(recordingsBox)
    }

    private fun playbackCard(): View = cardLayout().apply {
        addView(cardTitle("播放"))
        selectedText = TextView(this@MainActivity).apply {
            text = "尚未选择录制"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(6), 0, dp(14))
        }
        addView(selectedText)

        val options = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        options.addView(label("速度"))
        speedSpinner = spinner(listOf("0.5×", "1×", "2×", "4×")).apply { setSelection(1) }
        options.addView(speedSpinner, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(12) })
        options.addView(label("次数"))
        repeatSpinner = spinner(listOf("1", "2", "3", "5", "10"))
        options.addView(repeatSpinner, LinearLayout.LayoutParams(0, dp(44), 1f))
        addView(options)

        val controls = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        playButton = primaryButton("播放") { playSelected() }
        pauseButton = secondaryButton("暂停") { togglePause() }
        stopButton = secondaryButton("停止") { command("STOP_PLAYBACK", "已请求停止") }
        controls.addView(playButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        controls.addView(pauseButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        controls.addView(stopButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(controls)
    }

    private fun diagnosticsCard(): View = cardLayout().apply {
        val head = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(cardTitle("运行信息"), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(textButton("状态") { refreshStatus() })
        head.addView(textButton("能力") { showCapabilities() })
        addView(head)
        logText = TextView(this@MainActivity).apply {
            text = "等待 runtime…"
            textSize = 12f
            setTextColor(muted)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        addView(logText)
    }

    private fun connect() {
        setConnection(false, "正在连接…", "127.0.0.1:28183")
        io.execute {
            try {
                client.connect()
                refreshAll()
            } catch (t: Throwable) {
                runOnUiThread {
                    setConnection(false, "Runtime 未运行", "需要先启动 shell runtime · 点击重连")
                    appendLog("连接失败：${t.message ?: t.javaClass.simpleName}")
                }
            }
        }
    }

    private fun refreshAll() {
        refreshStatus()
        refreshRecordings()
    }

    private fun refreshStatus() = request("STATUS") { status ->
        detailText.text = status
        recording = status.contains("recording=true")
        updateRecordingUi()
    }

    private fun refreshRecordings() = request("LIST") { result ->
        val payload = result.removePrefix("RECORDINGS").trim()
        val names = if (payload.isBlank()) emptyList() else payload.split(',').filter { it.isNotBlank() }
        renderRecordings(names)
    }

    private fun renderRecordings(names: List<String>) {
        recordingsBox.removeAllViews()
        if (names.isEmpty()) {
            recordingsBox.addView(TextView(this).apply {
                text = "还没有录制"
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(8), 0, dp(4))
            })
            selectedRecording = null
            selectedText.text = "尚未选择录制"
            return
        }
        if (selectedRecording !in names) selectedRecording = names.first()
        names.forEach { name ->
            val selected = name == selectedRecording
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(if (selected) Color.rgb(237, 243, 255) else Color.rgb(247, 248, 250), 12f, Color.TRANSPARENT)
                setPadding(dp(12), dp(7), dp(8), dp(7))
                setOnClickListener {
                    selectedRecording = name
                    selectedText.text = "已选择：$name"
                    renderRecordings(names)
                }
            }
            row.addView(TextView(this).apply {
                text = name
                textSize = 14f
                setTextColor(if (selected) accent else ink)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(textButton("删除") { confirmDelete(name) })
            recordingsBox.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })
        }
        selectedText.text = "已选择：${selectedRecording}"
    }

    private fun toggleRecording() {
        if (!recording) {
            var name = recordName.text.toString().trim()
            if (name.isBlank()) name = defaultRecordingName()
            name = name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
            if (name.isBlank()) name = "recording"
            recordName.setText(name)
            request("RECORD_START $name") {
                recording = true
                updateRecordingUi()
                appendLog("开始录制：$name")
            }
        } else {
            request("RECORD_STOP") { result ->
                recording = false
                updateRecordingUi()
                appendLog(result)
                recordName.setText(defaultRecordingName())
                refreshRecordings()
            }
        }
    }

    private fun updateRecordingUi() {
        recordButton.text = if (recording) "结束录制" else "开始录制"
        recordButton.background = rounded(if (recording) red else accent, 12f, Color.TRANSPARENT)
        recordName.isEnabled = !recording
        recordingHint.text = if (recording) "● 正在录制人手触摸" else "未录制"
        recordingHint.setTextColor(if (recording) red else muted)
    }

    private fun playSelected() {
        val name = selectedRecording ?: return toast("先选择一个录制")
        val speed = when (speedSpinner.selectedItemPosition) { 0 -> 0.5; 1 -> 1.0; 2 -> 2.0; else -> 4.0 }
        val repeat = repeatSpinner.selectedItem.toString().toInt()
        request("PLAY $name $speed $repeat") {
            paused = false
            pauseButton.text = "暂停"
            appendLog("播放：$name · ${speed}× · ${repeat}次")
        }
    }

    private fun togglePause() {
        val cmd = if (paused) "RESUME" else "PAUSE"
        request(cmd) {
            paused = !paused
            pauseButton.text = if (paused) "继续" else "暂停"
            appendLog(if (paused) "播放已暂停" else "继续播放")
        }
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("删除录制？")
            .setMessage(name)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                request("DELETE $name") {
                    if (selectedRecording == name) selectedRecording = null
                    refreshRecordings()
                }
            }.show()
    }

    private fun showCapabilities() = request("CAPS") { result ->
        AlertDialog.Builder(this).setTitle("Touch Engine 能力").setMessage(result.replace(' ', '\n')).setPositiveButton("确定", null).show()
    }

    private fun command(command: String, message: String) = request(command) { appendLog(message) }

    private fun request(command: String, onOk: (String) -> Unit = {}) {
        io.execute {
            try {
                if (!client.isConnected) client.connect()
                val result = client.request(command)
                runOnUiThread { onOk(result) }
            } catch (t: Throwable) {
                runOnUiThread {
                    appendLog("$command：${t.message ?: "失败"}")
                    if (!client.isConnected) setConnection(false, "Runtime 未运行", "点击重连")
                }
            }
        }
    }

    override fun onConnected(ready: String) = runOnUiThread {
        setConnection(true, "Runtime 已连接", ready.removePrefix("READY touch-runtime-v1 "))
        appendLog("已连接")
    }

    override fun onDisconnected(message: String) = runOnUiThread {
        setConnection(false, "Runtime 已断开", message)
        appendLog("断开：$message")
    }

    override fun onEvent(line: String) = runOnUiThread {
        appendLog(line)
        if (line.startsWith("PLAYBACK_DONE") || line.startsWith("PLAYBACK_ERROR")) {
            paused = false
            pauseButton.text = "暂停"
            refreshStatus()
        }
    }

    private fun setConnection(connected: Boolean, title: String, detail: String) {
        statusDot.background = circle(if (connected) green else Color.rgb(170, 175, 184))
        statusText.text = title
        detailText.text = detail
    }

    private fun appendLog(message: String) {
        val old = logText.text.toString().takeLast(600)
        logText.text = if (old.isBlank() || old == "等待 runtime…") message else "$old\n$message"
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(card, 18f, Color.rgb(230, 232, 236))
        setPadding(dp(17), dp(16), dp(17), dp(16))
        elevation = dp(1).toFloat()
    }

    private fun cardTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(ink)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(muted)
        setPadding(0, 0, dp(7), 0)
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        background = rounded(Color.rgb(245, 246, 248), 10f, Color.TRANSPARENT)
        setPadding(dp(8), 0, dp(4), 0)
    }

    private fun primaryButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        isAllCaps = false
        background = rounded(accent, 12f, Color.TRANSPARENT)
        setOnClickListener { click() }
    }

    private fun secondaryButton(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(ink)
        isAllCaps = false
        background = rounded(Color.rgb(242, 243, 246), 12f, Color.TRANSPARENT)
        setOnClickListener { click() }
    }

    private fun textButton(text: String, click: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(accent)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(7), dp(10), dp(7))
        setOnClickListener { click() }
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun circle(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun sectionGap() = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun defaultRecordingName(): String = "record_" + SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
}
