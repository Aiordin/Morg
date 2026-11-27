package aiordin.morg.core

import aiordin.morg.launch.LaunchMode
import aiordin.morg.overlay.DockSide
import aiordin.morg.overlay.HandlePlacement
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全部用户设置。
 *
 * 用 `SharedPreferences` 而不是 DataStore：这里只有几个标量，引入新依赖换不到什么。
 * 对外只暴露 `StateFlow`，UI 与 Service 都观察同一份状态，不需要各自去读盘。
 */
public class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    public val state: StateFlow<Settings> = _state.asStateFlow()

    /** 服务是否应该运行。控制中心开关、应用内开关都改它。 */
    public fun setServiceEnabled(enabled: Boolean) = update { it.copy(serviceEnabled = enabled) }

    /** 全屏 / 小窗。 */
    public fun setLaunchMode(mode: LaunchMode) = update { it.copy(launchMode = mode) }

    /** 位置设定模式：开启时手柄可拖动，关闭时把位置落到磁盘。 */
    public fun setPositionEditing(editing: Boolean) = update { it.copy(positionEditing = editing) }

    /** 手柄位置。只在位置设定模式关闭时写入。 */
    public fun setPlacement(placement: HandlePlacement) = update { it.copy(placement = placement) }

    /** 首次启动引导是否已结束（完成或跳过）。 */
    public fun setOnboardingCompleted(completed: Boolean) =
        update { it.copy(onboardingCompleted = completed) }

    private fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        prefs.edit {
            putBoolean(KEY_ENABLED, next.serviceEnabled)
            putString(KEY_MODE, next.launchMode.name)
            putString(KEY_SIDE, next.placement.side.name)
            putFloat(KEY_RATIO, next.placement.verticalRatio)
            putBoolean(KEY_EDITING, next.positionEditing)
            putBoolean(KEY_ONBOARDING, next.onboardingCompleted)
        }
        _state.value = next
    }

    private fun load(): Settings = Settings(
        serviceEnabled = prefs.getBoolean(KEY_ENABLED, false),
        launchMode = prefs.getString(KEY_MODE, null)
            ?.let { runCatching { LaunchMode.valueOf(it) }.getOrNull() }
            ?: LaunchMode.Window,
        placement = HandlePlacement(
            side = prefs.getString(KEY_SIDE, null)
                ?.let { runCatching { DockSide.valueOf(it) }.getOrNull() }
                ?: HandlePlacement.Default.side,
            verticalRatio = prefs.getFloat(KEY_RATIO, HandlePlacement.Default.verticalRatio),
        ),
        positionEditing = prefs.getBoolean(KEY_EDITING, false),
        onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING, false),
    )

    private companion object {
        private const val PREFS_NAME = "morg.settings"
        private const val KEY_ENABLED = "service_enabled"
        private const val KEY_MODE = "launch_mode"
        private const val KEY_SIDE = "handle_side"
        private const val KEY_RATIO = "handle_ratio"
        private const val KEY_EDITING = "position_editing"
        private const val KEY_ONBOARDING = "onboarding_completed"
    }
}

/** 设置的不可变快照。 */
public data class Settings(
    val serviceEnabled: Boolean,
    val launchMode: LaunchMode,
    val placement: HandlePlacement,
    val positionEditing: Boolean,
    val onboardingCompleted: Boolean = false,
)
