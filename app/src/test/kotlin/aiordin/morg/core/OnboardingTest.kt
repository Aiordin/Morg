package aiordin.morg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导规则：
 *
 * 1. 浮窗与使用情况访问是必需项，通知可选；
 * 2. Android 13 以下没有通知权限，步骤列表里不能出现它；
 * 3. 欢迎页只在首次进入显示；三段引导各自只在对应开关第一次打开时显示；
 * 4. 跳过也算「看过」，不再重复弹。
 */
class OnboardingTest {

    private val allGranted = PermissionStatus(
        overlay = true,
        usageAccess = true,
        notifications = true,
    )

    @Test
    fun `service steps start with required permissions`() {
        assertEquals(
            listOf(
                OnboardingStep.Overlay,
                OnboardingStep.UsageAccess,
                OnboardingStep.Notifications,
            ),
            Onboarding.steps(notificationsAvailable = true),
        )
    }

    @Test
    fun `notifications step is dropped before android 13`() {
        assertEquals(
            listOf(OnboardingStep.Overlay, OnboardingStep.UsageAccess),
            Onboarding.steps(notificationsAvailable = false),
        )
    }

    @Test
    fun `welcome shows only on first launch`() {
        assertTrue(Onboarding.shouldShowWelcome(completed = false))
        assertFalse(Onboarding.shouldShowWelcome(completed = true))
    }

    @Test
    fun `each switch guide shows only until it is seen`() {
        assertTrue(Onboarding.shouldShowGuide(seen = false))
        assertFalse(Onboarding.shouldShowGuide(seen = true))
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
    fun `notifications never block readiness`() {
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

        assertTrue(Onboarding.satisfied(OnboardingStep.Overlay, onlyOverlay))
        assertFalse(Onboarding.satisfied(OnboardingStep.UsageAccess, onlyOverlay))
        assertFalse(Onboarding.satisfied(OnboardingStep.Notifications, onlyOverlay))
        assertTrue(Onboarding.satisfied(OnboardingStep.Notifications, allGranted))
    }
}
