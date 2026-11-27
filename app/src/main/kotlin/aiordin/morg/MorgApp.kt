package aiordin.morg

import aiordin.morg.core.AppSettings
import aiordin.morg.launch.InstanceLauncher
import aiordin.morg.overlay.ForegroundTracker
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 应用入口，同时充当依赖容器。
 *
 * 没有引入依赖注入框架：这个应用只有四个协作者，手写装配比注解处理器更直接，
 * 读代码的人也不需要先理解一个 DI 图。所有依赖都是 `by lazy` 的单例。
 */
public class MorgApp : Application(), LifecycleOwner, SavedStateRegistryOwner {

    public val settings: AppSettings by lazy { AppSettings(this) }
    public val tracker: ForegroundTracker by lazy { ForegroundTracker(this) }
    public val launcher: InstanceLauncher by lazy { InstanceLauncher(this) }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        // 悬浮窗里的 ComposeView 需要一个处于 RESUMED 的 LifecycleOwner，
        // 否则 collectAsStateWithLifecycle 永远不会开始收集。
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
}
