package aiordin.morg.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉式手柄的几何与手势换算。
 *
 * 这里的断言都是「手指不能滑出窗口 / 位移不能被窗口变宽吃掉」的数学前提 ——
 * 一旦破了，手势会中途丢给系统，实测后果是前台应用被返回手势关掉，
 * 或者抽屉看起来「完全没有变化」。
 */
class HandleWindowMathTest {

    private val base = 36       // 12dp @ 480dpi
    private val maxPull = 192   // 64dp
    private val overshoot = 48  // 16dp
    private val slack = 72      // 24dp

    @Test
    fun `pull follows the finger one to one below the cap`() {
        assertEquals(0f, HandleWindowMath.pullForDrag(0f, maxPull, overshoot), 1e-6f)
        assertEquals(60f, HandleWindowMath.pullForDrag(60f, maxPull, overshoot), 1e-6f)
        assertEquals(192f, HandleWindowMath.pullForDrag(192f, maxPull, overshoot), 1e-6f)
    }

    @Test
    fun `pull overshoots elastically after the cap`() {
        // 超过 64dp 后按 0.3 的阻尼系数继续外扩，最多 16dp。
        assertEquals(192f + 101f * 0.3f, HandleWindowMath.pullForDrag(293f, maxPull, overshoot), 0.01f)
        assertEquals(240f, HandleWindowMath.pullForDrag(10_000f, maxPull, overshoot), 1e-6f)
    }

    @Test
    fun `outward drag never pulls the drawer`() {
        assertEquals(0f, HandleWindowMath.pullForDrag(-300f, maxPull, overshoot), 1e-6f)
    }

    @Test
    fun `trigger only fires at or past the threshold`() {
        val threshold = 84 // 28dp
        assertFalse(HandleWindowMath.isTriggered(83.9f, threshold))
        assertTrue(HandleWindowMath.isTriggered(84f, threshold))
        assertTrue(HandleWindowMath.isTriggered(200f, threshold))
    }

    @Test
    fun `envelope covers handle, full drawer and finger slack`() {
        // 窗口在手势期间只撑开一次，所以必须覆盖抽屉 + 弹性 + 继续内滑的余量。
        assertEquals(
            base + maxPull + overshoot + slack,
            HandleWindowMath.envelopeWidth(
                baseWidthPx = base,
                maxPullPx = maxPull,
                overshootMaxPx = overshoot,
                slackPx = slack,
            ),
        )
        assertTrue(HandleWindowMath.envelopeWidth(base, maxPull, overshoot, slack) > maxPull + base)
    }

    @Test
    fun `inward distance is measured on the raw screen axis`() {
        // 旧实现累加窗口内 positionChange：右侧窗口变宽时左边缘左移，
        // 同一根手指的窗口内 x 也跟着变，增量被抵消，抽屉看起来“不动”。
        // 手势必须用 raw 屏幕坐标，窗口几何怎么变都不参与计算。
        val downRawX = 1062f
        listOf(0f, 7f, 14f, 120f, 300f).forEach { delta ->
            assertEquals(
                "delta=$delta",
                delta,
                HandleWindowMath.inwardDx(downRawX, downRawX - delta, DockSide.Right),
                1e-3f,
            )
        }
    }

    @Test
    fun `left side mirrors the inward direction`() {
        assertEquals(30f, HandleWindowMath.inwardDx(60f, 90f, DockSide.Left), 1e-6f)
        assertEquals(30f, HandleWindowMath.inwardDx(90f, 60f, DockSide.Right), 1e-6f)
        assertEquals(-15f, HandleWindowMath.inwardDx(60f, 45f, DockSide.Left), 1e-6f)
    }

    @Test
    fun `right side window hugs the right edge`() {
        assertEquals(1080 - 36, HandleWindowMath.leftForWidth(1080, 36, DockSide.Right))
        assertEquals(1080 - 348, HandleWindowMath.leftForWidth(1080, 348, DockSide.Right))
    }

    @Test
    fun `left side window hugs the left edge`() {
        assertEquals(0, HandleWindowMath.leftForWidth(1080, 36, DockSide.Left))
        assertEquals(0, HandleWindowMath.leftForWidth(1080, 348, DockSide.Left))
    }

    @Test
    fun `landscape horizontal edges are used in full`() {
        // 横屏时挖孔在左右短边上。用户明确要求不避让：避开就是一条 121px 的空白，
        // 手柄看起来没贴住屏幕。所以左右一律顶到 0 / screenWidth。
        assertEquals(2414 - 36, HandleWindowMath.leftForWidth(2414, 36, DockSide.Right))
        assertEquals(0, HandleWindowMath.leftForWidth(2414, 348, DockSide.Left))
    }
}
