package aiordin.morg.launch

import android.content.Context
import androidx.core.content.edit

/**
 * 小窗自检结果的落盘。
 *
 * 自检发生在**另一个 Activity 实例**里（系统可能给它单独的 task），
 * 内存状态带不回来，所以必须写盘再由 [WindowProbeActivity] 的调用方读走。
 *
 * 结果是**一次性**的：[consume] 读完就清掉标记，避免一次成功被永久当作「现在也能用」。
 */
public class WindowProbeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    public fun record(result: WindowProbeResult, windowingMode: Int) {
        prefs.edit {
            putString(KEY_RESULT, result.name)
            putInt(KEY_MODE, windowingMode)
            putBoolean(KEY_PENDING, true)
        }
    }

    /** 取走尚未消费的自检结果；没有则返回 null。 */
    public fun consume(): WindowProbeRecord? {
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        val result = prefs.getString(KEY_RESULT, null)
            ?.let { name -> runCatching { WindowProbeResult.valueOf(name) }.getOrNull() }
        val mode = prefs.getInt(KEY_MODE, WindowMode.NONE)
        prefs.edit { putBoolean(KEY_PENDING, false) }
        return result?.let { WindowProbeRecord(it, mode) }
    }

    private companion object {
        private const val PREFS_NAME = "morg.window_probe"
        private const val KEY_RESULT = "result"
        private const val KEY_MODE = "windowing_mode"
        private const val KEY_PENDING = "pending"
    }
}
