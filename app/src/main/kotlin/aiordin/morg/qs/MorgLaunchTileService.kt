package aiordin.morg.qs

import aiordin.morg.MorgApp
import aiordin.morg.R
import aiordin.morg.core.Permissions
import aiordin.morg.launch.LaunchCommand
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.launch

/**
 * 「新开窗口」磁贴。
 *
 * 独立于边缘手柄服务：不需要手柄在运行，也不需要悬浮窗与通知权限，
 * 唯一依赖是「使用情况访问」（用来知道前台是哪个应用）。
 *
 * 走 `startActivityAndCollapse` 而不是 `startActivity`：后者不会收起
 * 快捷设置面板，新 task 会被盖住。
 */
public class MorgLaunchTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val app = application as MorgApp
        if (!Permissions(this).hasUsageAccess()) {
            openMorgApp()
            return
        }

        val foreground = runCatching { app.tracker.currentForeground() }.getOrNull()
        if (!app.tracker.shouldShowHandle(foreground)) return

        val state = app.settings.state.value
        // 服务会在 onClick 返回后被系统解绑，所以用随进程存活的 appScope。
        app.appScope.launch {
            val command = app.launcher.prepare(
                packageName = requireNotNull(foreground),
                mode = state.launchMode,
                side = state.placement.side,
            )
            if (command == null) {
                Log.w(TAG, "tile: no launch command for $foreground")
                return@launch
            }
            Log.i(TAG, "tile tap -> new task for $foreground mode=${command.windowingMode}")
            launchAndCollapse(command)
        }
    }

    /** API 34 起只接受 `PendingIntent`；更低版本退回 `startActivity`，小窗参数不丢。 */
    private fun launchAndCollapse(command: LaunchCommand) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    command.intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    command.options,
                )
                startActivityAndCollapse(pending)
            } else {
                startActivity(command.intent, command.options)
            }
        }.onFailure { error ->
            Log.w(TAG, "tile launch failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** 一次性动作，恒为未激活。 */
    private fun updateTile() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.tile_launch_label)
            updateTile()
        }
    }

    private companion object {
        private const val TAG = "Morg"
    }
}
