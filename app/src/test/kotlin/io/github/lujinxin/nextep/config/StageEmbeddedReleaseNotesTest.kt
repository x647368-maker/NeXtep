package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageEmbeddedReleaseNotesTest {
    private val sample = """
        台前调度 0.9.16-dev（版本号 31）候选测试版

        修复普通点击交换时的缩放形变，保持原有四个真实小窗。

        ColorOS 真机尚未验证。拖动交换仍使用原来的动画。
    """.trimIndent()

    @Test fun currentBuildNotesAppearAsDevelopmentCandidate() {
        val result = StageEmbeddedReleaseNotes.parse("0.9.16-dev", 31, sample)
        assertNotNull(result)
        assertEquals("0.9.16-dev", result!!.version)
        assertTrue(result.status.contains("真机验收"))
        assertTrue(result.highlights.any { it.contains("拖动交换") })
    }

    @Test fun anotherBuildNotesAreNeverPresentedAsCurrent() {
        assertNull(StageEmbeddedReleaseNotes.parse("0.9.17-dev", 32, sample))
        assertNull(StageEmbeddedReleaseNotes.parse("0.9.16-dev", 310, sample))
        assertNull(StageEmbeddedReleaseNotes.parse("0.9.1", 31, sample))
    }

    @Test fun missingAndOversizedNotesFailClosed() {
        assertNull(StageEmbeddedReleaseNotes.parse("0.9.16-dev", 31, ""))
        assertNull(StageEmbeddedReleaseNotes.parse("0.9.16-dev", 31, "0.9.16-dev 31 " + "A".repeat(32769)))
    }

    @Test fun archivedBuildNotesRemainAvailableWithoutInstalledVersion() {
        val archived = StageEmbeddedReleaseNotes.archived(
            "0.9.17-dev", "台前调度 0.9.17-dev / 32\\n\\n新增稳定性测试，真机待验收。"
        )
        assertNotNull(archived)
        assertEquals("0.9.17-dev", archived!!.version)
        assertTrue(archived.status.contains("历史"))
        assertTrue(archived.caution.contains("不能代表"))
        assertNull(StageEmbeddedReleaseNotes.archived("0.9.18-dev", "台前调度 0.9.17-dev / 32"))
    }

    @Test fun specificReleaseHeadlineAppearsInsteadOfGenericBuildLabel() {
        val note = "台前调度 0.9.19-dev（版本号 34）｜三机统一集成与日志修正\n\nN5 和 Y700 最新候选功能。\n\n原生平行视窗仍待真机修复。"
        val parsed = StageEmbeddedReleaseNotes.parse("0.9.19-dev", 34, note)
        assertNotNull(parsed)
        assertEquals("三机统一集成与日志修正", parsed!!.title)
        assertTrue(parsed.highlights.any { it.contains("仍待真机修复") })
    }

    @Test fun stableBuildCannotBeMislabeledAsDevelopment() {
        val stable = StageEmbeddedReleaseNotes.parse("0.9.16", 31, "台前调度 0.9.16 / 31 发布说明\n\n支持更新日志。")
        assertNotNull(stable)
        assertFalse(stable!!.status.contains("开发版"))
    }
}
