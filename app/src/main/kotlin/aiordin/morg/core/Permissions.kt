package aiordin.morg.core

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * 三项权限的读取与系统设置入口。
 *
 * 集中在一个类里，引导页和设置界面读同一份判断，不会出现「引导说已授予、
 * 主界面还让你去授权」这种自相矛盾。规则本身在 [Onboarding]（纯逻辑）。
 *
 * 前两项都无法用普通运行时权限申请：`SYSTEM_ALERT_WINDOW` 与
 * `PACKAGE_USAGE_STATS` 都属于特殊权限，只能把用户送到系统设置页再返回，
 * 所以读取时机一律放在 `Activity.onResume`。
 */
public class Permissions(context: Context) {

    private val appContext = context.applicationContext

    /** 通知权限从 Android 13（API 33）起才存在。 */
    public val notificationsAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    public fun status(): PermissionStatus = PermissionStatus(
        overlay = Settings.canDrawOverlays(appContext),
        usageAccess = hasUsageAccess(),
        notifications = notificationsGranted(),
    )

    /** `PACKAGE_USAGE_STATS` 是 app-op，不是运行时权限，只能查 [AppOpsManager]。 */
    public fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 没有通知权限也照常处理：那只是通知不显示，手柄本身不受影响。 */
    public fun notificationsGranted(): Boolean =
        !notificationsAvailable || ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    /** 直接落到 Morg 自己的悬浮窗授权页，而不是让用户在一长串应用里找。 */
    public fun overlaySettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:${appContext.packageName}".toUri(),
    )

    public fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}
