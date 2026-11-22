package com.ai.assistance.quro.data.repository

/**
 * B1 基础设施批 —— OpenAnise-Novel 25 流派技能 + novel-writing 父技能的播种器。
 *
 * 设计约束（来自 B0_DECISIONS.md / SKILL_PORTING_PLAN §3.1、§4.5、§6）：
 *  - D1：id 显式取 id_map.json 的 new_id，形如 `genre-urban_zhuixu`（ASCII），
 *    UI 显示名保留 id_map.json 的中文 name。绝不能走 `SkillRepository.add()` /
 *    `importFromContent()` —— 它们用 `slugify(name)` 生成 id，中文名归一化后是
 *    空串会被拒绝（SkillRepository.kt:244-245、:494，方案 R1）。
 *  - D2：25 流派 `is_enabled = false` 默认关，入口交给 SessionSkillsSheet 按会话选用。
 *  - D3：父技能 `novel-writing` 是 6 角色能力的唯一入口，默认开启（true）；
 *    它的 references/ 兄弟文件由 [NovelSkillSink.copySiblingAssets] 一并落盘。
 *  - §4.5：复用 `insertDb + writeSkillMd` 手写通道（二者是 SkillRepository 的
 *    private 方法），播种器通过 [NovelSkillSink] 适配；同时保留一次性守卫 key。
 *  - R7：播种失败不得抛出 —— MinisApp.onCreate 中 SkillRepository 构造失败有
 *    GH#147「app permanently broken」事故史，整体 try-catch 且只记日志。
 *
 * 本文件是纯 Kotlin（仅依赖 SkillRepository 的嵌套 data class/enum），
 * 可由 JVM 单测直接驱动；Android 侧的 Context/SQLite/SharedPreferences 全部
 * 通过接口注入，见 INTEGRATION_NOTES.txt。
 */

/**
 * 一个待播种技能。流派：id 来自 id_map.json 的 new_id，name 为中文显示名，
 * 默认关闭（D2）；父技能 novel-writing：id 固定 ASCII slug，默认开启（D3），
 * 且附带 references 兄弟文件需要一并落盘。
 */
data class NovelSeedEntry(
    val id: String,
    val name: String,
    val assetPath: String,
    val enabledByDefault: Boolean = false,
    /** 相对于 ASSET_ROOT/<id>/ 的兄弟文件路径，如 references/outline.md。 */
    val siblingAssets: List<String> = emptyList(),
)

/** 25 个流派清单，严格对齐 id_map.json（顺序亦一致）。 */
object NovelSkillPack {
    val GENRE_ENTRIES: List<NovelSeedEntry> = listOf(
        NovelSeedEntry("genre-urban_zhuixu", "都市赘婿·隐忍爆发", NovelSkillGuard.assetPath("genre-urban_zhuixu")),
        NovelSeedEntry("genre-urban_shenyi", "都市神医·一针定乾坤", NovelSkillGuard.assetPath("genre-urban_shenyi")),
        NovelSeedEntry("genre-urban_zhanshen", "都市战神·龙帅归来", NovelSkillGuard.assetPath("genre-urban_zhanshen")),
        NovelSeedEntry("genre-fantasy_qiandao", "玄幻签到·开局无敌", NovelSkillGuard.assetPath("genre-fantasy_qiandao")),
        NovelSeedEntry("genre-fantasy_renwu", "玄幻系统·任务狂魔", NovelSkillGuard.assetPath("genre-fantasy_renwu")),
        NovelSeedEntry("genre-fantasy_wudi", "玄幻无敌·横推诸天", NovelSkillGuard.assetPath("genre-fantasy_wudi")),
        NovelSeedEntry("genre-chuanyue_zhongtian", "穿越种田·发家致富", NovelSkillGuard.assetPath("genre-chuanyue_zhongtian")),
        NovelSeedEntry("genre-chuanyue_niandai", "穿越年代·改革开放", NovelSkillGuard.assetPath("genre-chuanyue_niandai")),
        NovelSeedEntry("genre-xuanyi_lingyi", "悬疑灵异·捉鬼天师", NovelSkillGuard.assetPath("genre-xuanyi_lingyi")),
        NovelSeedEntry("genre-xuanyi_daomu", "盗墓探险·寻龙点穴", NovelSkillGuard.assetPath("genre-xuanyi_daomu")),
        NovelSeedEntry("genre-guize_guaitan", "规则怪谈·发疯破局流", NovelSkillGuard.assetPath("genre-guize_guaitan")),
        NovelSeedEntry("genre-xiancheng_zhenxing", "县城振兴·全民分红流", NovelSkillGuard.assetPath("genre-xiancheng_zhenxing")),
        NovelSeedEntry("genre-xiuxian_zc", "修仙职场·KPI考核流", NovelSkillGuard.assetPath("genre-xiuxian_zc")),
        NovelSeedEntry("genre-female_gaojian", "无CP大女主·搞钱流", NovelSkillGuard.assetPath("genre-female_gaojian")),
        NovelSeedEntry("genre-qingxu_fafeng", "情绪发疯·怼人变强流", NovelSkillGuard.assetPath("genre-qingxu_fafeng")),
        NovelSeedEntry("genre-changsheng_jiazu", "长生家族·千年底蕴流", NovelSkillGuard.assetPath("genre-changsheng_jiazu")),
        NovelSeedEntry("genre-tianming_fanpai", "天命反派·背景编辑流", NovelSkillGuard.assetPath("genre-tianming_fanpai")),
        NovelSeedEntry("genre-edu_nvpei", "恶毒女配·全家反派洗白流", NovelSkillGuard.assetPath("genre-edu_nvpei")),
        NovelSeedEntry("genre-dushi_shouye", "都市高武·守夜人流", NovelSkillGuard.assetPath("genre-dushi_shouye")),
        NovelSeedEntry("genre-niandai_qinqing", "年代重生·整顿亲情流", NovelSkillGuard.assetPath("genre-niandai_qinqing")),
        NovelSeedEntry("genre-zhiye_xt", "职业系统·超能力流", NovelSkillGuard.assetPath("genre-zhiye_xt")),
        NovelSeedEntry("genre-nvxing_xingzhen", "女性悬疑·刑侦流", NovelSkillGuard.assetPath("genre-nvxing_xingzhen")),
        NovelSeedEntry("genre-moyu_bailan", "摸鱼摆烂·变强流", NovelSkillGuard.assetPath("genre-moyu_bailan")),
        NovelSeedEntry("genre-zhibo_nixi", "直播短视频·逆袭流", NovelSkillGuard.assetPath("genre-zhibo_nixi")),
        NovelSeedEntry("genre-honghuang_bianji", "洪荒神话·编辑流", NovelSkillGuard.assetPath("genre-honghuang_bianji")),
    )

    /**
     * D3 父技能：6 角色能力的唯一入口，默认开启（与流派默认关不同）。
     * name 取 B3 frontmatter 的 `name: novel-writing`；frontmatter 里多出的
     * `id: novel-writing` 字段由 team-lead 总装前删除，播种器完全不依赖它。
     */
    val PARENT_ENTRY = NovelSeedEntry(
        id = NovelSkillGuard.PARENT_ID,
        name = NovelSkillGuard.PARENT_ID,
        assetPath = NovelSkillGuard.assetPath(NovelSkillGuard.PARENT_ID),
        enabledByDefault = true,
        siblingAssets = listOf(
            "references/outline.md",
            "references/character.md",
            "references/foreshadow.md",
            "references/polish.md",
            "references/review.md",
            "references/consistency.md",
            "references/delegation.md",
            "references/convergence.md",
            "references/style-tokens.md",
        ),
    )

    /** 全量播种清单：25 流派（默认关）+ 1 父技能（默认开）。 */
    val ENTRIES: List<NovelSeedEntry> = GENRE_ENTRIES + PARENT_ENTRY

    /** id -> 显示名，供 UI 直接使用；B4 的 genre 映射请取 id_map.json。 */
    val NAMES_BY_ID: Map<String, String> = ENTRIES.associate { it.id to it.name }
}

/**
 * 播种器与 SkillRepository 的桥接。[insert] 的实现必须是
 * `insertDb(skill); writeSkillMd(skill); mutateSkills { it + skill }`，
 * 即 §4.5 的手写通道，绕开 `add()` 的 slugify。
 */
interface NovelSkillSink {
    /** 库中是否已存在该 id（用于幂等：用户改过的技能绝不覆盖）。 */
    fun contains(id: String): Boolean

    /** 以显式 id 落库并写盘，body 已经填在 skill.body 中。 */
    fun insert(skill: SkillRepository.Skill)

    /**
     * 把 SKILL.md 之外的兄弟文件（父技能的 references/）复制到
     * `skillsDir/<id>/` 下，路径参数已含 references/ 前缀。
     * 默认空实现：流派技能是单文件，无需复制。
     */
    fun copySiblingAssets(id: String, assetPaths: List<String>) = Unit

    /** 显式设置全局开关：流派 false（D2），父技能 true（D3）。 */
    fun setEnabled(id: String, enabled: Boolean)
}

/** SKILL.md frontmatter 解析结果（与 SkillRepository.ParsedSkill 同构）。 */
data class NovelParsedSkill(
    val description: String,
    val version: String,
    val body: String,
)

/**
 * 幂等播种 25 个流派技能 + 1 个 novel-writing 父技能。
 *
 * @return 本次实际新增的技能数（守卫命中或全部已存在时返回 0）。
 *
 * 失败语义（R7）：单个 asset 缺失/解析失败只跳过该条并记日志，且整轮不设置
 * 守卫 key（下次启动续种）；数据库级异常由外层 try-catch 吞掉，同样不设置
 * 守卫。已插入项由 [NovelSkillSink.contains] 保证不重复。
 */
fun seedBuiltinNovelSkills(
    sink: NovelSkillSink,
    guardStore: NovelSkillGuardStore,
    assetReader: (assetPath: String) -> String,
    parseSkillMd: (content: String) -> NovelParsedSkill?,
    log: (message: String) -> Unit = {},
): Int {
    if (guardStore.getBoolean(NovelSkillGuard.KEY_SEEDED, false)) {
        log("seedBuiltinNovelSkills: guard hit, skip")
        return 0
    }

    var inserted = 0
    var assetFailures = 0
    try {
        for (entry in NovelSkillPack.ENTRIES) {
            if (sink.contains(entry.id)) {
                log("seedBuiltinNovelSkills: ${entry.id} exists, skip")
                continue
            }
            val parsed = try {
                parseSkillMd(assetReader(entry.assetPath))
            } catch (t: Throwable) {
                log("seedBuiltinNovelSkills: read/parse ${entry.assetPath} failed: ${t.message}")
                null
            }
            if (parsed == null) {
                assetFailures++
                log("seedBuiltinNovelSkills: invalid SKILL.md at ${entry.assetPath}, skip")
                continue
            }

            // 兄弟文件先落盘：失败则该条完全不入库，下次启动能整体重试。
            if (entry.siblingAssets.isNotEmpty()) {
                try {
                    sink.copySiblingAssets(
                        entry.id,
                        entry.siblingAssets.map { NovelSkillGuard.siblingAssetPath(entry.id, it) },
                    )
                } catch (t: Throwable) {
                    assetFailures++
                    log("seedBuiltinNovelSkills: copy siblings of ${entry.id} failed: ${t.message}")
                    continue
                }
            }

            val skill = SkillRepository.Skill(
                id = entry.id,                       // D1: 显式 ASCII id，绕开 slugify
                name = entry.name,                   // 中文显示名原样保留
                description = parsed.description,    // 取 B2/B3 frontmatter，不依赖 id 字段
                version = parsed.version.ifBlank { "1.0.0" },
                importSource = SkillRepository.ImportSource.BUNDLED,
                isEnabled = entry.enabledByDefault,  // 流派 false（D2）/ 父技能 true（D3）
                body = parsed.body,
            )
            sink.insert(skill)
            sink.setEnabled(entry.id, entry.enabledByDefault)
            inserted++
            log("seedBuiltinNovelSkills: seeded ${entry.id} (${entry.name})")
        }
        // 有资产读取/解析失败时绝不落守卫：下次启动继续补种缺失的技能，
        // 已成功插入的项由 sink.contains 幂等跳过。
        if (assetFailures == 0) {
            guardStore.putBoolean(NovelSkillGuard.KEY_SEEDED, true)
        }
        log("seedBuiltinNovelSkills: done, inserted=$inserted, assetFailures=$assetFailures")
    } catch (t: Throwable) {
        // 绝不向上传播（R7）：守卫不落 true，下次启动靠幂等续种。
        log("seedBuiltinNovelSkills failed (will retry): ${t.message}")
    }
    return inserted
}
