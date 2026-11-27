package aiordin.morg.core

/**
 * 首次启动引导的规则。
 *
 * 这里不碰 Android 类型：步骤顺序、必需性、完成判定都能在 JVM 单测里验证。
 * 权限的真实读取在 [Permissions]，界面在 `ui/OnboardingScreen`。
 *
 * 「小窗支持」这一步的完成条件不是读开发者选项里的开关，而是
 * **真的打开一个小窗再关掉**（见 `launch.WindowProbeActivity`）——
 * 开关打开但 `windowingMode` 被 ROM 丢弃的情况是实测存在的。
 */
public enum class OnboardingStep {
    /** 浮窗权限：没有它手柄画不出来。 */
    Overlay,

    /** 使用情况访问：没有它不知道前台是谁，手柄会一直挂在桌面上。 */
    UsageAccess,

    /** 小窗能力：以「能打开小窗」为确认依据，不只看开发者选项的开关。 */
    FreeformWindows,

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
     * 引导顺序：两项必需权限 → 小窗确认 → 通知（有才显示）。
     *
     * 小窗确认排在通知前面：它决定「打开方式」里能不能选小窗，
     * 比一条常驻通知更影响第一次使用的观感。
     *
     * @param windowConfirmed 小窗自检是否已经成功过。没成功过就一直保留这一步，
     *   哪怕静态探测认为设备有小窗 —— 那只是「应该有」，不是「确实能开」。
     */
    public fun steps(
        notificationsAvailable: Boolean,
        windowConfirmed: Boolean,
    ): List<OnboardingStep> = buildList {
        add(OnboardingStep.Overlay)
        add(OnboardingStep.UsageAccess)
        if (!windowConfirmed) add(OnboardingStep.FreeformWindows)
        // Android 13 以下没有通知运行时权限，别给用户看一个点了没反应的按钮。
        if (notificationsAvailable) add(OnboardingStep.Notifications)
    }

    /**
     * 是否进入引导。
     *
     * 只看「是否完成过」：完成之后用户又去系统设置撤销了权限，不重新进引导 ——
     * 主界面的服务卡片里已经有逐项授权入口，反复弹引导反而像在纠缠。
     */
    public fun shouldShow(completed: Boolean): Boolean = !completed

    /** 手柄能正常工作的最低条件：浮窗负责「画得出来」，使用情况访问负责「知道该不该显示」。 */
    public fun isReady(status: PermissionStatus): Boolean =
        status.overlay && status.usageAccess

    /** 还缺哪些**必需**项，按引导顺序。小窗与通知都可以「稍后再说」。 */
    public fun missingRequired(status: PermissionStatus): List<OnboardingStep> = buildList {
        if (!status.overlay) add(OnboardingStep.Overlay)
        if (!status.usageAccess) add(OnboardingStep.UsageAccess)
    }

    /**
     * 这一项是否已经满足 —— 引导页据此决定按钮是动作还是「继续」。
     *
     * 小窗这一步看的是自检结果，不看设置项：只有真的开出小窗才算过。
     */
    public fun satisfied(
        step: OnboardingStep,
        status: PermissionStatus,
        windowConfirmed: Boolean,
    ): Boolean = when (step) {
        OnboardingStep.Overlay -> status.overlay
        OnboardingStep.UsageAccess -> status.usageAccess
        OnboardingStep.Notifications -> status.notifications
        OnboardingStep.FreeformWindows -> windowConfirmed
    }
}
