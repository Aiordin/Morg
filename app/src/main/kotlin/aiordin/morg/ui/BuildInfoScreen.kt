package aiordin.morg.ui

import aiordin.morg.BuildConfig
import aiordin.morg.R
import aiordin.morg.core.BuildInfoFormat
import aiordin.morg.core.InfoEntry
import aiordin.morg.core.InfoSection
import aiordin.morg.core.ProjectLinks
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri

/**
 * 构建信息详情页。
 *
 * 由主界面 ASCII 字标连点 7 次进入（见 [MORG_ASCII] 与 `SecretTapCounter`）。
 * 目的是排查现场：用户反馈问题时，截图里就能看到版本、Git 提交和构建时间，
 * 不必再问「你装的是哪个包」。
 */
@Composable
public fun BuildInfoScreen(
    sections: List<InfoSection>,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "←",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.build_info_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        sections.forEach { section ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    section.entries.forEach { InfoRow(it) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 一行信息：标签固定宽度，值等宽字体。
 *
 * 值用等宽字体是为了让 Git hash、SDK 数字这类内容上下对齐，
 * 也好手工比对报错信息里的字符串。
 *
 * 带 [InfoEntry.url] 的行整行可点，末尾加 `↗` 暗示它能跳出去。
 */
@Composable
private fun InfoRow(entry: InfoEntry) {
    val context = LocalContext.current
    val url = entry.url
    val modifier = if (url == null) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button) { openLink(context, url) }
            .padding(vertical = 2.dp)
    }
    Row(modifier) {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        Text(
            text = if (url == null) entry.value else "${entry.value}  ↗",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
            color = if (url == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 用系统浏览器打开链接。
 *
 * 点一下没反应比报错更让人困惑（个别精简 ROM 没有浏览器），所以失败时给一句提示。
 */
private fun openLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, R.string.build_link_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 收集本机与本次构建的信息。
 *
 * 只在进入详情页时算一次 —— 这些值在一次运行里不会变，
 * 反复读 `PackageManager` 没有意义。
 */
@Composable
public fun rememberBuildInfoSections(): List<InfoSection> {
    val context = LocalContext.current
    return remember(context) { collectBuildInfo(context) }
}

private fun collectBuildInfo(context: Context): List<InfoSection> {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val metrics = DisplayMetrics().also { dm ->
        @Suppress("DEPRECATION")
        context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.getRealMetrics(dm)
    }
    if (metrics.widthPixels == 0) {
        // 极少数情况下 Display 拿不到，退回 resources 的 metrics（不含系统栏，够用）。
        val fallback = context.resources.displayMetrics
        metrics.setTo(fallback)
    }

    return listOf(
        InfoSection(
            title = context.getString(R.string.build_section_app),
            entries = listOf(
                InfoEntry(
                    context.getString(R.string.build_version),
                    BuildInfoFormat.version(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()),
                ),
                InfoEntry(context.getString(R.string.build_package), context.packageName),
                InfoEntry(context.getString(R.string.build_type), BuildConfig.BUILD_TYPE),
                // 仓库地址写死在 BuildConfig 之外：它是项目身份，不随构建变化。
                InfoEntry(
                    label = context.getString(R.string.build_source),
                    value = ProjectLinks.REPOSITORY_LABEL,
                    url = ProjectLinks.REPOSITORY_URL,
                ),
                InfoEntry(context.getString(R.string.build_license), ProjectLinks.LICENSE),
            ),
        ),
        InfoSection(
            title = context.getString(R.string.build_section_build),
            entries = listOf(
                InfoEntry(
                    context.getString(R.string.build_git),
                    BuildInfoFormat.git(BuildConfig.GIT_HASH, BuildConfig.GIT_DIRTY),
                    // 有有效 hash 才能链到提交页；`unknown` 时这一行只是文字。
                    url = ProjectLinks.commit(BuildConfig.GIT_HASH),
                ),
                InfoEntry(context.getString(R.string.build_stamp), BuildConfig.BUILD_STAMP),
                InfoEntry(
                    context.getString(R.string.build_sdk),
                    BuildInfoFormat.sdk(
                        compileSdk = BuildConfig.COMPILE_SDK,
                        minSdk = packageInfo?.applicationInfo?.minSdkVersion ?: 0,
                        targetSdk = packageInfo?.applicationInfo?.targetSdkVersion ?: 0,
                    ),
                ),
            ),
        ),
        InfoSection(
            title = context.getString(R.string.build_section_device),
            entries = listOf(
                InfoEntry(
                    context.getString(R.string.build_device),
                    "${Build.MANUFACTURER} ${Build.MODEL}",
                ),
                InfoEntry(
                    context.getString(R.string.build_android),
                    BuildInfoFormat.android(Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                ),
                InfoEntry(
                    context.getString(R.string.build_screen),
                    BuildInfoFormat.screen(
                        width = metrics.widthPixels,
                        height = metrics.heightPixels,
                        densityDpi = metrics.densityDpi,
                    ),
                ),
                InfoEntry(
                    context.getString(R.string.build_abi),
                    BuildInfoFormat.abis(Build.SUPPORTED_ABIS.toList()),
                ),
            ),
        ),
    )
}

/** 标签列宽：够放下「构建时间」四个字，再宽就挤压值了。 */
private val LABEL_WIDTH = 76.dp
