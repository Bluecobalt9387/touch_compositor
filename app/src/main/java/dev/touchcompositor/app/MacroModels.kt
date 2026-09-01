package dev.touchcompositor.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

enum class MacroTriggerMode { CLICK_ONCE, HOLD_REPEAT }

data class MacroTrigger(
    var mode: MacroTriggerMode = MacroTriggerMode.CLICK_ONCE,
    /** Normalized trigger-button center in the full logical display. */
    var x: Float = 0.86f,
    var y: Float = 0.45f,
    /** Visual trigger diameter. */
    var sizeDp: Float = 46f,
)

sealed class MacroAction {
    /** Coordinates are normalized to the current logical screen. */
    data class Tap(var x: Float, var y: Float, var holdMs: Long = 70L) : MacroAction()

    data class Drag(
        var x1: Float,
        var y1: Float,
        var x2: Float,
        var y2: Float,
        var durationMs: Long = 450L,
    ) : MacroAction()

    data class Wait(var durationMs: Long = 500L) : MacroAction()

    data class Recording(
        var recordingName: String,
        var speed: Float = 1f,
    ) : MacroAction()
}

data class TouchMacro(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var trigger: MacroTrigger = MacroTrigger(),
    val actions: MutableList<MacroAction> = mutableListOf(),
)

data class MacroProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val macros: MutableList<TouchMacro> = mutableListOf(),
)

/** Profile-aware macro persistence using canonical global-screen coordinates. */
class MacroStore(context: Context) {
    private val prefs = context.getSharedPreferences("touch_macros_v2", Context.MODE_PRIVATE)
    private val profilesKey = "profiles"
    private val activeProfileKey = "active_profile"

    fun loadProfiles(): MutableList<MacroProfile> {
        val stored = prefs.getString(profilesKey, null)
        if (stored != null) {
            return runCatching {
                val array = JSONArray(stored)
                MutableList(array.length()) { i -> decodeProfile(array.getJSONObject(i)) }
            }.getOrElse { mutableListOf() }
        }
        val profiles = mutableListOf(MacroProfile(name = "配置 1"))
        saveProfiles(profiles)
        saveActiveProfileId(profiles.first().id)
        return profiles
    }

    fun saveProfiles(profiles: List<MacroProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("macros", JSONArray().apply { profile.macros.forEach { put(encodeMacro(it)) } })
            })
        }
        prefs.edit().putString(profilesKey, array.toString()).apply()
    }

    fun activeProfileId(): String? = prefs.getString(activeProfileKey, null)

    fun saveActiveProfileId(id: String) {
        prefs.edit().putString(activeProfileKey, id).apply()
    }

    private fun decodeProfile(obj: JSONObject): MacroProfile {
        val macrosArray = obj.optJSONArray("macros") ?: JSONArray()
        val macros = MutableList(macrosArray.length()) { i -> decodeMacro(macrosArray.getJSONObject(i)) }
        return MacroProfile(
            id = obj.optString("id", UUID.randomUUID().toString()),
            name = obj.optString("name", "配置"),
            macros = macros,
        )
    }

    private fun encodeMacro(macro: TouchMacro): JSONObject = JSONObject().apply {
        put("id", macro.id)
        put("name", macro.name)
        put("trigger", JSONObject().apply {
            put("mode", macro.trigger.mode.name)
            put("x", macro.trigger.x.toDouble())
            put("y", macro.trigger.y.toDouble())
            put("sizeDp", macro.trigger.sizeDp.toDouble())
        })
        put("actions", JSONArray().apply {
            macro.actions.forEach { action ->
                put(JSONObject().apply {
                    when (action) {
                        is MacroAction.Tap -> {
                            put("type", "tap")
                            put("x", action.x.toDouble())
                            put("y", action.y.toDouble())
                            put("hold", action.holdMs)
                        }
                        is MacroAction.Drag -> {
                            put("type", "drag")
                            put("x1", action.x1.toDouble())
                            put("y1", action.y1.toDouble())
                            put("x2", action.x2.toDouble())
                            put("y2", action.y2.toDouble())
                            put("duration", action.durationMs)
                        }
                        is MacroAction.Wait -> {
                            put("type", "wait")
                            put("duration", action.durationMs)
                        }
                        is MacroAction.Recording -> {
                            put("type", "recording")
                            put("name", action.recordingName)
                            put("speed", action.speed.toDouble())
                        }
                    }
                })
            }
        })
    }

    private fun decodeMacro(obj: JSONObject): TouchMacro {
        val triggerObj = obj.optJSONObject("trigger") ?: JSONObject()
        val trigger = MacroTrigger(
            mode = runCatching { MacroTriggerMode.valueOf(triggerObj.optString("mode", MacroTriggerMode.CLICK_ONCE.name)) }
                .getOrDefault(MacroTriggerMode.CLICK_ONCE),
            x = triggerObj.optDouble("x", 0.86).toFloat().coerceIn(0f, 1f),
            y = triggerObj.optDouble("y", 0.45).toFloat().coerceIn(0f, 1f),
            sizeDp = triggerObj.optDouble("sizeDp", 46.0).toFloat().coerceIn(28f, 96f),
        )
        val actions = mutableListOf<MacroAction>()
        val array = obj.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until array.length()) {
            val a = array.getJSONObject(i)
            when (a.optString("type")) {
                "tap" -> actions += MacroAction.Tap(
                    a.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                    a.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                    a.optLong("hold", 70L).coerceIn(20L, 5_000L),
                )
                "drag" -> actions += MacroAction.Drag(
                    a.optDouble("x1", 0.35).toFloat().coerceIn(0f, 1f),
                    a.optDouble("y1", 0.5).toFloat().coerceIn(0f, 1f),
                    a.optDouble("x2", 0.65).toFloat().coerceIn(0f, 1f),
                    a.optDouble("y2", 0.5).toFloat().coerceIn(0f, 1f),
                    a.optLong("duration", 450L).coerceIn(50L, 30_000L),
                )
                "wait" -> actions += MacroAction.Wait(a.optLong("duration", 500L).coerceIn(0L, 60_000L))
                "recording" -> {
                    val name = a.optString("name", "")
                    if (name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                        actions += MacroAction.Recording(
                            name,
                            a.optDouble("speed", 1.0).toFloat().coerceIn(0.1f, 8f),
                        )
                    }
                }
            }
        }
        return TouchMacro(
            id = obj.optString("id", UUID.randomUUID().toString()),
            name = obj.optString("name", "宏"),
            trigger = trigger,
            actions = actions,
        )
    }
}

/** Sequential macro executor. Recording playback uses a second control connection so HOLD_REPEAT can stop immediately. */
class MacroRunner : AutoCloseable {
    interface Listener {
        fun onMacroStarted(name: String) {}
        fun onMacroFinished(name: String, stopped: Boolean, error: String?) {}
    }

    @Volatile var listener: Listener? = null
    private val stopRequested = AtomicBoolean(false)
    private val execClient = RuntimeClient()
    private val controlClient = RuntimeClient()
    @Volatile private var thread: Thread? = null

    val isRunning: Boolean get() = thread?.isAlive == true

    @Synchronized
    fun start(macro: TouchMacro, repeat: Boolean = false) {
        check(!isRunning) { "macro already running" }
        check(macro.actions.isNotEmpty()) { "macro has no actions" }
        stopRequested.set(false)
        thread = Thread({ runMacro(macro, repeat) }, "touch-macro-runner").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopRequested.set(true)
        ensureControlClient()
        runCatching { controlClient.request("STOP_PLAYBACK", 1000) }
        runCatching { controlClient.request("RELEASE_ALL", 1000) }
    }

    private fun runMacro(macro: TouchMacro, repeat: Boolean) {
        var error: String? = null
        listener?.onMacroStarted(macro.name)
        try {
            ensureExecClient()
            do {
                for (action in macro.actions.toList()) {
                    if (stopRequested.get()) break
                    execute(action)
                }
                if (!repeat || stopRequested.get()) break
            } while (true)
        } catch (t: Throwable) {
            if (!stopRequested.get()) error = t.message ?: t.javaClass.simpleName
        } finally {
            if (stopRequested.get() || error != null) {
                ensureControlClient()
                runCatching { controlClient.request("STOP_PLAYBACK", 1000) }
                runCatching { controlClient.request("RELEASE_ALL", 1000) }
            }
            listener?.onMacroFinished(macro.name, stopRequested.get(), error)
        }
    }

    private fun execute(action: MacroAction) {
        when (action) {
            is MacroAction.Tap -> {
                val x = action.x.coerceIn(0f, 1f)
                val y = action.y.coerceIn(0f, 1f)
                val pointerId = 10_001
                execClient.request("DOWN_N $pointerId $x $y")
                sleepInterruptibly(action.holdMs)
                if (!stopRequested.get()) execClient.request("UP_N $pointerId $x $y")
            }
            is MacroAction.Drag -> {
                val x1 = action.x1.coerceIn(0f, 1f)
                val y1 = action.y1.coerceIn(0f, 1f)
                val x2 = action.x2.coerceIn(0f, 1f)
                val y2 = action.y2.coerceIn(0f, 1f)
                val pointerId = 10_001
                execClient.request("DOWN_N $pointerId $x1 $y1")
                val frames = ceil(action.durationMs / 16.0).toInt().coerceAtLeast(1)
                val started = SystemClock.uptimeMillis()
                for (i in 1..frames) {
                    if (stopRequested.get()) break
                    val t = i.toFloat() / frames.toFloat()
                    val x = x1 + (x2 - x1) * t
                    val y = y1 + (y2 - y1) * t
                    execClient.request("MOVE_N $pointerId $x $y")
                    val due = started + action.durationMs * i / frames
                    val remain = due - SystemClock.uptimeMillis()
                    if (remain > 0) sleepInterruptibly(remain)
                }
                if (!stopRequested.get()) execClient.request("UP_N $pointerId $x2 $y2")
            }
            is MacroAction.Wait -> sleepInterruptibly(action.durationMs)
            is MacroAction.Recording -> {
                val speed = action.speed.coerceIn(0.1f, 8f)
                execClient.request("PLAY_SYNC ${action.recordingName} $speed 1", 3_600_000)
            }
        }
    }


    private fun ensureExecClient() {
        if (!execClient.isConnected) execClient.connect()
    }

    private fun ensureControlClient() {
        if (!controlClient.isConnected) runCatching { controlClient.connect() }
    }

    private fun sleepInterruptibly(ms: Long) {
        val end = SystemClock.uptimeMillis() + ms.coerceAtLeast(0)
        while (!stopRequested.get()) {
            val remain = end - SystemClock.uptimeMillis()
            if (remain <= 0) return
            Thread.sleep(minOf(remain, 25L))
        }
    }

    override fun close() {
        stop()
        execClient.close()
        controlClient.close()
    }
}
