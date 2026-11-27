package aiordin.morg.qs

import aiordin.morg.MainActivity
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * 磁贴需要用户回应用处理时（未授予使用情况访问）调它，同时收起面板。
 *
 * API 34 起只接受 `PendingIntent`，两个分支互斥，lint 只看得到废弃的那个。
 */
@SuppressLint("StartActivityAndCollapseDeprecated")
internal fun TileService.openMorgApp() {
    val intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
