package com.ai.assistance.quro.data.repository

/**
 * B1 基础设施批 —— 播种守卫 key 与 id/路径约定常量。
 *
 * 守卫 key 必须**新开**，不得复用基座的 `installBundledSkills` 相关键：
 * 基座只装 `skill-creator`，与本 25 流派 + 1 父技能是两套独立播种生命周期。
 */

object NovelSkillGuard {
    /** SharedPreferences 文件名（Android 侧适配用；见 INTEGRATION_NOTES.txt）。 */
    const val PREFS_NAME = "novel_skills_guard"

    /**
     * 一次性播种守卫。方案 §4.5 原 key 为 `builtin_novel_skills_v1`；
     * 加入 novel-writing 父技能后升版为 `..._v2`，确保已经跑过 v1 的设备
     * 在升级后仍会执行一轮播种（已有的 25 个流派按 id 幂等跳过，只补父技能）。
     * 将来资产内容升级需要再次重播时，继续升版即可。
     */
    const val KEY_SEEDED = "builtin_novel_skills_v2"

    /** D1：流派技能 id 前缀，区别于基座 bundled 与用户导入技能。 */
    const val GENRE_ID_PREFIX = "genre-"

    /** D3：父技能 id（ASCII slug，slugify("novel-writing") 正好得到它）。 */
    const val PARENT_ID = "novel-writing"

    /** assets 根目录（必须放 src/main/assets，release 也打包；方案 §6 证据 9）。 */
    const val ASSET_ROOT = "skills-novel"

    /** 资产相对路径：<ASSET_ROOT>/<id>/SKILL.md，兼容 importFromArchive 的一层目录约定。 */
    fun assetPath(id: String): String = "$ASSET_ROOT/$id/SKILL.md"

    /** 父技能 references 的 assets 路径（相对于 ASSET_ROOT/<id>/）。 */
    fun siblingAssetPath(id: String, relativePath: String): String =
        "$ASSET_ROOT/$id/$relativePath"
}

/**
 * 守卫存储抽象。生产实现 = SharedPreferences 适配器（见 INTEGRATION_NOTES.txt）；
 * 单测实现 = 内存 Map。这样播种器本身保持纯 JVM 可测。
 */
interface NovelSkillGuardStore {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}
