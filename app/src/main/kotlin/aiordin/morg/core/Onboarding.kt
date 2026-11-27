package aiordin.morg.core

/**
 * 引导规则。
 *
 * 不碰 Android 类型，步骤与完成判定都能在 JVM 单测里验证。
 *
 * - **欢迎页**：首次进入应用，说明有哪些入口；
 * - **服务引导**：第一次打开「边缘手柄」时授权；
 * - **小窗引导**：第一次打开「打开方式」时确认小窗能力。
 *
 * 小窗的完成条件是实际打开一个小窗再关掉（见 `launch.WindowProbeActivity`），
 * 不以开发者选项的值为准。
 */
public enum class OnboardingStep {
    /** 浮窗权限：没有它手柄画不出来。 */
    Overlay,

    /** 使用情况访问：没有它不知道前台是谁，手柄会一直挂在桌面上。 */
    UsageAccess,

    /** 常驻通知：可选，拒绝后手柄照常工作。 */
    Notifications,
}

/** 三项权限的当前状态。默认全未授予。 */
public data class PermissionStatus(
    val overlay: Boolean = false,
    val usageAccess: Boolean = false,
    val notifications: Boolean = false,
)

public object Onboarding {

    /**
     * 服务引导的顺序：两项必需权限 → 通知（有才显示）。
     * Android 13 以下没有通知运行时权限，不展示这一步。
     */
    public fun steps(notificationsAvailable: Boolean): List<OnboardingStep> = buildList {
        add(OnboardingStep.Overlay)
        add(OnboardingStep.UsageAccess)
        if (notificationsAvailable) add(OnboardingStep.Notifications)
    }

    /** 首次进入应用时显示欢迎页。 */
    public fun shouldShowWelcome(completed: Boolean): Boolean = !completed

    /**
     * 某个开关第一次打开时是否显示它自己的引导。
     *
     * 记的是「看过」而不是「完成」：跳过也算，否则每次打开开关都弹一次。
     */
    public fun shouldShowGuide(seen: Boolean): Boolean = !seen

    /** 手柄能正常工作的最低条件：浮窗负责「画得出来」，使用情况访问负责「知道该不该显示」。 */
    public fun isReady(status: PermissionStatus): Boolean =
        status.overlay && status.usageAccess

    /** 还缺哪些**必需**项，按引导顺序。通知可以「稍后再说」。 */
    public fun missingRequired(status: PermissionStatus): List<OnboardingStep> = buildList {
        if (!status.overlay) add(OnboardingStep.Overlay)
        if (!status.usageAccess) add(OnboardingStep.UsageAccess)
    }

    /** 这一项是否已经满足 —— 引导页据此决定按钮是动作还是「继续」。 */
    public fun satisfied(step: OnboardingStep, status: PermissionStatus): Boolean = when (step) {
        OnboardingStep.Overlay -> status.overlay
        OnboardingStep.UsageAccess -> status.usageAccess
        OnboardingStep.Notifications -> status.notifications
    }
}
