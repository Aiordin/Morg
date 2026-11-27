package aiordin.morg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导规则：
 *
 * 1. 浮窗与使用情况访问是必需项，通知可选；
 * 2. 小窗这一步以**自检成功**为完成条件，不看开发者选项里的开关；
 * 3. Android 13 以下没有通知权限，步骤列表里不能出现它；
 * 4. 完成过一次就不再引导（撤销权限也不重新弹）。
 */
class OnboardingTest {

    private val allGranted = PermissionStatus(
        overlay = true,
        usageAccess = true,
        notifications = true,
    )

    @Test
    fun `steps order starts with required permissions`() {
        assertEquals(
            listOf(
                OnboardingStep.Overlay,
                OnboardingStep.UsageAccess,
                OnboardingStep.Notifications,
            ),
            Onboarding.steps(notificationsAvailable = true, windowConfirmed = true),
        )
    }

    @Test
    fun `window step stays until the probe actually succeeds`() {
        assertEquals(
            listOf(
                OnboardingStep.Overlay,
                OnboardingStep.UsageAccess,
                OnboardingStep.FreeformWindows,
                OnboardingStep.Notifications,
            ),
            Onboarding.steps(notificationsAvailable = true, windowConfirmed = false),
        )
    }

    @Test
    fun `notifications step is dropped before android 13`() {
        assertEquals(
            listOf(OnboardingStep.Overlay, OnboardingStep.UsageAccess),
            Onboarding.steps(notificationsAvailable = false, windowConfirmed = true),
        )
    }

    @Test
    fun `first launch shows onboarding`() {
        assertTrue(Onboarding.shouldShow(completed = false))
        assertFalse(Onboarding.shouldShow(completed = true))
    }

    @Test
    fun `ready needs overlay and usage access`() {
        assertTrue(Onboarding.isReady(allGranted))
        assertFalse(Onboarding.isReady(PermissionStatus(overlay = true)))
        assertFalse(Onboarding.isReady(PermissionStatus(usageAccess = true)))
        assertTrue(
            Onboarding.isReady(
                PermissionStatus(overlay = true, usageAccess = true),
            ),
        )
    }

    @Test
    fun `window and notifications never block readiness`() {
        assertTrue(
            Onboarding.isReady(
                PermissionStatus(overlay = true, usageAccess = true, notifications = false),
            ),
        )
    }

    @Test
    fun `missing required keeps onboarding order`() {
        assertEquals(
            listOf(OnboardingStep.Overlay, OnboardingStep.UsageAccess),
            Onboarding.missingRequired(PermissionStatus()),
        )
        assertEquals(
            listOf(OnboardingStep.UsageAccess),
            Onboarding.missingRequired(PermissionStatus(overlay = true)),
        )
        assertEquals(
            listOf(OnboardingStep.Overlay),
            Onboarding.missingRequired(PermissionStatus(usageAccess = true)),
        )
        assertEquals(
            emptyList<OnboardingStep>(),
            Onboarding.missingRequired(allGranted),
        )
    }

    @Test
    fun `satisfied reads each step from its own source`() {
        val onlyOverlay = PermissionStatus(overlay = true)

        assertTrue(Onboarding.satisfied(OnboardingStep.Overlay, onlyOverlay, false))
        assertFalse(Onboarding.satisfied(OnboardingStep.UsageAccess, onlyOverlay, false))
        assertFalse(Onboarding.satisfied(OnboardingStep.Notifications, onlyOverlay, false))
        // 小窗只看自检：授权齐全也不代表小窗能用。
        assertFalse(Onboarding.satisfied(OnboardingStep.FreeformWindows, allGranted, false))
        assertTrue(Onboarding.satisfied(OnboardingStep.FreeformWindows, PermissionStatus(), true))
    }
}
