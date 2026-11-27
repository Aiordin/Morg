package aiordin.morg.ui

import aiordin.morg.R
import aiordin.morg.core.Onboarding
import aiordin.morg.core.OnboardingStep
import aiordin.morg.core.PermissionStatus
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 边缘手柄开关第一次打开时的权限引导。
 *
 * 一次只讲一件事：标题、为什么需要、当前状态、一个动作按钮。
 * 三项权限的获取方式各不相同（两个跳系统设置、一个是运行时权限），
 * 混在一屏里会变成一张用户看不完的清单。
 *
 * 每一步都可以「稍后再说」：引导不是关卡，没给的权限主界面里还能补。
 * `onFinish` 只在 [Onboarding.isReady] 时顺手开启手柄，判定放在 `MainActivity`，
 * 这里不掺业务状态。
 *
 * 「小窗」与「工具栏」的引导不在这里：它们由各自开关第一次打开时触发，
 * 见 `ui/Guides`。
 */
@Composable
public fun OnboardingScreen(
    status: PermissionStatus,
    notificationsAvailable: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onOpenUsageSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onFinish: () -> Unit,
    onDismiss: () -> Unit,
) {
    val steps = Onboarding.steps(notificationsAvailable)
    var index by rememberSaveable { mutableIntStateOf(0) }

    // 步骤列表会变（设备升级到 Android 13），索引先夹紧再用。
    val safeIndex = index.coerceIn(0, steps.lastIndex)
    val step = steps[safeIndex]
    val satisfied = Onboarding.satisfied(step, status)
    val isLast = safeIndex == steps.lastIndex

    // 系统返回键回上一页，而不是直接退出应用。
    BackHandler(enabled = safeIndex > 0) { index = safeIndex - 1 }

    val act: () -> Unit = {
        when (step) {
            OnboardingStep.Overlay -> onOpenOverlaySettings()
            OnboardingStep.UsageAccess -> onOpenUsageSettings()
            OnboardingStep.Notifications -> onRequestNotifications()
        }
    }
    val advance: () -> Unit = {
        if (isLast) onFinish() else index = safeIndex + 1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${safeIndex + 1} / ${steps.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        StepProgress(
            fraction = (safeIndex + 1f) / steps.size,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(step.titleRes()),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(step.bodyRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StepStatusCard(
                label = stringResource(step.categoryRes()),
                state = stringResource(step.stateRes(satisfied)),
                satisfied = satisfied,
                number = safeIndex + 1,
            )
            Text(
                text = stringResource(step.hintRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = { if (satisfied) advance() else act() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text(stringResource(primaryLabelRes(step, satisfied, isLast)))
        }

        if (!satisfied) {
            TextButton(onClick = advance, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_later))
            }
        }
        if (!isLast) {
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_skip_all))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun primaryLabelRes(step: OnboardingStep, satisfied: Boolean, isLast: Boolean): Int = when {
    satisfied && isLast -> R.string.onboarding_finish
    satisfied -> R.string.onboarding_next
    step == OnboardingStep.Notifications -> R.string.onboarding_grant_notifications
    else -> R.string.onboarding_grant
}

/** 细进度条。用两个 Box 而不是 `LinearProgressIndicator`：高度可控，没有额外装饰。 */
@Composable
private fun StepProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** 当前这一项的状态：序号（未满足）或对勾（已满足）。 */
@Composable
private fun StepStatusCard(label: String, state: String, satisfied: Boolean, number: Int) {
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
                    .background(if (satisfied) colorScheme.primary else colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (satisfied) "✓" else number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (satisfied) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = state,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (satisfied) colorScheme.primary else colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun OnboardingStep.titleRes(): Int = when (this) {
    OnboardingStep.Overlay -> R.string.onboarding_step_overlay_title
    OnboardingStep.UsageAccess -> R.string.onboarding_step_usage_title
    OnboardingStep.Notifications -> R.string.onboarding_step_notifications_title
}

private fun OnboardingStep.bodyRes(): Int = when (this) {
    OnboardingStep.Overlay -> R.string.onboarding_step_overlay_body
    OnboardingStep.UsageAccess -> R.string.onboarding_step_usage_body
    OnboardingStep.Notifications -> R.string.onboarding_step_notifications_body
}

private fun OnboardingStep.hintRes(): Int = when (this) {
    OnboardingStep.Overlay -> R.string.onboarding_step_overlay_hint
    OnboardingStep.UsageAccess -> R.string.onboarding_step_usage_hint
    OnboardingStep.Notifications -> R.string.onboarding_step_notifications_hint
}

private fun OnboardingStep.categoryRes(): Int = when (this) {
    OnboardingStep.Overlay, OnboardingStep.UsageAccess -> R.string.onboarding_required
    OnboardingStep.Notifications -> R.string.onboarding_optional
}

private fun OnboardingStep.stateRes(satisfied: Boolean): Int =
    if (satisfied) R.string.onboarding_granted else R.string.onboarding_not_granted
