package aiordin.morg.overlay

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * 一次前台采样的结果。
 *
 * @param packageName 当前前台；null 表示从未识别到
 * @param settling 证据还没到齐，约 1.5s 内可能翻转 —— 调用方应用快轮询确认
 */
public data class ForegroundSample(
    val packageName: String?,
    val settling: Boolean,
)

/**
 * 采集「现在前台是什么、屏幕上还有哪些窗口」。
 *
 * 判定规则在 [ForegroundDecision]（纯函数、可单测）。这里只管两件事：
 * 增量读取 `UsageStats` 事件，维护三个小状态。
 *
 * ## 复杂度
 *
 * 事件只读一次：用 [cursor] 记住扫到哪，之后每轮只 query 新增区间。
 *
 * - 时间：O(新增事件数)，稳态通常是 0（没有 Activity 切换就没有新事件）。
 * - 空间：O(存活实例数)，通常个位数 —— 只保留 `lastResumed` 和
 *   [liveInstances]，不缓存事件列表。
 *
 * 首次采样（进程刚起）做一次 [INITIAL_LOOKBACK_MS] 的回看建立初始状态 ——
 * 否则小窗可能在回看窗口之外、刚启动时识别不到。
 *
 * ## 为什么不用无障碍服务
 *
 * 它只需「使用情况访问」权限；无障碍要用户单独开，对这样一个工具代价太大。
 */
public class ForegroundTracker(context: Context) {

    private val appContext = context.applicationContext
    private val launcherPackages: Set<String> by lazy { queryLauncherPackages() }

    /** 上一次被采纳的**强**前台（该显示手柄的包）。写桌面进来会让预览取消时无处可退。 */
    private var lastStrong: String? = null

    /** 最后一条 `RESUMED` 事件。判定只需要这一个事件，不需要整份列表。 */
    private var lastResumed: ForegroundEvent? = null

    /** 仍存活的 Activity 实例，按 `instanceId` 索引。 */
    private val liveInstances = HashMap<Int, LiveInstance>()

    /** 已扫描到的时间戳。0 表示尚未初始化，下次采样会做一次完整回看。 */
    private var cursor: Long = 0L

    /**
     * `UsageEvents.Event.getInstanceId()` 的反射句柄。
     *
     * 它是 `@hide`：编译用 SDK stub 里没有（直接写编译失败），设备 framework
     * 里存在。反射失败时退化为 0（退化成包级判断，不会崩）。
     *
     * 不要改用 `getExtras()`：本机 framework 根本没那个方法，调用会
     * `NoSuchMethodError`，还会被上层 `runCatching` 静默吞掉，表现为
     * 「手柄完全不显示」而毫无日志。
     */
    private val instanceIdMethod = runCatching {
        UsageEvents.Event::class.java.getMethod("getInstanceId").apply { isAccessible = true }
    }.getOrNull()

    private fun instanceIdOf(event: UsageEvents.Event): Int =
        instanceIdMethod?.let { runCatching { it.invoke(event) as? Int }.getOrNull() } ?: 0

    /** 采一次前台状态。 */
    public fun sample(): ForegroundSample {
        val usage = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return ForegroundSample(lastStrong, settling = false)
        val now = System.currentTimeMillis()

        // 首次做完整回看，之后只扫新增区间。
        val begin = if (cursor == 0L) now - INITIAL_LOOKBACK_MS else cursor
        val events = runCatching { usage.queryEvents(begin, now) }.getOrNull()
        if (events != null) {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                applyEvent(event)
            }
        }
        cursor = now

        val result = ForegroundDecision.decide(
            now = now,
            lastStrong = lastStrong,
            isWeak = ::isWeakForeground,
            latestResumed = lastResumed,
            liveInstances = liveInstances.values,
        )
        lastStrong = result.lastStrong
        return ForegroundSample(result.foreground, result.settling)
    }

    /**
     * 把一条事件并入状态。只认三种 Activity 生命周期事件，其余（前台服务、
     * 配置变化、锁屏…）直接跳过。
     */
    private fun applyEvent(event: UsageEvents.Event) {
        val pkg = event.packageName ?: return
        val id = instanceIdOf(event)
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                lastResumed = ForegroundEvent(pkg, event.timeStamp, id)
                liveInstances[id] = LiveInstance(pkg, id, visible = true)
            }

            UsageEvents.Event.ACTIVITY_PAUSED ->
                liveInstances[id] = LiveInstance(pkg, id, visible = false)

            UsageEvents.Event.ACTIVITY_STOPPED -> liveInstances.remove(id)
        }
    }

    /** 采一次当前前台包名。 */
    public fun currentForeground(): String? = sample().packageName

    /** 这个包是否「不应该显示手柄」（桌面、系统 UI、本应用）。 */
    private fun isWeakForeground(pkg: String): Boolean =
        pkg == appContext.packageName ||
            pkg in launcherPackages ||
            pkg in SYSTEM_PACKAGES ||
            pkg.startsWith(SYSTEM_PACKAGE_PREFIX)

    /** 手柄此刻是否应该显示。 */
    public fun shouldShowHandle(foreground: String?): Boolean {
        if (foreground == null) return false
        return !isWeakForeground(foreground)
    }

    /**
     * 桌面包名。用 `queryIntentActivities` 动态查 —— 各家 ROM 的桌面包名
     * 都不一样，写死必然漏。
     */
    private fun queryLauncherPackages(): Set<String> {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved: List<ResolveInfo> = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.getOrElse { error ->
            Log.w(TAG, "query launcher packages failed: ${error.message}")
            emptyList()
        }
        return resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    private companion object {
        private const val TAG = "Morg"

        /**
         * 首次采样的回看窗口。
         *
         * 取 6 小时：小窗可能开了很久，窗口太短会让「刚启动」时识别不到它。
         * 只查一次，代价可接受。
         */
        private const val INITIAL_LOOKBACK_MS = 6 * 60 * 60_000L

        /** 状态栏/导航/系统弹层，手柄出现在这些之上没有意义。 */
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.settings",
        )

        /** 系统外观覆盖包（`*overlay*`）不需要手柄。 */
        private const val SYSTEM_PACKAGE_PREFIX = "com.android.internal.systemui"
    }
}
