package aiordin.morg.qs

import aiordin.morg.MorgApp
import aiordin.morg.R
import aiordin.morg.overlay.HandleService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * 控制中心里启停边缘手柄的磁贴。
 *
 * 状态读 [HandleService.running]，不读设置里的 `serviceEnabled`：服务被系统
 * 清理后设置值仍是 `true`，磁贴会与实际不一致。
 *
 * 服务可能在磁贴不可见时启停，所以 [onStartListening] 里注册状态广播接收器，
 * 由 [HandleService] 状态变化时发出 [HandleService.ACTION_STATE_CHANGED]。
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
            openMorgApp()
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
}
