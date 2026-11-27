package aiordin.morg.ui

import aiordin.morg.R
import aiordin.morg.launch.WindowSupport
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 首次进入应用的欢迎页。
 *
 * 只讲有哪些入口，权限引导留在第一次打开「边缘手柄」时。
 */
@Composable
public fun WelcomeScreen(onStart: () -> Unit) {
    GuidePage(
        title = stringResource(R.string.welcome_title),
        body = stringResource(R.string.welcome_body),
        bullets = listOf(
            stringResource(R.string.welcome_point_service_title) to
                stringResource(R.string.welcome_point_service_body),
            stringResource(R.string.welcome_point_window_title) to
                stringResource(R.string.welcome_point_window_body),
            stringResource(R.string.welcome_point_toolbar_title) to
                stringResource(R.string.welcome_point_toolbar_body),
        ),
        primaryLabel = stringResource(R.string.welcome_start),
        onPrimary = onStart,
    )
}

/**
 * 「打开方式」开关第一次打开时的引导。
 *
 * 按当前小窗能力给不同的主按钮：没有能力 → 去开发者选项；
 * 有能力但未确认 → 先试一次；已确认 → 直接开。
 */
@Composable
public fun WindowGuideScreen(
    windowSupport: WindowSupport,
    windowConfirmed: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onTryWindow: () -> Unit,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val unavailable = windowSupport == WindowSupport.Unavailable
    val statusText = when {
        unavailable -> stringResource(R.string.window_guide_status_unavailable)
        windowConfirmed -> stringResource(R.string.window_guide_status_confirmed)
        else -> stringResource(R.string.window_guide_status_unconfirmed)
    }

    val primaryLabel: String
    val onPrimary: () -> Unit
    val secondaryLabel: String?
    val onSecondary: (() -> Unit)?
    val tertiaryLabel: String?
    val onTertiary: (() -> Unit)?

    when {
        unavailable -> {
            primaryLabel = stringResource(R.string.open_developer_options)
            onPrimary = onOpenDeveloperOptions
            secondaryLabel = stringResource(R.string.window_guide_later)
            onSecondary = onDismiss
            tertiaryLabel = null
            onTertiary = null
        }

        !windowConfirmed -> {
            primaryLabel = stringResource(R.string.onboarding_try_window)
            onPrimary = onTryWindow
            secondaryLabel = stringResource(R.string.window_guide_enable_anyway)
            onSecondary = onEnable
            tertiaryLabel = stringResource(R.string.window_guide_later)
            onTertiary = onDismiss
        }

        else -> {
            primaryLabel = stringResource(R.string.window_guide_enable)
            onPrimary = onEnable
            secondaryLabel = stringResource(R.string.window_guide_later)
            onSecondary = onDismiss
            tertiaryLabel = null
            onTertiary = null
        }
    }

    GuidePage(
        title = stringResource(R.string.window_guide_title),
        body = stringResource(R.string.window_guide_body),
        statusLabel = stringResource(R.string.window_guide_status_label),
        statusText = statusText,
        statusOk = windowConfirmed,
        hint = if (unavailable) {
            stringResource(R.string.onboarding_step_freeform_hint_unavailable)
        } else {
            stringResource(R.string.window_guide_hint)
        },
        primaryLabel = primaryLabel,
        onPrimary = onPrimary,
        secondaryLabel = secondaryLabel,
        onSecondary = onSecondary,
        tertiaryLabel = tertiaryLabel,
        onTertiary = onTertiary,
    )
}

/** 两段引导共用的版式。 */
@Composable
private fun GuidePage(
    title: String,
    body: String,
    bullets: List<Pair<String, String>> = emptyList(),
    statusLabel: String? = null,
    statusText: String? = null,
    statusOk: Boolean = false,
    hint: String? = null,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryLabel: String? = null,
    onTertiary: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (bullets.isNotEmpty()) {
                BulletCard(bullets)
            }
            if (statusLabel != null && statusText != null) {
                GuideStatusCard(
                    label = statusLabel,
                    state = statusText,
                    ok = statusOk,
                )
            }
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = onPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(primaryLabel)
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                Text(secondaryLabel)
            }
        }
        if (tertiaryLabel != null && onTertiary != null) {
            TextButton(onClick = onTertiary, modifier = Modifier.fillMaxWidth()) {
                Text(tertiaryLabel)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** 三条以内的要点卡片，序号 + 标题 + 一句解释。 */
@Composable
private fun BulletCard(bullets: List<Pair<String, String>>) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            bullets.forEachIndexed { index, (pointTitle, pointBody) ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onPrimary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = pointTitle, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = pointBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 单项状态卡：对勾表示已确认，序号之外不再加图标。 */
@Composable
private fun GuideStatusCard(label: String, state: String, ok: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (ok) colorScheme.primary else colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (ok) "✓" else "!",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (ok) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = state,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) colorScheme.primary else colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
