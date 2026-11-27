package aiordin.morg.launch

import aiordin.morg.overlay.DockSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口模式的选择逻辑。
 *
 * 这是项目里最容易「看起来能用但其实选错」的地方：
 * 设备能力不同，同一个用户选择要落到不同的 windowingMode 上。
 */
class WindowModeTest {

    @Test
    fun `fullscreen never asks for a window mode`() {
        val mode = WindowMode.resolve(LaunchMode.Fullscreen, zoomSupported = true, freeformEnabled = true)
        assertEquals(WindowMode.FULLSCREEN, mode)
    }

    @Test
    fun `native window mode is preferred when available`() {
        // 有 ColorOS 小窗时选 100，而不是 5 —— 观感差别很大。
        val mode = WindowMode.resolve(LaunchMode.Window, zoomSupported = true, freeformEnabled = true)
        assertEquals(WindowMode.ZOOM, mode)
    }

    @Test
    fun `freeform is the fallback when there is no native window mode`() {
        val mode = WindowMode.resolve(LaunchMode.Window, zoomSupported = false, freeformEnabled = true)
        assertEquals(WindowMode.FREEFORM, mode)
    }

    @Test
    fun `window request degrades to fullscreen when nothing is supported`() {
        val mode = WindowMode.resolve(LaunchMode.Window, zoomSupported = false, freeformEnabled = false)
        assertEquals(WindowMode.FULLSCREEN, mode)
    }

    @Test
    fun `support reflects what the device can actually do`() {
        assertTrue(WindowMode.supports(LaunchMode.Fullscreen, false, false))
        assertFalse(WindowMode.supports(LaunchMode.Window, false, false))
        assertTrue(WindowMode.supports(LaunchMode.Window, false, true))
        assertTrue(WindowMode.supports(LaunchMode.Window, true, false))
    }

    @Test
    fun `native window mode sends no bounds because the system lays it out`() {
        val request = WindowLaunchDecision.request(
            mode = LaunchMode.Window,
            zoomSupported = true,
            freeformEnabled = true,
            screen = 1080 to 2400,
            side = DockSide.Right,
        )
        assertEquals(WindowMode.ZOOM, request.windowingMode)
        assertNull("zoom mode must not be given bounds", request.bounds)
    }

    @Test
    fun `freeform fallback sends bounds on the handle side`() {
        val right = WindowLaunchDecision.request(
            mode = LaunchMode.Window,
            zoomSupported = false,
            freeformEnabled = true,
            screen = 1080 to 2400,
            side = DockSide.Right,
        )
        val left = WindowLaunchDecision.request(
            mode = LaunchMode.Window,
            zoomSupported = false,
            freeformEnabled = true,
            screen = 1080 to 2400,
            side = DockSide.Left,
        )
        assertEquals(WindowMode.FREEFORM, right.windowingMode)
        val rightBounds = requireNotNull(right.bounds)
        val leftBounds = requireNotNull(left.bounds)
        assertTrue("right-side bounds should sit right of left-side", rightBounds.left > leftBounds.left)
        assertTrue("bounds must stay inside the screen", rightBounds.right <= 1080)
        assertTrue("bounds must stay inside the screen", leftBounds.left >= 0)
    }

    @Test
    fun `fullscreen request carries no bounds`() {
        val request = WindowLaunchDecision.request(
            mode = LaunchMode.Fullscreen,
            zoomSupported = false,
            freeformEnabled = false,
            screen = 1080 to 2400,
            side = DockSide.Right,
        )
        assertNull(request.bounds)
    }
}
