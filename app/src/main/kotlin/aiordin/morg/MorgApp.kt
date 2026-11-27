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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /**
     * 不随任何组件销毁的应用级作用域。
     *
     * 控制中心磁贴点击后，TileService 会立刻被解绑，用服务自己的 scope 启动多开
     * 会在动作完成前被取消。这个 scope 随进程存活 —— 进程退出时任务自然结束，
     * 不需要保活，也不会留下后台运行的服务。
     */
    public val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
