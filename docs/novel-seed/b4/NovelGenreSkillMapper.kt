package com.ai.assistance.quro.core.novel

/**
 * 作品 genre 文本 → 流派技能 id 映射（B4 联动批）。
 * 25 条映射由 C://1111//openanise-skills//id_map.json 自动生成，勿手写修改。
 * 对齐 D1 决策：流派技能 id = genre-<slug>，UI 仍显示中文名。
 */
object NovelGenreSkillMapper {
    private data class Entry(val genreId: String, val skillId: String, val names: List<String>)

    private val ENTRIES = listOf(
    Entry("urban_zhuixu", "genre-urban_zhuixu", listOf("都市赘婿·隐忍爆发", "都市赘婿", "隐忍爆发")),
    Entry("urban_shenyi", "genre-urban_shenyi", listOf("都市神医·一针定乾坤", "都市神医", "一针定乾坤")),
    Entry("urban_zhanshen", "genre-urban_zhanshen", listOf("都市战神·龙帅归来", "都市战神", "龙帅归来")),
    Entry("fantasy_qiandao", "genre-fantasy_qiandao", listOf("玄幻签到·开局无敌", "玄幻签到", "开局无敌")),
    Entry("fantasy_renwu", "genre-fantasy_renwu", listOf("玄幻系统·任务狂魔", "玄幻系统", "任务狂魔")),
    Entry("fantasy_wudi", "genre-fantasy_wudi", listOf("玄幻无敌·横推诸天", "玄幻无敌", "横推诸天")),
    Entry("chuanyue_zhongtian", "genre-chuanyue_zhongtian", listOf("穿越种田·发家致富", "穿越种田", "发家致富")),
    Entry("chuanyue_niandai", "genre-chuanyue_niandai", listOf("穿越年代·改革开放", "穿越年代", "改革开放")),
    Entry("xuanyi_lingyi", "genre-xuanyi_lingyi", listOf("悬疑灵异·捉鬼天师", "悬疑灵异", "捉鬼天师")),
    Entry("xuanyi_daomu", "genre-xuanyi_daomu", listOf("盗墓探险·寻龙点穴", "盗墓探险", "寻龙点穴")),
    Entry("guize_guaitan", "genre-guize_guaitan", listOf("规则怪谈·发疯破局流", "规则怪谈", "发疯破局流")),
    Entry("xiancheng_zhenxing", "genre-xiancheng_zhenxing", listOf("县城振兴·全民分红流", "县城振兴", "全民分红流")),
    Entry("xiuxian_zc", "genre-xiuxian_zc", listOf("修仙职场·KPI考核流", "修仙职场", "KPI考核流")),
    Entry("female_gaojian", "genre-female_gaojian", listOf("无CP大女主·搞钱流", "无CP大女主", "搞钱流")),
    Entry("qingxu_fafeng", "genre-qingxu_fafeng", listOf("情绪发疯·怼人变强流", "情绪发疯", "怼人变强流")),
    Entry("changsheng_jiazu", "genre-changsheng_jiazu", listOf("长生家族·千年底蕴流", "长生家族", "千年底蕴流")),
    Entry("tianming_fanpai", "genre-tianming_fanpai", listOf("天命反派·背景编辑流", "天命反派", "背景编辑流")),
    Entry("edu_nvpei", "genre-edu_nvpei", listOf("恶毒女配·全家反派洗白流", "恶毒女配", "全家反派洗白流")),
    Entry("dushi_shouye", "genre-dushi_shouye", listOf("都市高武·守夜人流", "都市高武", "守夜人流")),
    Entry("niandai_qinqing", "genre-niandai_qinqing", listOf("年代重生·整顿亲情流", "年代重生", "整顿亲情流")),
    Entry("zhiye_xt", "genre-zhiye_xt", listOf("职业系统·超能力流", "职业系统", "超能力流")),
    Entry("nvxing_xingzhen", "genre-nvxing_xingzhen", listOf("女性悬疑·刑侦流", "女性悬疑", "刑侦流")),
    Entry("moyu_bailan", "genre-moyu_bailan", listOf("摸鱼摆烂·变强流", "摸鱼摆烂", "变强流")),
    Entry("zhibo_nixi", "genre-zhibo_nixi", listOf("直播短视频·逆袭流", "直播短视频", "逆袭流")),
    Entry("honghuang_bianji", "genre-honghuang_bianji", listOf("洪荒神话·编辑流", "洪荒神话", "编辑流")),
    )

    /** 用户输入的 genre 文本 → 流派技能 id；无法匹配返回 null。 */
    fun toSkillId(genre: String?): String? {
        if (genre.isNullOrBlank()) return null
        val raw = genre.trim()
        val lower = raw.lowercase()
        return ENTRIES.firstOrNull { e ->
            lower == e.genreId || lower == e.skillId ||
            e.names.any { alias ->
                alias == raw || (raw.length >= 2 && (alias.contains(raw) || raw.contains(alias)))
            }
        }?.skillId
    }

    /** 给定技能 id 是否是流派技能（供 D4 前 60 行注入判断用）。 */
    fun isGenreSkill(skillId: String): Boolean = skillId.startsWith("genre-")
}
