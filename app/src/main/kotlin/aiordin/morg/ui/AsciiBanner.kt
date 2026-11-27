package aiordin.morg.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * 主界面的 ASCII 字标。
 *
 * 纯 ASCII（figlet `standard`），刻意不使用 `█`/`═` 这些 Unicode 框线字符：
 * 框线字符在等宽字体里常常是双宽，行与行对不齐，也不能保证所有设备的字体都有。
 *
 * 每行 35 字符，五行。字母间留 2 列：figlet 原样只有 1 列，在手机等宽字体上
 * 相邻字母的竖笔几乎贴在一起，`R` 与 `G` 尤其难分。
 */
internal val MORG_ASCII: String = """
 __  __     ___     ____      ____
|  \/  |   / _ \   |  _ \    / ___|
| |\/| |  | | | |  | |_) |  | |  _
| |  | |  | |_| |  |  _ <   | |_| |
|_|  |_|   \___/   |_| \_\   \____|
""".trimStart('\n').trimEnd('\n', ' ')

/**
 * 主界面顶部的 ASCII 字标，装在和下方设置项一样的卡片里。
 *
 * 卡片不是为了好看：字标是「连点 7 次进详情页」的入口，纯文字没有任何可点的样子，
 * 用户点下去如果什么反应都没有，只会以为界面卡了。卡片的涟漪给出「按到了」的反馈，
 * 外框也让它和其他可交互区域保持一致的视觉语言。
 *
 * 字体大小按可用宽度自适应：固定字号在 360dp 屏幕上会溢出，在平板上又小得可怜。
 * 先用基准字号量一次，再按「目标宽度 / 实测宽度」线性换算 —— sp 与 px 在同一
 * density/fontScale 下是线性的，所以这次缩放是精确的。
 *
 * 但**只按宽度算不够**：横屏时可用宽度翻倍，按宽度换算出的字号会让字标占掉
 * 大半屏（实测 2414px 宽 → 字标高 530px，屏幕才 1080px 高），把下面的设置项全挤下去。
 * 所以再按屏幕高度给一个上限，取两者中小的。
 *
 * `softWrap = false`：万一字体度量与预估有偏差，宁可右侧溢出一点，
 * 也不能换行 —— 换行会把字标拆成两半，比略宽难看多了。
 */
@Composable
internal fun AsciiBanner(
    art: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // ASCII 字标读屏是一串无意义符号，直接换成应用名。
            .semantics(mergeDescendants = true) { contentDescription = "Morg" },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = CARD_PADDING,
                    end = CARD_PADDING,
                    top = CARD_PADDING_TOP,
                    bottom = CARD_PADDING_BOTTOM,
                ),
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val lineCount = art.lineSequence().count()

            // 基准字号固定，只用来量一次宽度。0.95 是行高比：等宽字体的默认行高
            // 约 1.2em，直接用会让 ASCII 五行之间散开、`_` 与 `|` 接不上。
            val fitSize = remember(art, widthPx, screenHeightDp) {
                val byWidth = if (widthPx <= 0f) {
                    BASE_SIZE_SP
                } else {
                    val measured = measurer.measure(
                        text = AnnotatedString(art),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = BASE_SIZE_SP.sp,
                            lineHeight = BASE_SIZE_SP.sp * LINE_HEIGHT_FACTOR,
                            letterSpacing = 0.sp,
                        ),
                        softWrap = false,
                    )
                    val measuredWidth = measured.size.width
                    if (measuredWidth <= 0) BASE_SIZE_SP else BASE_SIZE_SP * (widthPx / measuredWidth)
                }
                // 高度上限按 sp 算：`toSp` 已经除掉 density 与 fontScale，
                // 所以大字号无障碍设置下也不会算错。
                val byHeight = with(density) {
                    val maxHeightPx = MAX_SCREEN_HEIGHT_FRACTION * screenHeightDp.dp.toPx()
                    (maxHeightPx / (lineCount * LINE_HEIGHT_FACTOR)).toSp()
                }
                min(byWidth, byHeight.value).sp
            }

            // 字标本身不 fillMaxWidth：横屏时受高度上限约束，字号会比按宽度算的小，
            // 拉满宽度只会让右边缘空一大块。交给 Box 整体居中 ——
            // 不能用 `textAlign = Center`，那是逐行居中，行长差一列就会错位。
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = art,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fitSize,
                        lineHeight = fitSize * LINE_HEIGHT_FACTOR,
                        letterSpacing = 0.sp,
                    ),
                    color = color,
                    softWrap = false,
                    maxLines = lineCount,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

/** 测量用的基准字号。够大才能让换算的比例足够精确。 */
private const val BASE_SIZE_SP: Float = 64f

/** 行高与字号之比。见 [AsciiBanner] 里的说明。 */
private const val LINE_HEIGHT_FACTOR: Float = 0.95f

/**
 * 字标最多占屏幕高度的多少。
 *
 * 0.30 是竖屏不受影响、横屏刚好收住的分界：竖屏按宽度算出来约 15%，横屏原本会到 49%。
 * 不能再大，否则横屏下设置项全被挤到首屏之外。
 */
private const val MAX_SCREEN_HEIGHT_FRACTION: Float = 0.30f

/**
 * 卡片内边距。
 *
 * 与 `SettingCard` 的 16dp 左右对齐。纵向特意**上小下大**：
 * 等宽 ASCII 的最后一行是 `|_|  |_|   \___/ ...`，下缘几乎贴着字形基线，
 * 而 `lineHeight = 0.95em` 又把行间空白压掉了，上下对称时视觉上会觉得字标贴着底边。
 */
private val CARD_PADDING = 16.dp
private val CARD_PADDING_TOP = 12.dp
private val CARD_PADDING_BOTTOM = 26.dp

/** 字标上方留一点呼吸，避免贴住状态栏。 */
internal val AsciiBannerTopPadding = 8.dp
