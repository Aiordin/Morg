package aiordin.morg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「连点 7 次」计数。
 *
 * 这里的断言对应两个真实风险：
 * 1. 误触 —— 零散点几下就进详情页，彩蛋变成陷阱；
 * 2. 卡死 —— 解锁后计数没归零，下一次点击又立刻触发。
 */
class SecretTapTest {

    private fun tapAt(counter: SecretTapCounter, ms: Long): TapResult = counter.tap(ms)

    @Test
    fun `counts down from the threshold`() {
        val counter = SecretTapCounter(requiredTaps = 3, resetAfterMs = 1_000)
        assertEquals(TapResult.Progress(2), tapAt(counter, 0))
        assertEquals(TapResult.Progress(1), tapAt(counter, 100))
        assertEquals(TapResult.Unlocked, tapAt(counter, 200))
    }

    @Test
    fun `fast taps reach the threshold`() {
        val counter = SecretTapCounter()
        val results = (0 until 7).map { counter.tap(it * 200L) }
        assertEquals(TapResult.Progress(1), results[5])
        assertEquals(TapResult.Unlocked, results[6])
    }

    @Test
    fun `remaining counts down 3 2 1 over the last three taps`() {
        // 界面的倒数提示就挂在这三个值上（`HINT_FROM_REMAINING = 3`），
        // 所以这里把整条剩余序列钉死：前三次不提示、最后三次倒数。
        val counter = SecretTapCounter()
        val remaining = (0 until 6).map { tap ->
            (counter.tap(tap * 200L) as TapResult.Progress).remaining
        }
        assertEquals(listOf(6, 5, 4, 3, 2, 1), remaining)
        assertEquals(TapResult.Unlocked, counter.tap(6 * 200L))
    }

    @Test
    fun `a long pause resets the count`() {
        val counter = SecretTapCounter(requiredTaps = 3, resetAfterMs = 500)
        counter.tap(0)
        counter.tap(100)
        // 停顿超过 500ms：前面两次不算数，从 1 重新开始。
        assertEquals(TapResult.Progress(2), tapAt(counter, 1_000))
    }

    @Test
    fun `pause exactly at the limit still counts`() {
        // 边界取「大于才重置」：手指点击间隔偶尔正好落在阈值上，不该被吃掉。
        val counter = SecretTapCounter(requiredTaps = 2, resetAfterMs = 500)
        counter.tap(0)
        assertEquals(TapResult.Unlocked, counter.tap(500))
    }

    @Test
    fun `unlocking clears the counter`() {
        val counter = SecretTapCounter(requiredTaps = 2, resetAfterMs = 10_000)
        counter.tap(0)
        assertEquals(TapResult.Unlocked, counter.tap(1))
        // 解锁后必须重新数满一轮，否则下一次点击会又进一次详情页。
        assertEquals(TapResult.Progress(1), counter.tap(2))
    }

    @Test
    fun `reset starts over`() {
        val counter = SecretTapCounter(requiredTaps = 3, resetAfterMs = 10_000)
        counter.tap(0)
        counter.tap(1)
        counter.reset()
        assertEquals(TapResult.Progress(2), counter.tap(2))
    }

    @Test
    fun `first tap ignores a zero clock`() {
        // uptimeMillis 的起点是 0，不能被当成「上次点击在很久以前」。
        val counter = SecretTapCounter(requiredTaps = 2, resetAfterMs = 1)
        assertEquals(TapResult.Progress(1), counter.tap(0))
        assertTrue(counter.tap(0) is TapResult.Unlocked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-positive threshold`() {
        SecretTapCounter(requiredTaps = 0)
    }
}
