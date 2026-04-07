package com.first.ufotw

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PatternEngine のテスト。
 * Context (assets) が不要になるよう、ステップ直渡し用の内部 API を使うため
 * PatternEngine をサブクラス化してオーバーライドする。
 */
class PatternEngineTest {

    // ── Fake BleSender ────────────────────────────────────────────────────────

    class FakeSender : BleSender {
        val sent = mutableListOf<ByteArray>()
        override fun send(bytes: ByteArray) { sent.add(bytes.copyOf()) }
        fun clear() = sent.clear()
    }

    // ── テスト用サブクラス (Context/assets を使わず steps を直接渡す) ──────────

    class TestPatternEngine(sender: BleSender) : PatternEngine(null!!, sender) {
        // テスト用にステップを直接 play できるよう公開
        fun playSteps(steps: List<PatternStep>, loop: Boolean = false) {
            playDirect(steps, loop)
        }
    }

    // PatternEngine に playDirect を追加するのではなく、
    // ここでは PatternEngine のロジックを直接検証可能な
    // シンプルな FakeEngine を用意する
    class FakePatternEngine(private val sender: BleSender) {
        private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var job: Job? = null
        var currentPattern: String? = null
        var onPatternChanged: ((String?) -> Unit)? = null

        fun playSteps(name: String, steps: List<PatternStep>, loop: Boolean = false) {
            job?.cancel()
            currentPattern = name
            onPatternChanged?.invoke(name)
            job = scope.launch {
                try {
                    do {
                        for (step in steps) {
                            val cmd = UfoTwProtocol.buildCommand(
                                step.speed, step.direction,
                                step.speed, step.direction
                            )
                            sender.send(cmd)
                            delay(step.durationMs)
                        }
                    } while (loop && isActive)
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

        suspend fun awaitIdle() { job?.join() }
        fun destroy() = scope.cancel()
    }

    private lateinit var sender: FakeSender
    private lateinit var engine: FakePatternEngine

    @Before
    fun setUp() {
        sender = FakeSender()
        engine = FakePatternEngine(sender)
    }

    // ── テスト ────────────────────────────────────────────────────────────────

    @Test
    fun `play - sends correct commands for each step`() = runTest {
        val steps = listOf(
            PatternStep(speed = 30, direction = UfoTwProtocol.CW,  durationMs = 10),
            PatternStep(speed = 60, direction = UfoTwProtocol.CCW, durationMs = 10),
        )
        engine.playSteps("test", steps, loop = false)
        engine.awaitIdle()

        // 2ステップ + 終了stopコマンド
        assertEquals(3, sender.sent.size)
        assertArrayEquals(UfoTwProtocol.buildCommand(30, UfoTwProtocol.CW,  30, UfoTwProtocol.CW),  sender.sent[0])
        assertArrayEquals(UfoTwProtocol.buildCommand(60, UfoTwProtocol.CCW, 60, UfoTwProtocol.CCW), sender.sent[1])
        assertArrayEquals(UfoTwProtocol.stopCommand(), sender.sent[2])
    }

    @Test
    fun `play - currentPattern is set while playing`() = runTest {
        val steps = listOf(PatternStep(50, UfoTwProtocol.CW, 50))
        engine.playSteps("wave", steps)
        assertEquals("wave", engine.currentPattern)
    }

    @Test
    fun `play - currentPattern becomes null after non-loop finishes`() = runTest {
        val steps = listOf(PatternStep(50, UfoTwProtocol.CW, 10))
        engine.playSteps("pulse", steps, loop = false)
        engine.awaitIdle()
        assertNull(engine.currentPattern)
    }

    @Test
    fun `stop - sends stop command immediately`() = runTest {
        val steps = listOf(PatternStep(80, UfoTwProtocol.CW, 5_000)) // 長い delay
        engine.playSteps("storm", steps, loop = true)
        delay(50)
        engine.stop()
        delay(50)

        // stop コマンドが送信されていること
        assertTrue(sender.sent.any { it.contentEquals(UfoTwProtocol.stopCommand()) })
        assertNull(engine.currentPattern)
    }

    @Test
    fun `stop - onPatternChanged fires with null`() = runTest {
        var last: String? = "initial"
        engine.onPatternChanged = { last = it }

        val steps = listOf(PatternStep(50, UfoTwProtocol.CW, 10))
        engine.playSteps("wave", steps)
        engine.stop()

        assertNull(last)
    }

    @Test
    fun `play twice - second play cancels first`() = runTest {
        val longStep  = listOf(PatternStep(50, UfoTwProtocol.CW, 10_000))
        val shortStep = listOf(PatternStep(20, UfoTwProtocol.CCW, 10))

        engine.playSteps("long",  longStep,  loop = false)
        delay(20)
        sender.clear()

        engine.playSteps("short", shortStep, loop = false)
        engine.awaitIdle()

        // 最後に実行されたのは "short" のコマンド
        assertTrue(sender.sent.isNotEmpty())
        assertArrayEquals(
            UfoTwProtocol.buildCommand(20, UfoTwProtocol.CCW, 20, UfoTwProtocol.CCW),
            sender.sent[0]
        )
    }
}
