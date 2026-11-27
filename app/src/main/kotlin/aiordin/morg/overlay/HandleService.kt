package aiordin.morg.overlay

import aiordin.morg.MainActivity
import aiordin.morg.MorgApp
import aiordin.morg.R
import aiordin.morg.core.AppSettings
import aiordin.morg.qs.MorgTileService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 手柄的前台服务。
 *
 * 需要前台服务是因为手柄要在游戏之上常驻；Android 会要求一个常驻通知。
 * 通知里的「停止」action 是**必须的**：手柄一旦盖住屏幕，非触摸的关闭通道
 * 就只剩这一条。
 */
public class HandleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: HandleOverlay? = null

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        startForegroundCompat()
        running = true
        notifyStateChanged()
        val app = application as MorgApp
        val overlay = HandleOverlay(
            context = this,
            settings = app.settings,
            launcher = app.launcher,
            tracker = app.tracker,
            lifecycleOwner = app,
            savedStateOwner = app,
        )
        this.overlay = overlay

        // 设置一变就同步手柄：位置、模式、是否运行。
        scope.launch {
            app.settings.state.collectLatest { state ->
                if (!state.serviceEnabled) {
                    stopSelf()
                    return@collectLatest
                }
                overlay.start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as MorgApp).settings.setServiceEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlay?.stop()
        overlay = null
        scope.cancel()
        running = false
        notifyStateChanged()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HandleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_handle)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    public companion object {
        private const val TAG = "Morg"
        private const val CHANNEL_ID = "morg.handle"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "aiordin.morg.action.STOP"

        /**
         * 服务此刻是否真的在跑。
         *
         * 磁贴与界面用**它**（而不是设置里的开关值）决定显示状态 —— 否则
         * 服务被系统清理后开关仍显示「开」，与实际不一致。
         * 参考 2dust/v2rayNG 的做法：状态以真实运行为准。
         */
        @Volatile
        public var running: Boolean = false
            private set

        /** 服务运行状态变化时发出的广播（供磁贴刷新）。 */
        public const val ACTION_STATE_CHANGED: String = "aiordin.morg.action.STATE_CHANGED"

        /** 通知磁贴重新拉取状态。 */
        public fun requestTileRefresh(context: Context) {
            TileService.requestListeningState(
                context,
                ComponentName(context, MorgTileService::class.java),
            )
        }

        /** 状态变化：发广播（进程内磁贴）+ 请系统重拉磁贴。 */
        private fun notifyStateChanged() {
            val ctx = appContext ?: return
            runCatching {
                // 显式包名 + 不导出，Android 13+ 对隐式广播的限制不会影响它。
                ctx.sendBroadcast(
                    Intent(ACTION_STATE_CHANGED).setPackage(ctx.packageName),
                )
            }
            runCatching { requestTileRefresh(ctx) }
        }

        /** 供 [notifyStateChanged] 使用的进程内 context，在 [onCreate] 里赋值。 */
        @Volatile
        private var appContext: Context? = null

        /** 启动服务。调用前应确保已有悬浮窗权限。 */
        public fun start(context: Context) {
            appContext = context.applicationContext
            val intent = Intent(context, HandleService::class.java)
            runCatching {
                context.startForegroundService(intent)
            }.onFailure { Log.w(TAG, "start service failed: ${it.message}") }
        }

        public fun stop(context: Context) {
            appContext = context.applicationContext
            runCatching { context.stopService(Intent(context, HandleService::class.java)) }
        }
    }
}
