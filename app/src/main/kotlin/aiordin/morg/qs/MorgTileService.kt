package aiordin.morg.qs

import aiordin.morg.MainActivity
import aiordin.morg.MorgApp
import aiordin.morg.R
import aiordin.morg.overlay.HandleService
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * 控制中心快捷开关。
 *
 * 用户从顶部下滑就能启停手柄，不用先打开应用 —— 手柄是「随手用」的东西，
 * 每次都进应用开关太笨重。
 *
 * ## 状态以「服务是否真的在跑」为准
 *
 * 不能读设置里的 `serviceEnabled`：服务被系统清理后那个值仍是 `true`，
 * 磁贴会显示「开」而实际没有手柄。所以状态源是 [HandleService.running]。
 * 这也是 2dust/v2rayNG 的做法（`CoreServiceManager.isRunning()`）。
 *
 * ## 状态变化要主动刷新
 *
 * 系统只在磁贴可见时调 [onStartListening]。服务在后台启动/停止（通知里的
 * 「停止」、应用内开关、被系统杀掉）时，磁贴不会自己更新。所以：
 *
 * - [onStartListening] 里注册广播接收器；
 * - [HandleService] 状态变化时发 [HandleService.ACTION_STATE_CHANGED]
 *   并请求系统重拉。
 *
 * 参考 v2rayNG 的 `QSTileService`：`onStartListening` 注册、`onStopListening` 注销。
 */
public class MorgTileService : TileService() {

    private var receiver: BroadcastReceiver? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile(HandleService.running)
        registerStateReceiver()
    }

    override fun onStopListening() {
        super.onStopListening()
        unregisterStateReceiver()
    }

    /**
     * 点击开关。
     *
     * 没授予悬浮窗权限时不能直接开服务（开了也看不见），改为把用户送到应用里，
     * 由引导页处理授权 —— 比一个静默失败的开关有用。
     */
    override fun onClick() {
        super.onClick()
        val app = application as MorgApp

        // 以真实运行状态决定动作，而不是设置值 —— 两者可能不一致。
        if (HandleService.running) {
            app.settings.setServiceEnabled(false)
            HandleService.stop(this)
            updateTile(false)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            launchApp()
            return
        }

        app.settings.setServiceEnabled(true)
        HandleService.start(this)
        // 服务真正起来后由广播再刷一次，这里先乐观更新，避免点击后无反馈。
        updateTile(true)
    }

    private fun updateTile(active: Boolean) {
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_label)
            updateTile()
        }
    }

    private fun registerStateReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateTile(HandleService.running)
            }
        }
        // 显式包名 + NOT_EXPORTED：Android 13+ 对动态注册的要求。
        val ok = runCatching {
            ContextCompat.registerReceiver(
                applicationContext,
                r,
                IntentFilter(HandleService.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        }.getOrDefault(false)
        if (ok) receiver = r
    }

    private fun unregisterStateReceiver() {
        receiver?.let { runCatching { applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    /**
     * 打开应用。
     *
     * API 34 起必须用 PendingIntent 版本；低版本仍走 Intent 版本。两个分支互斥，
     * lint 只看得到被调用的废弃 API，所以在这里整体 suppress。
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34 起 Intent 版本废弃，改用 PendingIntent。
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}
