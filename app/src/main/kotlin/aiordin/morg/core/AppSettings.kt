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

    /** 全屏 / 小窗。默认全屏；打开方式开关打开时切到小窗。 */
    public fun setLaunchMode(mode: LaunchMode) = update { it.copy(launchMode = mode) }

    /** 位置设定模式：开启时手柄可拖动，关闭时把位置落到磁盘。 */
    public fun setPositionEditing(editing: Boolean) = update { it.copy(positionEditing = editing) }

    /** 手柄位置。只在位置设定模式关闭时写入。 */
    public fun setPlacement(placement: HandlePlacement) = update { it.copy(placement = placement) }

    /** 初次进入应用的欢迎引导是否已结束（完成或跳过）。 */
    public fun setWelcomeCompleted(completed: Boolean) =
        update { it.copy(welcomeCompleted = completed) }

    /** 边缘手柄开关第一次打开时的权限引导是否已看过。 */
    public fun markServiceGuideSeen() = update { it.copy(serviceGuideSeen = true) }

    /** 打开方式开关第一次打开时的小窗引导是否已看过。 */
    public fun markWindowGuideSeen() = update { it.copy(windowGuideSeen = true) }

    private fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        prefs.edit {
            putBoolean(KEY_ENABLED, next.serviceEnabled)
            putString(KEY_MODE, next.launchMode.name)
            putString(KEY_SIDE, next.placement.side.name)
            putFloat(KEY_RATIO, next.placement.verticalRatio)
            putBoolean(KEY_EDITING, next.positionEditing)
            putBoolean(KEY_WELCOME, next.welcomeCompleted)
            putBoolean(KEY_SERVICE_GUIDE, next.serviceGuideSeen)
            putBoolean(KEY_WINDOW_GUIDE, next.windowGuideSeen)
        }
        _state.value = next
    }

    private fun load(): Settings = Settings(
        serviceEnabled = prefs.getBoolean(KEY_ENABLED, false),
        launchMode = prefs.getString(KEY_MODE, null)
            ?.let { runCatching { LaunchMode.valueOf(it) }.getOrNull() }
            ?: LaunchMode.Fullscreen,
        placement = HandlePlacement(
            side = prefs.getString(KEY_SIDE, null)
                ?.let { runCatching { DockSide.valueOf(it) }.getOrNull() }
                ?: HandlePlacement.Default.side,
            verticalRatio = prefs.getFloat(KEY_RATIO, HandlePlacement.Default.verticalRatio),
        ),
        positionEditing = prefs.getBoolean(KEY_EDITING, false),
        welcomeCompleted = prefs.getBoolean(KEY_WELCOME, false),
        serviceGuideSeen = prefs.getBoolean(KEY_SERVICE_GUIDE, false),
        windowGuideSeen = prefs.getBoolean(KEY_WINDOW_GUIDE, false),
    )

    private companion object {
        private const val PREFS_NAME = "morg.settings"
        private const val KEY_ENABLED = "service_enabled"
        private const val KEY_MODE = "launch_mode"
        private const val KEY_SIDE = "handle_side"
        private const val KEY_RATIO = "handle_ratio"
        private const val KEY_EDITING = "position_editing"

        /**
         * 旧版本用这个 key 记录「首次引导已完成」。现在它表示欢迎页已完成：
         * 已升级的用户不会再看到一次欢迎页，但各自开关的首次引导仍会按新 key 触发。
         */
        private const val KEY_WELCOME = "onboarding_completed"
        private const val KEY_SERVICE_GUIDE = "service_guide_seen"
        private const val KEY_WINDOW_GUIDE = "window_guide_seen"
    }
}

/** 设置的不可变快照。 */
public data class Settings(
    val serviceEnabled: Boolean,
    val launchMode: LaunchMode,
    val placement: HandlePlacement,
    val positionEditing: Boolean,
    val welcomeCompleted: Boolean = false,
    val serviceGuideSeen: Boolean = false,
    val windowGuideSeen: Boolean = false,
) {
    /** 打开方式开关的状态：关 = 全屏，开 = 小窗。 */
    public val windowModeEnabled: Boolean get() = launchMode == LaunchMode.Window
}
