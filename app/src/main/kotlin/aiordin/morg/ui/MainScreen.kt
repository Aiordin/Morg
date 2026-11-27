package aiordin.morg.ui

import aiordin.morg.MorgApp
import aiordin.morg.R
import aiordin.morg.core.SecretTapCounter
import aiordin.morg.core.TapResult
import aiordin.morg.launch.WindowSupport
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * 主界面：字标、开关、模式、位置设定、状态说明。
 *
 * 只做一件事 —— 把所有影响手柄行为的开关放在一屏里，每一项都即时生效，
 * 不需要「保存」按钮。
 *
 * 顶部的 ASCII 字标兼作彩蛋入口：连点 7 次打开构建信息页（[onOpenBuildInfo]）。
 */
@Composable
public fun MainScreen(
    app: MorgApp,
    windowSupport: WindowSupport,
    hasOverlayPermission: Boolean,
    hasUsageAccess: Boolean,
    onServiceChecked: (Boolean) -> Unit,
    onWindowChecked: (Boolean) -> Unit,
    onOpenBuildInfo: () -> Unit,
) {
    val context = LocalContext.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val tapCounter = remember { SecretTapCounter() }

    // 提示条自绘：只需要「短暂浮在底部」，不依赖系统 Toast 的行为。
    // 浮层随 MainScreen 一起卸载，跳详情页时自然消失。
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(HINT_VISIBLE_MS)
            hint = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsciiBanner(
                art = MORG_ASCII,
                modifier = Modifier.padding(top = AsciiBannerTopPadding),
                onClick = {
                    when (val result = tapCounter.tap(SystemClock.uptimeMillis())) {
                        TapResult.Unlocked -> {
                            hint = null
                            onOpenBuildInfo()
                        }

                        is TapResult.Progress -> {
                            // 只在最后三次给提示：一上来就说「点我 7 次」就把彩蛋变成说明书了。
                            if (result.remaining <= HINT_FROM_REMAINING) {
                                hint = context.resources.getQuantityString(
                                    R.plurals.build_secret_remaining,
                                    result.remaining,
                                    result.remaining,
                                )
                            }
                        }
                    }
                },
            )

            // —— 运行时开关 ——
            SettingCard(title = stringResource(R.string.section_service)) {
                SwitchRow(
                    title = stringResource(R.string.service_enabled),
                    subtitle = stringResource(R.string.service_enabled_desc),
                    checked = settings.serviceEnabled,
                    // 首次开启要走权限引导，所以这里不因缺权限而禁用。
                    enabled = true,
                    onCheckedChange = onServiceChecked,
                )
                if (!hasOverlayPermission) {
                    ActionRow(
                        text = stringResource(R.string.grant_overlay),
                        onClick = {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:${context.packageName}".toUri(),
                                ),
                            )
                        },
                    )
                }
                if (!hasUsageAccess) {
                    ActionRow(
                        text = stringResource(R.string.grant_usage_access),
                        onClick = {
                            context.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
                        },
                    )
                }
            }

            // —— 打开方式：关 = 全屏，开 = 小窗 ——
            SettingCard(title = stringResource(R.string.section_mode)) {
                SwitchRow(
                    title = stringResource(R.string.window_mode_enabled),
                    subtitle = stringResource(R.string.window_mode_enabled_desc),
                    checked = settings.windowModeEnabled,
                    // 两个支持都没有时不可开：否则用户看到「开」，实际只会得到全屏。
                    enabled = windowSupport != WindowSupport.Unavailable,
                    onCheckedChange = onWindowChecked,
                )
                if (windowSupport != WindowSupport.Ready) {
                    WindowSupportHint(windowSupport)
                }
            }

            // —— 位置 ——
            SettingCard(title = stringResource(R.string.section_position)) {
                SwitchRow(
                    title = stringResource(R.string.position_editing),
                    subtitle = stringResource(R.string.position_editing_desc),
                    checked = settings.positionEditing,
                    enabled = settings.serviceEnabled,
                    onCheckedChange = { app.settings.setPositionEditing(it) },
                )
                Text(
                    text = stringResource(
                        R.string.current_edge,
                        stringResource(
                            if (settings.placement.side == aiordin.morg.overlay.DockSide.Left) {
                                R.string.edge_left
                            } else {
                                R.string.edge_right
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.footer_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }

        // 倒数提示：浮在底部，与系统 Toast 的位置/观感一致，但完全由我们控制。
        if (hint != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = HINT_BOTTOM_PADDING),
            ) {
                Text(
                    text = hint.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(HINT_CORNER_RADIUS))
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** 小窗能力不足时的说明与引导。 */
@Composable
private fun WindowSupportHint(support: WindowSupport) {
    val context = LocalContext.current
    val text = when (support) {
        WindowSupport.Ready -> return
        WindowSupport.FreeformOnly -> stringResource(R.string.window_freeform_only)
        WindowSupport.Unavailable -> stringResource(R.string.window_unavailable)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        ActionRow(
            text = stringResource(R.string.open_developer_options),
            onClick = {
                runCatching {
                    context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.onFailure {
                    context.startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
                }
            },
        )
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

/**
 * 剩余次数小于等于它时开始提示。
 *
 * 3 = 倒数三次（还剩 3 / 2 / 1 次）。再小就只有两下，用户来不及意识到「这是在倒数」；
 * 再大就把彩蛋变成说明书了。
 */
private const val HINT_FROM_REMAINING = 3

/**
 * 提示在屏幕上停留多久。
 *
 * 与 `SecretTapCounter.DEFAULT_RESET_AFTER_MS`（1.5s） 对齐：只要连点没有中断，
 * 每一下都能看到提示；点得慢一点（~1s 一下）也不会闪。再长就会互相叠着，
 * 倒数看起来像卡在同一个数上。
 */
private const val HINT_VISIBLE_MS = 1_500L

/** 提示距底部的距离，大致对齐系统 Toast 的位置。 */
private val HINT_BOTTOM_PADDING = 96.dp

private val HINT_CORNER_RADIUS = 24.dp
