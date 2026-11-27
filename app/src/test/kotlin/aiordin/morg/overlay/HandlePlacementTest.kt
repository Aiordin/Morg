package aiordin.morg.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手柄位置的吸附与夹紧。
 *
 * 这些是纯函数，不需要 Robolectric —— 位置算错会让手柄跑到屏幕外或者贴不上边，
 * 是用户一眼能看出来的缺陷，值得逐个钉住。
 */
class HandlePlacementTest {

    @Test
    fun `drags left of center snap to left edge`() {
        val p = HandlePlacementMath.snap(centerX = 100f, centerY = 500f, width = 1080, height = 2400)
        assertEquals(DockSide.Left, p.side)
    }

    @Test
    fun `drags right of center snap to right edge`() {
        val p = HandlePlacementMath.snap(centerX = 900f, centerY = 500f, width = 1080, height = 2400)
        assertEquals(DockSide.Right, p.side)
    }

    @Test
    fun `exactly at center goes right`() {
        // 中线没有「中间」这个选项，统一归到右侧，避免中线附近抖动。
        val p = HandlePlacementMath.snap(centerX = 540f, centerY = 500f, width = 1080, height = 2400)
        assertEquals(DockSide.Right, p.side)
    }

    @Test
    fun `vertical ratio is clamped away from screen edges`() {
        val top = HandlePlacementMath.snap(centerX = 900f, centerY = 0f, width = 1080, height = 2400)
        val bottom = HandlePlacementMath.snap(centerX = 900f, centerY = 2400f, width = 1080, height = 2400)
        assertEquals(HandlePlacementMath.EDGE_MARGIN_RATIO, top.verticalRatio, 1e-6f)
        assertEquals(1f - HandlePlacementMath.EDGE_MARGIN_RATIO, bottom.verticalRatio, 1e-6f)
    }

    @Test
    fun `center px hugs the edge the handle is on`() {
        val left = HandlePlacementMath.centerPx(
            HandlePlacement(DockSide.Left, 0.5f),
            screenWidth = 1080,
            screenHeight = 2400,
            handleWidthPx = 36,
        )
        val right = HandlePlacementMath.centerPx(
            HandlePlacement(DockSide.Right, 0.5f),
            screenWidth = 1080,
            screenHeight = 2400,
            handleWidthPx = 36,
        )
        assertEquals(18f, left.first, 1e-6f)
        assertEquals(1062f, right.first, 1e-6f)
        assertEquals(1200f, left.second, 1e-6f)
    }

    @Test
    fun `round trip keeps the handle inside the screen`() {
        val placement = HandlePlacement(DockSide.Right, 0.9f)
        val (cx, cy) = HandlePlacementMath.centerPx(placement, 1080, 2400, 36)
        val topLeftX = cx - 18f
        val topLeftY = cy - 96f
        assertTrue("handle must stay on screen: $topLeftX,$topLeftY", topLeftX >= 0f)
        assertTrue("handle must stay on screen: $topLeftX,$topLeftY", topLeftX + 36f <= 1080f)
        assertTrue("handle must stay on screen: $topLeftX,$topLeftY", topLeftY >= 0f)
        assertTrue("handle must stay on screen: $topLeftX,$topLeftY", topLeftY + 192f <= 2400f)
    }

    @Test
    fun `custom edge margin is respected when clamping`() {
        assertEquals(0.2f, HandlePlacementMath.clampVertical(0.2f), 1e-6f)
        assertEquals(0.96f, HandlePlacementMath.clampVertical(2f), 1e-6f)
    }

    // —— 横竖屏切换 ——

    @Test
    fun `ratio maps onto the current height in both orientations`() {
        val placement = HandlePlacement(DockSide.Right, 0.5f)
        // 竖屏 1080x2414：中心 1207，手柄高 192 → 上沿 1111
        assertEquals(1111, HandlePlacementMath.topPx(placement, 2414, 192))
        // 横屏 2414x1080：同一个比例在新高度上重算，手柄仍然居中
        assertEquals(444, HandlePlacementMath.topPx(placement, 1080, 192))
    }

    @Test
    fun `landscape keeps the whole handle on screen at extreme ratios`() {
        // 横屏只有 1080 高：4% 的边距（~43px）比手柄半高（96px）小，
        // 只按中心点算会让手柄出屏，所以必须按手柄本身夹紧。
        val top = HandlePlacementMath.topPx(HandlePlacement(DockSide.Right, 0f), 1080, 192)
        val bottom = HandlePlacementMath.topPx(HandlePlacement(DockSide.Right, 1f), 1080, 192)
        assertEquals(0, top)
        assertEquals(1080 - 192, bottom)
    }

    @Test
    fun `handle taller than the short screen still stays anchored at the top`() {
        val top = HandlePlacementMath.topPx(HandlePlacement(DockSide.Left, 0.5f), 120, 192)
        assertEquals(0, top)
    }

    @Test
    fun `rotating keeps the same proportional center`() {
        val placement = HandlePlacement(DockSide.Left, 0.72f)
        listOf(2414, 1080).forEach { height ->
            val center = HandlePlacementMath.topPx(placement, height, 192) + 192 / 2f
            assertEquals(0.72f * height, center, 2f)
        }
    }
}
