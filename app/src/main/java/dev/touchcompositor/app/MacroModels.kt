package dev.touchcompositor.app

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

sealed class MacroStep {
    data class Tap(var x: Float, var y: Float, var holdMs: Long = 70L) : MacroStep()
    data class Swipe(
        var x1: Float,
        var y1: Float,
        var x2: Float,
        var y2: Float,
        var durationMs: Long = 450L,
    ) : MacroStep()
    data class Wait(var durationMs: Long = 500L) : MacroStep()
}

data class TouchMacro(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val steps: MutableList<MacroStep> = mutableListOf(),
)

class MacroStore(context: Context) {
    private val prefs = context.getSharedPreferences("touch_macros", Context.MODE_PRIVATE)

    fun load(): MutableList<TouchMacro> {
        val raw = prefs.getString("macros", null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { i -> decodeMacro(array.getJSONObject(i)) }
        }.getOrElse { mutableListOf() }
    }

    fun save(macros: List<TouchMacro>) {
        val array = JSONArray()
        macros.forEach { array.put(encodeMacro(it)) }
        prefs.edit().putString("macros", array.toString()).apply()
    }

    private fun encodeMacro(macro: TouchMacro): JSONObject = JSONObject().apply {
        put("id", macro.id)
        put("name", macro.name)
        put("steps", JSONArray().apply {
            macro.steps.forEach { step ->
                put(JSONObject().apply {
                    when (step) {
                        is MacroStep.Tap -> {
                            put("type", "tap")
                            put("x", step.x.toDouble())
                            put("y", step.y.toDouble())
                            put("hold", step.holdMs)
                        }
                        is MacroStep.Swipe -> {
                            put("type", "swipe")
                            put("x1", step.x1.toDouble())
                            put("y1", step.y1.toDouble())
                            put("x2", step.x2.toDouble())
                            put("y2", step.y2.toDouble())
                            put("duration", step.durationMs)
                        }
                        is MacroStep.Wait -> {
                            put("type", "wait")
                            put("duration", step.durationMs)
                        }
                    }
                })
            }
        })
    }

    private fun decodeMacro(obj: JSONObject): TouchMacro {
        val steps = mutableListOf<MacroStep>()
        val array = obj.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until array.length()) {
            val s = array.getJSONObject(i)
            when (s.getString("type")) {
                "tap" -> steps += MacroStep.Tap(
                    s.getDouble("x").toFloat(),
                    s.getDouble("y").toFloat(),
                    s.optLong("hold", 70L),
                )
                "swipe" -> steps += MacroStep.Swipe(
                    s.getDouble("x1").toFloat(), s.getDouble("y1").toFloat(),
                    s.getDouble("x2").toFloat(), s.getDouble("y2").toFloat(),
                    s.optLong("duration", 450L),
                )
                "wait" -> steps += MacroStep.Wait(s.optLong("duration", 500L))
            }
        }
        return TouchMacro(
            id = obj.optString("id", UUID.randomUUID().toString()),
            name = obj.optString("name", "宏"),
            steps = steps,
        )
    }
}

class MacroRunner(private val client: RuntimeClient) {
    interface Listener {
        fun onMacroStarted(name: String) {}
        fun onMacroFinished(name: String, stopped: Boolean, error: String?) {}
    }

    @Volatile var listener: Listener? = null
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    val isRunning: Boolean get() = thread?.isAlive == true

    @Synchronized
    fun start(macro: TouchMacro) {
        check(!isRunning) { "macro already running" }
        stopRequested.set(false)
        thread = Thread({ runMacro(macro) }, "touch-macro-runner").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopRequested.set(true)
        runCatching { client.request("RELEASE_ALL", 1000) }
    }

    private fun runMacro(macro: TouchMacro) {
        var error: String? = null
        listener?.onMacroStarted(macro.name)
        try {
            if (!client.isConnected) client.connect()
            val pointerId = 10_001
            macro.steps.forEach { step ->
                if (stopRequested.get()) return@forEach
                when (step) {
                    is MacroStep.Tap -> {
                        client.request("DOWN $pointerId ${step.x} ${step.y}")
                        sleepInterruptibly(step.holdMs)
                        if (!stopRequested.get()) client.request("UP $pointerId ${step.x} ${step.y}")
                    }
                    is MacroStep.Swipe -> {
                        client.request("DOWN $pointerId ${step.x1} ${step.y1}")
                        val frames = ceil(step.durationMs / 16.0).toInt().coerceAtLeast(1)
                        val started = SystemClock.uptimeMillis()
                        for (i in 1..frames) {
                            if (stopRequested.get()) break
                            val t = i.toFloat() / frames.toFloat()
                            val x = step.x1 + (step.x2 - step.x1) * t
                            val y = step.y1 + (step.y2 - step.y1) * t
                            client.request("MOVE $pointerId $x $y")
                            val due = started + (step.durationMs * i / frames)
                            val remain = due - SystemClock.uptimeMillis()
                            if (remain > 0) sleepInterruptibly(remain)
                        }
                        if (!stopRequested.get()) client.request("UP $pointerId ${step.x2} ${step.y2}")
                    }
                    is MacroStep.Wait -> sleepInterruptibly(step.durationMs)
                }
            }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        } finally {
            if (stopRequested.get() || error != null) runCatching { client.request("RELEASE_ALL", 1000) }
            listener?.onMacroFinished(macro.name, stopRequested.get(), error)
        }
    }

    private fun sleepInterruptibly(ms: Long) {
        val end = SystemClock.uptimeMillis() + ms.coerceAtLeast(0)
        while (!stopRequested.get()) {
            val remain = end - SystemClock.uptimeMillis()
            if (remain <= 0) return
            Thread.sleep(minOf(remain, 25L))
        }
    }
}
