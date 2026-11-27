package aiordin.morg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 构建信息的文本格式。都是详情页上一眼能看到的东西。 */
class BuildInfoTest {

    @Test
    fun `git hash shows dirty working tree`() {
        assertEquals("a1b2c3d4", BuildInfoFormat.git("a1b2c3d4", dirty = false))
        assertEquals("a1b2c3d4 (dirty)", BuildInfoFormat.git("a1b2c3d4", dirty = true))
    }

    @Test
    fun `missing git falls back to unknown`() {
        // 归档源码包、CI 浅克隆失败时 git 会返回空串：不能显示成空白的字段。
        assertEquals(BuildInfoFormat.UNKNOWN, BuildInfoFormat.git("", dirty = true))
        assertEquals(BuildInfoFormat.UNKNOWN, BuildInfoFormat.git("  ", dirty = false))
        assertEquals(BuildInfoFormat.UNKNOWN, BuildInfoFormat.git("unknown", dirty = true))
    }

    @Test
    fun `sdk line lists all three levels`() {
        assertEquals("compile 35 · min 29 · target 34", BuildInfoFormat.sdk(35, 29, 34))
    }

    @Test
    fun `screen line uses the real pixel size`() {
        assertEquals("1080 × 2414 · 480dpi", BuildInfoFormat.screen(1080, 2414, 480))
    }

    @Test
    fun `android line pairs release with sdk`() {
        assertEquals("14 (SDK 34)", BuildInfoFormat.android("14", 34))
        // 个别定制 ROM 上 RELEASE 可能为空。
        assertEquals("unknown (SDK 34)", BuildInfoFormat.android("", 34))
    }

    @Test
    fun `abis are joined and empty falls back`() {
        assertEquals("arm64-v8a, armeabi-v7a", BuildInfoFormat.abis(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(BuildInfoFormat.UNKNOWN, BuildInfoFormat.abis(emptyList()))
    }

    @Test
    fun `version pairs name with code`() {
        assertEquals("0.1.0 (1)", BuildInfoFormat.version("0.1.0", 1))
        assertEquals("unknown (1)", BuildInfoFormat.version("", 1))
    }

    @Test
    fun `repository link points at the project on github`() {
        assertEquals("https://github.com/Aiordin/morg", ProjectLinks.REPOSITORY_URL)
        assertEquals("Aiordin/morg", ProjectLinks.REPOSITORY_LABEL)
        assertEquals("MIT", ProjectLinks.LICENSE)
    }

    @Test
    fun `commit link is built from a usable hash`() {
        assertEquals(
            "https://github.com/Aiordin/morg/commit/a1b2c3d4",
            ProjectLinks.commit("a1b2c3d4"),
        )
        // dirty 后缀不能带进 URL：`/commit/a1b2c3d4 (dirty)` 会被当成 404 路径。
        assertEquals(
            "https://github.com/Aiordin/morg/commit/a1b2c3d4",
            ProjectLinks.commit("a1b2c3d4 (dirty)"),
        )
    }

    @Test
    fun `commit link is omitted without a real hash`() {
        // 归档源码包、浅克隆失败时没有 hash：宁可不可点，也不要链到 404。
        assertNull(ProjectLinks.commit(""))
        assertNull(ProjectLinks.commit("   "))
        assertNull(ProjectLinks.commit(BuildInfoFormat.UNKNOWN))
    }

    @Test
    fun `info entry is only a link when a url is given`() {
        assertNull(InfoEntry("Git", "a1b2c3d4").url)
        assertEquals(
            ProjectLinks.REPOSITORY_URL,
            InfoEntry("Source", ProjectLinks.REPOSITORY_LABEL, ProjectLinks.REPOSITORY_URL).url,
        )
    }
}
