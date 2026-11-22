package com.ai.assistance.quro.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 播种器 JVM 单测（纯 Kotlin + JUnit4，不依赖 Android 运行时）。
 * 覆盖：幂等、中文 id 可用性（genre-<slug>）、默认关（D2）/ 父技能默认开（D3）、
 * 父技能 references 落盘、失败可重试（R7）、frontmatter 透传。
 */
class SeedNovelSkillsTest {

    // ---------------- fakes ----------------

    private class FakeSink : NovelSkillSink {
        val skills = LinkedHashMap<String, SkillRepository.Skill>()
        val enabledCalls = mutableListOf<Pair<String, Boolean>>()
        val siblingCopies = mutableListOf<Pair<String, List<String>>>()

        override fun contains(id: String): Boolean = skills.containsKey(id)
        override fun insert(skill: SkillRepository.Skill) {
            skills[skill.id] = skill
        }
        override fun copySiblingAssets(id: String, assetPaths: List<String>) {
            siblingCopies += id to assetPaths
        }
        override fun setEnabled(id: String, enabled: Boolean) {
            enabledCalls += id to enabled
        }
    }

    private class FakeGuard : NovelSkillGuardStore {
        val values = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }
    }

    /** SKILL_MD 的最小合法形态；frontmatter 只含三字段（B3 多余的 id 字段由总装删除）。 */
    private val skillMd = """
        ---
        name: 某技能
        description: 规约与触发场景（测试用描述）
        version: 1.0.0
        ---
        # 正文
        保留的正文。
    """.trimIndent()

    private val reader: (String) -> String = { skillMd }

    /** 镜像 SkillRepository.parseSkillMd 的三字段解析（BOM/CRLF 已由 lines() 处理）。 */
    private fun parseSkillMd(content: String): NovelParsedSkill? {
        val trimmed = content.removePrefix("\uFEFF").trimStart()
        if (!trimmed.startsWith("---")) return null
        val lines = trimmed.lines()
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null
        var description = ""
        var version = "1.0.0"
        for (i in 1 until end) {
            val colon = lines[i].indexOf(':')
            if (colon < 0) continue
            when (lines[i].substring(0, colon).trim().lowercase()) {
                "description" -> description = lines[i].substring(colon + 1).trim()
                "version" -> version = lines[i].substring(colon + 1).trim()
            }
        }
        val body = if (end + 1 < lines.size) {
            lines.subList(end + 1, lines.size).joinToString("\n").trim('\n')
        } else ""
        return NovelParsedSkill(description, version, body)
    }

    private fun seed(
        sink: FakeSink = FakeSink(),
        guard: FakeGuard = FakeGuard(),
        assetReader: (String) -> String = reader,
    ): Int = seedBuiltinNovelSkills(
        sink = sink,
        guardStore = guard,
        assetReader = assetReader,
        parseSkillMd = { content -> parseSkillMd(content) },
    )

    /** id_map.json 的 25 个 new_id，写死以便真正校验播种清单。 */
    private val expectedGenreIds = listOf(
        "genre-urban_zhuixu", "genre-urban_shenyi", "genre-urban_zhanshen",
        "genre-fantasy_qiandao", "genre-fantasy_renwu", "genre-fantasy_wudi",
        "genre-chuanyue_zhongtian", "genre-chuanyue_niandai", "genre-xuanyi_lingyi",
        "genre-xuanyi_daomu", "genre-guize_guaitan", "genre-xiancheng_zhenxing",
        "genre-xiuxian_zc", "genre-female_gaojian", "genre-qingxu_fafeng",
        "genre-changsheng_jiazu", "genre-tianming_fanpai", "genre-edu_nvpei",
        "genre-dushi_shouye", "genre-niandai_qinqing", "genre-zhiye_xt",
        "genre-nvxing_xingzhen", "genre-moyu_bailan", "genre-zhibo_nixi",
        "genre-honghuang_bianji",
    )

    // ---------------- 幂等 ----------------

    @Test
    fun `repeated seeding is idempotent`() {
        val sink = FakeSink()
        val guard = FakeGuard()

        assertEquals(26, seed(sink, guard)) // 25 流派 + 1 父技能
        assertEquals(26, sink.skills.size)
        assertEquals(true, guard.values[NovelSkillGuard.KEY_SEEDED])

        assertEquals(0, seed(sink, guard)) // 守卫命中
        assertEquals(26, sink.skills.size)
    }

    @Test
    fun `existing skill is never overwritten`() {
        val sink = FakeSink()
        val first = NovelSkillPack.GENRE_ENTRIES.first()
        sink.insert(
            SkillRepository.Skill(
                id = first.id,
                name = "用户改过的名字",
                description = "用户改过的描述",
                importSource = SkillRepository.ImportSource.FILE,
            )
        )

        assertEquals(25, seed(sink))
        assertEquals(26, sink.skills.size)
        assertEquals("用户改过的名字", sink.skills[first.id]!!.name)
        assertEquals("用户改过的描述", sink.skills[first.id]!!.description)
    }

    @Test
    fun `pre-set guard skips all seeding`() {
        val guard = FakeGuard().apply { putBoolean(NovelSkillGuard.KEY_SEEDED, true) }
        val sink = FakeSink()
        assertEquals(0, seed(sink, guard))
        assertTrue(sink.skills.isEmpty())
    }

    // ---------------- 中文 id 可用性 ----------------

    @Test
    fun `all 25 genre ids are ASCII genre-prefixed and match id_map`() {
        val entries = NovelSkillPack.GENRE_ENTRIES
        assertEquals(25, entries.size)
        assertEquals(expectedGenreIds, entries.map { it.id })
        assertEquals(26, NovelSkillPack.ENTRIES.size)
        assertEquals(NovelSkillGuard.PARENT_ID, NovelSkillPack.ENTRIES.last().id)

        for (entry in entries) {
            assertTrue(entry.id.startsWith(NovelSkillGuard.GENRE_ID_PREFIX))
            assertTrue("${entry.id} must be ASCII", entry.id.all { it.code in 0..127 })
            assertTrue("${entry.name} must keep Chinese display name", entry.name.any { it.code > 127 })
            assertEquals("skills-novel/${entry.id}/SKILL.md", entry.assetPath)
            assertEquals(entry.name, NovelSkillPack.NAMES_BY_ID[entry.id])
        }
    }

    @Test
    fun `slugify of Chinese name would be empty hence explicit id is required`() {
        // 镜像 SkillRepository.kt:1633-1636：中文名slugify后为空，add()会拒绝。
        fun slugify(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        assertEquals("", slugify("都市赘婿·隐忍爆发"))
        assertEquals("", slugify("无CP大女主·搞钱流"))
        assertEquals("genre-urban_zhuixu", NovelSkillPack.GENRE_ENTRIES.first().id)
    }

    // ---------------- 默认关 / 父技能默认开 ----------------

    @Test
    fun `genre skills default disabled while parent skill defaults enabled`() {
        val sink = FakeSink()
        seed(sink)

        assertEquals(26, sink.skills.size)
        for (id in expectedGenreIds) {
            assertFalse("$id must be disabled", sink.skills[id]!!.isEnabled)
        }
        assertTrue(sink.skills[NovelSkillGuard.PARENT_ID]!!.isEnabled)

        assertEquals(26, sink.enabledCalls.size)
        assertEquals(
            expectedGenreIds.associateWith { false } + (NovelSkillGuard.PARENT_ID to true),
            sink.enabledCalls.toMap(),
        )
    }

    @Test
    fun `parent skill is seeded with references copied`() {
        val sink = FakeSink()
        seed(sink)

        val parent = sink.skills[NovelSkillGuard.PARENT_ID]!!
        assertEquals(NovelSkillGuard.PARENT_ID, parent.id)
        assertEquals(NovelSkillGuard.PARENT_ID, parent.name)
        assertEquals("skills-novel/novel-writing/SKILL.md", NovelSkillPack.PARENT_ENTRY.assetPath)
        assertTrue(parent.isEnabled)
        assertEquals(SkillRepository.ImportSource.BUNDLED, parent.importSource)

        assertEquals(1, sink.siblingCopies.size)
        val (id, paths) = sink.siblingCopies.single()
        assertEquals(NovelSkillGuard.PARENT_ID, id)
        assertEquals(9, paths.size)
        assertEquals("skills-novel/novel-writing/references/outline.md", paths.first())
        assertEquals("skills-novel/novel-writing/references/style-tokens.md", paths.last())
        assertTrue("no genre skill should copy siblings", sink.siblingCopies.all { it.first == NovelSkillGuard.PARENT_ID })
    }

    @Test
    fun `frontmatter description version and body are preserved`() {
        val sink = FakeSink()
        seed(sink)

        val skill = sink.skills.values.first()
        assertEquals("规约与触发场景（测试用描述）", skill.description)
        assertEquals("1.0.0", skill.version)
        assertTrue(skill.body.contains("# 正文"))
        assertEquals(SkillRepository.ImportSource.BUNDLED, skill.importSource)
    }

    // ---------------- R7 失败可重试 ----------------

    @Test
    fun `bad asset is skipped and guard is only set after a complete pass`() {
        val badPath = NovelSkillPack.GENRE_ENTRIES[3].assetPath
        val failingReader: (String) -> String = { path ->
            if (path == badPath) throw IllegalStateException("asset missing") else skillMd
        }
        val guard = FakeGuard()
        val sink = FakeSink()

        assertEquals(25, seed(sink, guard, failingReader)) // 26 - 1 个坏资产
        assertEquals(false, guard.values[NovelSkillGuard.KEY_SEEDED] ?: false)
        assertFalse(sink.skills.containsKey(NovelSkillPack.GENRE_ENTRIES[3].id))

        // 下次启动重试：已存在的 25 条跳过，只补 1 条，然后守卫落定。
        assertEquals(1, seed(sink, guard))
        assertEquals(26, sink.skills.size)
        assertEquals(true, guard.values[NovelSkillGuard.KEY_SEEDED])
    }
}
