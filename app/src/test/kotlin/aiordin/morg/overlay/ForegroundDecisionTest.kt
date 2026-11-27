package aiordin.morg.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台判定的三条规则：
 *
 * 1. 普通应用在前台 → 显示
 * 2. 弱前台（桌面/系统 UI/本应用）在前台 → **立即隐藏**（哪怕还有 RESUMED 的应用实例）
 * 3. 等确认那个实例不是正在退场 → 若它是小窗（一直 RESUMED）→ 重新显示
 *
 * 断言来自 2026-10-30 真机实测。规则 2 的「立即」是关键：退桌面的全屏实例
 * 要 1.1s 才 PAUSED，不能因此让手柄挂在桌面上。
 */
class ForegroundDecisionTest {

    private val app = "com.example.app"
    private val launcher = "com.android.launcher"
    private val morg = "aiordin.morg"

    private val isWeak: (String) -> Boolean = { it == launcher || it == morg }

    private fun resumed(pkg: String, at: Long, id: Int = 1) =
        ForegroundEvent(pkg, at, id)

    private fun visible(pkg: String, id: Int) = LiveInstance(pkg, id, visible = true)
    private fun hidden(pkg: String, id: Int) = LiveInstance(pkg, id, visible = false)

    private fun decide(
        now: Long,
        lastStrong: String? = null,
        latestResumed: ForegroundEvent? = null,
        liveInstances: List<LiveInstance> = emptyList(),
    ) = ForegroundDecision.decide(now, lastStrong, isWeak, latestResumed, liveInstances)

    @Test
    fun `app in foreground shows the handle`() {
        val result = decide(
            now = 2_000,
            latestResumed = resumed(app, 1_000),
            liveInstances = listOf(visible(app, 1)),
        )
        assertEquals(app, result.foreground)
        assertEquals(app, result.lastStrong)
    }

    @Test
    fun `launcher hides the handle immediately even if an app is still RESUMED`() {
        // **核心回归**：上滑退桌面时，被退走的全屏实例此刻仍 RESUMED
        // （要 1.1s 才 PAUSED）。必须立刻隐藏，不能挂在桌面上。
        val result = decide(
            now = 1_050,
            lastStrong = app,
            latestResumed = resumed(launcher, 1_000, id = 9),
            liveInstances = listOf(visible(launcher, 9), visible(app, 1)),
        )
        assertEquals(launcher, result.foreground)
        // 还在窗口期内，应当快轮询 —— 可能马上确认成小窗。
        assertTrue(result.settling)
    }

    @Test
    fun `window is shown again once the app instance never leaves`() {
        // 小窗：弱前台 RESUMED 之后应用实例一直 RESUMED。
        // 等过 WINDOW_SETTLE_MS 就确认它是小窗 → 重新显示。
        val result = decide(
            now = 1_000 + ForegroundDecision.WINDOW_SETTLE_MS,
            lastStrong = app,
            latestResumed = resumed(launcher, 1_000, id = 9),
            liveInstances = listOf(visible(launcher, 9), visible(app, 1)),
        )
        assertEquals(app, result.foreground)
        assertFalse(result.settling)
    }

    @Test
    fun `fullscreen leaving to the launcher stays hidden after the instance departs`() {
        // 真退场：等确认期过后，app 实例已经不可见 → 保持隐藏。
        val result = decide(
            now = 1_000 + ForegroundDecision.WINDOW_SETTLE_MS,
            lastStrong = app,
            latestResumed = resumed(launcher, 1_000, id = 9),
            liveInstances = listOf(visible(launcher, 9), hidden(app, 1)),
        )
        assertEquals(launcher, result.foreground)
    }

    @Test
    fun `paused instance is not a window`() {
        val result = decide(
            now = 5_000,
            lastStrong = app,
            latestResumed = resumed(launcher, 1_000, id = 9),
            liveInstances = listOf(visible(launcher, 9), hidden(app, 1)),
        )
        assertEquals(launcher, result.foreground)
    }

    @Test
    fun `gesture preview cancel restores the previous app`() {
        // 预览划回：桌面 RESUMED 后又自己退场（实例已不可见），app 全程无事件。
        val result = decide(
            now = 1_900,
            lastStrong = app,
            latestResumed = resumed(launcher, 1_000, id = 9),
            liveInstances = listOf(hidden(launcher, 9)),
        )
        assertEquals(app, result.foreground)
        assertEquals(app, result.lastStrong)
    }

    @Test
    fun `own app hides the handle`() {
        val result = decide(
            now = 3_000,
            lastStrong = app,
            latestResumed = resumed(morg, 1_000, id = 5),
            liveInstances = listOf(visible(morg, 5)),
        )
        assertEquals(morg, result.foreground)
    }

    @Test
    fun `settling clears after the settle window`() {
        assertFalse(
            decide(
                now = 1_000 + ForegroundDecision.WINDOW_SETTLE_MS + 100,
                lastStrong = app,
                latestResumed = resumed(launcher, 1_000, id = 9),
                liveInstances = listOf(visible(launcher, 9)),
            ).settling,
        )
    }

    @Test
    fun `no events yet falls back to the cached strong foreground`() {
        val result = decide(now = 5_000, lastStrong = app, latestResumed = null)
        assertEquals(app, result.foreground)
        assertEquals(app, result.lastStrong)
    }

    @Test
    fun `unknown foreground stays null`() {
        assertEquals(null, decide(now = 1_000, lastStrong = null, latestResumed = null).foreground)
    }
}
