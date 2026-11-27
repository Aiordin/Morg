package aiordin.morg.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小窗自检：
 *
 * 1. 判定只看窗口是不是明显小于屏幕（状态栏、导航栏造成的几个百分点不算）；
 * 2. 实测优先 —— 自检成功就按能开算，失败就不再相信设置项。
 */
class WindowProbeTest {

    private val phone = WindowBounds(0, 0, 1080, 2414)

    @Test
    fun `zoom sized window counts as small`() {
        assertTrue(WindowProbe.isSmallWindow(WindowBounds(0, 0, 594, 966), phone))
    }

    @Test
    fun `fullscreen with system bars still counts as fullscreen`() {
        // 状态栏 + 导航栏约 120px，不应被当成小窗。
        assertFalse(WindowProbe.isSmallWindow(WindowBounds(0, 0, 1080, 2294), phone))
    }

    @Test
    fun `one narrow dimension is enough`() {
        assertTrue(WindowProbe.isSmallWindow(WindowBounds(0, 0, 820, 2414), phone))
    }

    @Test
    fun `unknown screen size is never small`() {
        assertFalse(WindowProbe.isSmallWindow(WindowBounds(0, 0, 0, 0), WindowBounds(0, 0, 0, 0)))
    }

    @Test
    fun `no record keeps the static hint`() {
        assertEquals(
            WindowSupport.FreeformOnly,
            WindowProbe.resolveSupport(WindowSupport.FreeformOnly, null),
        )
    }

    @Test
    fun `successful zoom probe means native window`() {
        assertEquals(
            WindowSupport.Ready,
            WindowProbe.resolveSupport(
                WindowSupport.Unavailable,
                WindowProbeRecord(WindowProbeResult.SmallWindow, WindowMode.ZOOM),
            ),
        )
    }

    @Test
    fun `successful freeform probe means freeform only`() {
        assertEquals(
            WindowSupport.FreeformOnly,
            WindowProbe.resolveSupport(
                WindowSupport.Unavailable,
                WindowProbeRecord(WindowProbeResult.SmallWindow, WindowMode.FREEFORM),
            ),
        )
    }

    @Test
    fun `failed probe overrides a promising setting`() {
        // 开发者选项开关读到「开」，实测却是全屏 —— 以实测为准。
        assertEquals(
            WindowSupport.Unavailable,
            WindowProbe.resolveSupport(
                WindowSupport.FreeformOnly,
                WindowProbeRecord(WindowProbeResult.Fullscreen, WindowMode.FREEFORM),
            ),
        )
    }
}
