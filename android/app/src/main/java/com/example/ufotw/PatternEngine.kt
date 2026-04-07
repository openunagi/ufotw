package com.first.ufotw

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject

data class PatternStep(val speed: Int, val direction: Int, val durationMs: Long)
data class PatternMeta(val name: String, val emoji: String, val description: String)

class PatternEngine(
    private val context: Context,
    val sender: BleSender
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    var currentPattern: String? = null
        private set
    var onPatternChanged: ((String?) -> Unit)? = null
    var onStepPlaying: ((speed1: Int, dir1: Int, speed2: Int, dir2: Int) -> Unit)? = null
    var isMirrorMode: () -> Boolean = { false }

    fun listPatterns(): List<String> =
        context.assets.list("patterns")
            ?.filter { it.endsWith(".json") }
            ?.map { it.removeSuffix(".json") }
            ?.sorted()
            ?: emptyList()

    fun listPatternMeta(): List<PatternMeta> =
        context.assets.list("patterns")
            ?.filter { it.endsWith(".json") }
            ?.map { it.removeSuffix(".json") }
            ?.sorted()
            ?.map { name ->
                val text = context.assets.open("patterns/$name.json").bufferedReader().readText()
                val obj = JSONObject(text)
                PatternMeta(name, obj.optString("emoji", "▶"), obj.optString("description", ""))
            } ?: emptyList()

    fun play(name: String) {
        val (steps, loop) = loadPattern(name)
        playSpec(name, steps, loop)
    }

    fun playSpec(name: String, steps: List<PatternStep>, loop: Boolean) {
        stop()
        currentPattern = name
        onPatternChanged?.invoke(name)
        runSequence(steps, loop)
    }

    private fun runSequence(steps: List<PatternStep>, loop: Boolean) {
        job = scope.launch {
            try {
                do {
                    for (step in steps) {
                        val dir2 = if (isMirrorMode()) 1 - step.direction else step.direction
                        val cmd = UfoTwProtocol.buildCommand(
                            step.speed, step.direction,
                            step.speed, dir2
                        )
                        sender.send(cmd)
                        withContext(Dispatchers.Main) {
                            onStepPlaying?.invoke(step.speed, step.direction, step.speed, dir2)
                        }
                        delay(step.durationMs)
                    }
                } while (loop && isActive)
                // 非ループパターン終了
                sender.send(UfoTwProtocol.stopCommand())
                withContext(Dispatchers.Main) {
                    currentPattern = null
                    onPatternChanged?.invoke(null)
                }
            } catch (e: CancellationException) {
                sender.send(UfoTwProtocol.stopCommand())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        currentPattern = null
        onPatternChanged?.invoke(null)
    }

    fun destroy() = scope.cancel()

    private fun loadPattern(name: String): Pair<List<PatternStep>, Boolean> {
        val text = context.assets.open("patterns/$name.json").bufferedReader().readText()
        val obj = JSONObject(text)
        val loop = obj.optBoolean("loop", false)
        val arr = obj.getJSONArray("steps")
        val steps = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            PatternStep(
                speed = s.getInt("speed"),
                direction = s.getInt("direction"),
                durationMs = (s.getDouble("duration") * 1000).toLong()
            )
        }
        return steps to loop
    }
}
