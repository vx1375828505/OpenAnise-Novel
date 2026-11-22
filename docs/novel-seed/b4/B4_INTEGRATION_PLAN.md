# B4 联动批 · 作品 genre → 流派技能 id 映射与注入点改动方案

> 负责人：team-lead ｜ 依据：SKILL_PORTING_PLAN §3.4 / §4.5 / §6，D1-D6 决策，远端 main 源码实测
> 状态：方案（未写代码，待团队 B1/B2 产出后接线）

## 1. 现状（源码实测，非推测）

| 项 | 文件:行 | 现状 |
|---|---|---|
| 作品流派字段 | `core/novel/NovelModels.kt:105-108` | `NovelWork.genre: String`，用户自由文本，`NovelRepository.kt:82` 处 `genre.trim()` 入库，`:245` 可改 |
| 会话技能开关 | `data/repository/SkillRepository.kt:301-317` | `isEnabledForSession` / `setSessionOverride(sessionId, skillId, enabled)` / `clearSessionOverrides` 已就绪 |
| 提示词注入点 | `SkillRepository.kt:349` `skillPromptFragment(sessionId)` | 注入 `<available_skills>`（name+截断 desc+path），上限 20、desc 200 字符，三级优先 bundled>7天>useCount |
| 注入点调用 | 方案 §6-11 `ChatViewModel.kt:16484-16490` | 已有 `skillRepository?.skillPromptFragment(activeSessionId)`，无需新增调用点 |
| UI | `ui/chat/SessionSkillsSheet.kt:215/228/265` | 已用 setSessionOverride，手动开关完整可用 |

**结论**：B4 不需要动 ChatViewModel 注入链路，不需要动 UI。只需要在「作品切换/创建」时自动调用已有的 `setSessionOverride`，把该作品 genre 对应的流派技能按 D2（默认关）之外的规则启用。

## 2. 核心难点

`NovelWork.genre` 是**自由文本**（用户可输「都市赘婿」「urban_zhuixu」「都市赘婿·隐忍爆发」等），而技能 id 是 `genre-<slug>`（如 `genre-urban_zhuixu`）。需要容错映射。

## 3. 改动清单（3 个 Kotlin 文件）

### 3.1 新增 `core/novel/NovelGenreSkillMapper.kt`

```
object NovelGenreSkillMapper {
    // 25 条映射，数据源 = id_map.json（genre_id / new_id / name）
    private data class Entry(val genreId: String, val skillId: String, val names: List<String>)
    private val ENTRIES = listOf(...)   // 由 B0 id_map.json 生成，勿手写

    /** 用户输入的 genre 文本 → 流派技能 id；无法匹配返回 null */
    fun toSkillId(genre: String?): String? {
        if (genre.isNullOrBlank()) return null
        val g = genre.trim().lowercase()
        // 1) 精确 slug：urban_zhuixu
        // 2) 带前缀：genre-urban_zhuixu
        // 3) 精确中文名：都市赘婿·隐忍爆发
        // 4) 包含匹配：输入「都市赘婿」命中 name 去后缀后的短名
        // 5) 别名表（从 name 派生：· 前后两段分别匹配）
        return ENTRIES.firstOrNull { e ->
            e.genreId == g || e.skillId == g ||
            e.names.any { it == genre.trim() || it.contains(genre.trim()) && genre.trim().length >= 2 }
        }?.skillId
    }
}
```

**生成方式**：从 `C:\1111\openanise-skills\id_map.json` 用脚本生成 ENTRIES 常量块（25 条），避免手写错。name 的短别名取 `name.split("·")[0]`。

### 3.2 改 `core/novel/NovelRepository.kt`（切换作品时联动）

在作品被选中/切换为当前作品的路径上（需定位 `activeWorkId` 或等价状态设置处），插入：

```kotlin
// 伪码：作品切换时，把上一作品的流派技能关闭、新作品的流派技能启用
fun onWorkActivated(sessionId: String, work: NovelWork?) {
    previousGenreSkillId?.let { skillRepository.setSessionOverride(sessionId, it, false) }
    val next = NovelGenreSkillMapper.toSkillId(work?.genre)?.also {
        skillRepository.setSessionOverride(sessionId, it, true)
    }
    previousGenreSkillId = next
}
```

**注意**：
- `setSessionOverride` 是 session 级，作品切换必须同时关闭上一流派，否则两个流派规约同时进 `<available_skills>`，D4 的强制度会叠加冲突。
- **⚠️ 待产品确认（超出 D2 已拍板范围）**：D2 只拍板了「默认关 + SessionSkillsSheet 入口」，并未授权「切换作品时自动启用对应流派」。此联动会引入两个语义变化，须用户拍板后才能落地：
  1. 用户在 SessionSkillsSheet 手动关掉某流派后，下次切回该作品会被自动重新打开（自动联动覆盖手动选择）。
  2. 作品 `genre` 是自由文本，模糊匹配可能误启用相近流派。
  **备选**：若不做自动联动，则完全依赖用户在 SessionSkillsSheet 手动开启流派技能，B4 退化为「只提供 NovelGenreSkillMapper 工具 + 作品创建页提示」，不动 setSessionOverride。**当前默认按备选实现，自动联动部分标记为待确认代码（不启用）。**
- 播种器必须先把 25 个流派技能 install 好（B1），否则 setSessionOverride 指向不存在的 id（数据库不校验外键，但 skillPromptFragment 只遍历 _skills，无效 id 不会进提示词，静默失败）。**这是 B4 对 B1 的硬依赖**。

### 3.3 改 `data/repository/SkillRepository.kt`（D4 混合强制：前 60 行入提示词）

D4 决策要求「流派 SKILL.md 正文前 60 行拼进系统提示词」。当前 `skillPromptFragment` 只发 name+desc+path，正文靠 agent 主动 read（可能漏读）。需要为流派技能开特例：

```kotlin
// skillPromptFragment 内，选中 selected 后：
for (skill in selected) {
    if (skill.id.startsWith("genre-")) {
        // D4：流派技能前 60 行强制注入
        val body = readSkillMd(skill.id)?.lines()?.take(60)?.joinToString("\n")
        if (!body.isNullOrBlank()) {
            append("  <genre_rules skill=\"${escapeXml(skill.id)}\">\n")
            append(body)
            append("\n  </genre_rules>\n")
        }
    }
}
```

**风险**：25 流派同时启用会爆上下文。但 D2 默认关 + 3.2 的「切作品只启用一个流派」已经把同时启用数压到 1，风险可控。B5 需验证 XML 转义（desc 里的中文引号无需转义，但正文 blockquote `>` 需确认 `<genre_rules>` 内的 `>` 不破坏 XML——建议改用 CDATA 或不包 XML 标签，B5 定）。

## 4. 依赖关系与顺序

```
B1 播种器（25 技能 install，默认关）
   ↓
B2 流派 SKILL.md（正文前 60 行 = 规约来源）
   ↓
B4 联动（本方案）：Mapper + 作品切换 override + D4 注入
   ↓
B5 验证：首启播种→UI 开关→prompt fragment 检查→subagent_task 冒烟
```

## 5. 本地无法编译的提醒

项目铁律：CI 是唯一编译器（本地无 Gradle/JDK 完整环境）。以上 Kotlin 代码产出后**不声称已编译通过**，须由 CI 验证。本方案只做静态一致性检查（id 与 id_map.json 对齐、override 关闭逻辑配对）。
