package aiordin.morg.launch

import aiordin.morg.MainActivity
import aiordin.morg.R
import aiordin.morg.ui.theme.MorgTheme
import android.app.ActivityManager
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnLayout

/**
 * 小窗自检页。
 *
 * 引导里「小窗支持」这一步的确认方式：**真的打开一个小窗，再把它关掉**。
 * 只看开发者选项里的开关不够 —— 那个值打开、`windowingMode` 仍可能被 ROM 丢弃，
 * 用户选了小窗却得到全屏。
 *
 * 流程：
 *
 * 1. [MainActivity] 把主界面退到后台，再以小窗模式启动本页（目标在前台时会被复用成全屏，
 *    所以必须先退后台）；
 * 2. 本页测量自己这扇窗口是否真的比屏幕小；
 * 3. 结果写盘（[WindowProbeStore]），显示给人看；
 * 4. 用户点「关闭小窗」或 2.6s 后自动关闭，回到主界面。
 *
 * 本页不需要任何权限：它是普通 Activity，只是被要求以 `windowingMode` 打开。
 */
public class WindowProbeActivity : ComponentActivity() {

    private val store by lazy { WindowProbeStore(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var result: WindowProbeResult? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MorgTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ProbeContent(result = result, onClose = ::closeProbe)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (result != null) return
        // 布局完成才有真实尺寸；doOnLayout 在已布局时会立刻回调。
        window.decorView.doOnLayout { measureAndRecord() }
    }

    private fun measureAndRecord() {
        if (result != null) return
        val decor = window.decorView
        val screen = realScreen()
        val windowBounds = WindowBounds(0, 0, decor.width, decor.height)
        val screenBounds = WindowBounds(0, 0, screen.first, screen.second)
        val measured = if (WindowProbe.isSmallWindow(windowBounds, screenBounds)) {
            WindowProbeResult.SmallWindow
        } else {
            WindowProbeResult.Fullscreen
        }
        Log.i(
            TAG,
            "probe window=${decor.width}x${decor.height} " +
                "screen=${screen.first}x${screen.second} " +
                "requested=${requestedMode()} result=$measured",
        )
        store.record(measured, requestedMode())
        result = measured
        handler.postDelayed(::closeProbe, AUTO_CLOSE_MS)
    }

    /** 关闭小窗并把主界面带回前台。 */
    private fun closeProbe() {
        if (isFinishing) return
        handler.removeCallbacksAndMessages(null)
        // 主界面此刻在后台（调用方先 moveTaskToBack 才有了这次小窗）。
        // 不能直接 startActivity(CLEAR_TOP)：同 affinity 的当前 task 就是这扇小窗，
        // MainActivity 会被启动到小窗里（实测），用户看到主界面缩成小窗。
        val mainTaskId = intent.getIntExtra(EXTRA_MAIN_TASK_ID, -1)
        val moved = mainTaskId > 0 && runCatching {
            getSystemService(ActivityManager::class.java)
                ?.moveTaskToFront(mainTaskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            true
        }.getOrDefault(false)
        finish()
        if (!moved) launchMain()
    }

    /** 主 task 已经不在了（被系统回收）时的兜底。 */
    private fun launchMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun requestedMode(): Int = intent.getIntExtra(EXTRA_WINDOWING_MODE, WindowMode.NONE)

    /** 真实屏幕尺寸。与 [InstanceLauncher] 用同一套取法，判定才有可比性。 */
    private fun realScreen(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val fallback = resources.displayMetrics
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else fallback.widthPixels
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else fallback.heightPixels
        return w to h
    }

    public companion object {
        private const val TAG = "Morg"
        private const val AUTO_CLOSE_MS = 2600L

        /** 本次自检请求的 windowingMode，写盘时一起记下，用来区分原生小窗与 freeform。 */
        public const val EXTRA_WINDOWING_MODE: String = "aiordin.morg.extra.WINDOWING_MODE"

        /** 调用方（主界面）的 taskId，关闭小窗后据此把主界面切回前台。 */
        public const val EXTRA_MAIN_TASK_ID: String = "aiordin.morg.extra.MAIN_TASK_ID"
    }
}

@Composable
private fun ProbeContent(result: WindowProbeResult?, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.probe_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(
                when (result) {
                    null -> R.string.probe_checking
                    WindowProbeResult.SmallWindow -> R.string.probe_success
                    WindowProbeResult.Fullscreen -> R.string.probe_fullscreen
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = when (result) {
                WindowProbeResult.Fullscreen -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Button(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.probe_close))
        }
    }
}
