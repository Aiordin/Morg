package aiordin.morg.core

/**
 * 详情页里的一行：左边标签，右边值。
 *
 * [url] 非空时这一行可点击，用浏览器打开 —— 例如仓库主页、某次提交。
 * 链接计算留在纯逻辑层（[ProjectLinks]），UI 只负责把它交给系统。
 */
public data class InfoEntry(
    val label: String,
    val value: String,
    val url: String? = null,
)

/** 项目在 GitHub 上的地址。构建信息页与 README 用的是同一份事实。 */
public object ProjectLinks {

    public const val REPOSITORY_URL: String = "https://github.com/Aiordin/morg"

    /** 展示用的仓库名，不带协议前缀 —— 和 GitHub 页面上的写法一致。 */
    public const val REPOSITORY_LABEL: String = "Aiordin/morg"

    /**
     * 某次提交的网页地址；拿不到有效 hash 时返回 null。
     *
     * 不要在 hash 无效时也拼一个链接：`/commit/unknown` 是一个 404，
     * 点进去只会让用户以为仓库出了问题。
     *
     * @param hash `BuildConfig.GIT_HASH`，可能为空、`unknown`，或带 ` (dirty)` 后缀
     */
    public fun commit(hash: String): String? {
        val short = hash.trim().substringBefore(' ')
        if (short.isEmpty() || short == BuildInfoFormat.UNKNOWN) return null
        return "$REPOSITORY_URL/commit/$short"
    }

    /** 本项目使用的许可证。构建信息页直接展示它。 */
    public const val LICENSE: String = "MIT"
}

/** 详情页里的一组信息（对应一张卡片）。 */
public data class InfoSection(val title: String, val entries: List<InfoEntry>)

/**
 * 构建信息的文本格式化。
 *
 * 只做字符串拼接，不碰 Android 类型 —— 读设备信息的活留给 `BuildInfoScreen`，
 * 这里负责「怎么显示」。分开的好处是这些格式能在 JVM 上单测：
 * `git dirty` 的括号、`unknown` 的兜底都曾经在别处写错过。
 */
public object BuildInfoFormat {

    /** git 拿不到时的占位。不要写成空串 —— 详情页留白会让人以为字段丢了。 */
    public const val UNKNOWN: String = "unknown"

    /**
     * Git 一行：`a1b2c3d4`、`a1b2c3d4 (dirty)`，或 [UNKNOWN]。
     *
     * 未提交改动必须标出来：debug 包与仓库 HEAD 不一致时，
     * 「这个 hash 能重现问题」就是假的前提。
     */
    public fun git(hash: String, dirty: Boolean): String {
        val trimmed = hash.trim()
        if (trimmed.isEmpty() || trimmed == UNKNOWN) return UNKNOWN
        return if (dirty) "$trimmed (dirty)" else trimmed
    }

    /** SDK 一行：`compile 35 · min 29 · target 34`。 */
    public fun sdk(compileSdk: Int, minSdk: Int, targetSdk: Int): String =
        "compile $compileSdk · min $minSdk · target $targetSdk"

    /** 屏幕一行：`1080 × 2414 · 480dpi`。 */
    public fun screen(width: Int, height: Int, densityDpi: Int): String =
        "$width × $height · ${densityDpi}dpi"

    /** 系统一行：`14 (SDK 34)`。 */
    public fun android(release: String, sdkInt: Int): String =
        "${release.ifBlank { UNKNOWN }} (SDK $sdkInt)"

    /** ABI 一行：首选在前，无则 [UNKNOWN]。 */
    public fun abis(list: List<String>): String =
        if (list.isEmpty()) UNKNOWN else list.joinToString(", ")

    /** 版本一行：`0.1.0 (1)`。 */
    public fun version(name: String, code: Long): String =
        "${name.ifBlank { UNKNOWN }} ($code)"
}
