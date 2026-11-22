package com.ai.assistance.quro.tools.novel

import com.ai.assistance.quro.data.model.AgentToolDefinition
import com.ai.assistance.quro.data.model.AgentToolParam
import com.ai.assistance.quro.sandbox.PRootKernel
import com.ai.assistance.quro.tools.ToolExecutionResult
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文本小说库 · 记忆读写工具（`novel_memory_save` / `novel_memory_query`）。
 *
 * 为什么另开一对工具：`novel_memory_text` / `novel_memory_link` / `novel_memory_graph` 读的是
 * filesDir 里的结构化作品库（`filesDir/novel/works.json` 五张表），对「纯文本项目目录」
 * （/var/minis/mounts/ 下的 .txt 小说库）用户永远是空结果。本文件的读写对象是**项目目录里的
 * 两个固定 md**：
 *
 * | section | 目标文件 | 内容 |
 * |---|---|---|
 * | `current_volume` | `项目中枢/剧情记忆/019_当前卷.md` | 当前卷剧情记忆（本卷目标/已发生/悬念） |
 * | `character_states` | `项目中枢/剧情记忆/020_人物状态总表.md` | 人物状态总表（位置/伤势/心态/关系/持有物） |
 *
 * 两条硬约束：
 * 1. **写前必备份**——原文先复制到 `草稿/归档备份/记忆_<yyyyMMdd_HHmm>/`，备份失败则中止，
 *    绝不出现「改了但没备份」的中间态（对齐 规则.1 铁律：改前必做备份）。
 * 2. **查询有上限**——超过 [QUERY_LIMIT] 字符截断并指回 `file_read`，避免一次查询把上下文撑爆。
 *
 * 与基座 `NovelTools` 一致的契约：`definition(): AgentToolDefinition` +
 * `execute(argsJson): ToolExecutionResult`；不接收 sessionId（目标目录由 project_path
 * 指定，路径经 [PRootKernel.resolveHostPath] 落到宿主文件系统）。所有文件 IO 都包在
 * `runCatching` 里，失败返回错误结果而不是抛异常。
 */
object NovelGraphTools {
    const val NAME_SAVE = "novel_memory_save"
    const val NAME_QUERY = "novel_memory_query"

    private const val SECTION_CURRENT_VOLUME = "current_volume"
    private const val SECTION_CHARACTER_STATES = "character_states"
    private val SECTIONS = listOf(SECTION_CURRENT_VOLUME, SECTION_CHARACTER_STATES)

    /** 记忆文件所在目录（相对项目根）。 */
    private const val MEMORY_DIR = "项目中枢/剧情记忆"

    /** 写前备份根目录（相对项目根）。 */
    private const val BACKUP_ROOT = "草稿/归档备份"

    /** 单次查询返回的字符上限。 */
    private const val QUERY_LIMIT = 8000

    /** section → 目标文件名；未知 section 返回 null 由调用方报错。 */
    private fun fileNameOf(section: String): String? = when (section) {
        SECTION_CURRENT_VOLUME -> "019_当前卷.md"
        SECTION_CHARACTER_STATES -> "020_人物状态总表.md"
        else -> null
    }

    /** section 的说明（写进 definition 的 enum 描述里，让模型知道该选哪个）。 */
    private fun sectionDoc(section: String): String = when (section) {
        SECTION_CURRENT_VOLUME -> "$SECTION_CURRENT_VOLUME=当前卷剧情记忆（019_当前卷.md）"
        SECTION_CHARACTER_STATES -> "$SECTION_CHARACTER_STATES=人物状态总表（020_人物状态总表.md）"
        else -> section
    }

    fun saveDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_SAVE,
        description =
            "把记忆写进文本小说库的固定文件（覆盖写，写前自动备份原文）。" +
                "section=current_volume 写《019_当前卷.md》——本卷目标 / 已发生 / 未回收的悬念；" +
                "section=character_states 写《020_人物状态总表.md》——主要人物此刻的位置、伤势、心态、关系、持有物。" +
                "content 是整份文件的新内容（不是追加：把旧内容一起带上，或者先用 novel_memory_query 读出全文再改）。" +
                "何时用：每写完一章更新当前卷记忆与人物状态；下一章开写前若发现记忆过期，先更新再写。" +
                "读取用 novel_memory_query；章节正文用 novel_write。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "project_path" to AgentToolParam("string", "小说项目根目录（/var/minis/ 下，如 /var/minis/mounts/小说/我的书）"),
            "section" to AgentToolParam(
                "string",
                "写哪份记忆：" + SECTIONS.joinToString(" / ") { sectionDoc(it) },
                enumValues = SECTIONS,
            ),
            "content" to AgentToolParam("string", "整份文件的新内容（覆盖写；Markdown 原文，不是片段）"),
        ),
        required = listOf("project_path", "section", "content"),
        propertyOrdering = listOf("tool_title", "project_path", "section", "content"),
    )

    fun queryDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME_QUERY,
        description =
            "读取文本小说库的记忆文件。" +
                "section=current_volume 读《019_当前卷.md》（本卷剧情记忆），" +
                "section=character_states 读《020_人物状态总表.md》（人物状态总表）。" +
                "何时用：写新章前对齐前文与人物状态；用户问「这卷写到哪了」「某人现在什么状态」。" +
                "超过 $QUERY_LIMIT 字符会截断，完整内容改用 file_read。" +
                "写入用 novel_memory_save。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "project_path" to AgentToolParam("string", "小说项目根目录（/var/minis/ 下，如 /var/minis/mounts/小说/我的书）"),
            "section" to AgentToolParam(
                "string",
                "读哪份记忆：" + SECTIONS.joinToString(" / ") { sectionDoc(it) },
                enumValues = SECTIONS,
            ),
        ),
        required = listOf("project_path", "section"),
        propertyOrdering = listOf("tool_title", "project_path", "section"),
    )

    /**
     * 写入一份记忆：备份 → 覆盖写 → 回报路径与字节数。
     *
     * 失败三种都已落成错误结果（不抛异常）：参数不全 / 项目目录不存在 / 备份失败 / 写入失败。
     * 备份失败是**中止**而不是「跳过备份继续写」——备份是这次写入的前置条件。
     */
    fun executeSave(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("Error: 参数必须是合法 JSON", false, toolTitle = NAME_SAVE)
        val toolTitle = args.optString("tool_title", NAME_SAVE).ifBlank { NAME_SAVE }

        val projectPath = args.optString("project_path", "").trim()
        if (projectPath.isEmpty()) {
            return ToolExecutionResult(
                "Error: project_path 不能为空（项目根目录，如 /var/minis/mounts/小说/我的书）",
                false, toolTitle = toolTitle,
            )
        }
        val section = args.optString("section", "").trim()
        val fileName = fileNameOf(section)
            ?: return ToolExecutionResult(
                "Error: section 必须是 ${SECTIONS.joinToString(" / ")}，收到 '$section'",
                false, toolTitle = toolTitle,
            )
        if (!args.has("content")) {
            return ToolExecutionResult(
                "Error: content 不能为空（save 需要整份文件的新内容）",
                false, toolTitle = toolTitle,
            )
        }
        val content = args.optString("content", "")

        val projectDir = resolveProjectDir(projectPath)
            ?: return ToolExecutionResult("Error: 无法解析项目路径：$projectPath", false, toolTitle = toolTitle)
        if (!projectDir.isDirectory) {
            return ToolExecutionResult("Error: 项目目录不存在：$projectPath", false, toolTitle = toolTitle)
        }

        val target = File(File(projectDir, MEMORY_DIR), fileName)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

        // 写前备份：原文存在才需要备份；备份失败一律中止，不进入写入。
        var backupRelPath: String? = null
        if (target.exists()) {
            val backupDir = File(File(projectDir, BACKUP_ROOT), "记忆_$stamp")
            val copied = runCatching {
                if (!backupDir.isDirectory && !backupDir.mkdirs()) {
                    error("无法创建备份目录：${backupDir.absolutePath}")
                }
                val dst = uniqueChild(backupDir, target.name)
                target.copyTo(dst, overwrite = false)
                dst
            }
            val dst = copied.getOrElse {
                return ToolExecutionResult(
                    "Error: 写前备份失败，已中止（未改动 ${target.name}）：${it.message ?: it.javaClass.simpleName}",
                    false, toolTitle = toolTitle,
                )
            }
            backupRelPath = "$BACKUP_ROOT/记忆_$stamp/${dst.name}"
        }

        val writeError = runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
        }.exceptionOrNull()
        if (writeError != null) {
            return ToolExecutionResult(
                "Error: 写入失败：${writeError.message ?: writeError.javaClass.simpleName}",
                false, toolTitle = toolTitle,
            )
        }

        val bytes = runCatching { target.length() }.getOrDefault(content.toByteArray(Charsets.UTF_8).size.toLong())
        val sb = StringBuilder()
        sb.append("已写入 $fileName（$bytes 字节，${content.length} 字符）\n")
        sb.append("目标：$projectPath/$MEMORY_DIR/$fileName\n")
        if (backupRelPath != null) {
            sb.append("备份：$projectPath/$backupRelPath")
        } else {
            sb.append("备份：原文件不存在，首次写入，无需备份")
        }
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    /**
     * 读取一份记忆：文件不存在不算失败（记忆还没建），返回可自纠正的提示；
     * 超过 [QUERY_LIMIT] 字符截断并指回 `file_read`。
     */
    fun executeQuery(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("Error: 参数必须是合法 JSON", false, toolTitle = NAME_QUERY)
        val toolTitle = args.optString("tool_title", NAME_QUERY).ifBlank { NAME_QUERY }

        val projectPath = args.optString("project_path", "").trim()
        if (projectPath.isEmpty()) {
            return ToolExecutionResult(
                "Error: project_path 不能为空（项目根目录，如 /var/minis/mounts/小说/我的书）",
                false, toolTitle = toolTitle,
            )
        }
        val section = args.optString("section", "").trim()
        val fileName = fileNameOf(section)
            ?: return ToolExecutionResult(
                "Error: section 必须是 ${SECTIONS.joinToString(" / ")}，收到 '$section'",
                false, toolTitle = toolTitle,
            )

        val projectDir = resolveProjectDir(projectPath)
            ?: return ToolExecutionResult("Error: 无法解析项目路径：$projectPath", false, toolTitle = toolTitle)
        if (!projectDir.isDirectory) {
            return ToolExecutionResult("Error: 项目目录不存在：$projectPath", false, toolTitle = toolTitle)
        }

        val target = File(File(projectDir, MEMORY_DIR), fileName)
        if (!target.isFile) {
            return ToolExecutionResult(
                "尚未写入 $fileName（$projectPath/$MEMORY_DIR/$fileName 不存在）。" +
                    "先用 novel_memory_save 记录；若文件已手工创建，可用 file_read 确认路径。",
                true, toolTitle = toolTitle,
            )
        }

        val text = runCatching { target.readText(Charsets.UTF_8) }.getOrElse {
            return ToolExecutionResult(
                "Error: 读取失败：${it.message ?: it.javaClass.simpleName}",
                false, toolTitle = toolTitle,
            )
        }
        val truncated = text.length > QUERY_LIMIT
        val sb = StringBuilder()
        sb.append("$fileName（${text.length} 字符")
        if (truncated) sb.append("，已截断到 $QUERY_LIMIT")
        sb.append("）\n\n")
        sb.append(if (truncated) text.take(QUERY_LIMIT) else text)
        if (truncated) sb.append("\n\n（已截断，完整内容请用 file_read）")
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    /**
     * `/var/minis/...` 路径按挂载表落到宿主文件系统（与 NovelTools 同一套解析），
     * 已是宿主绝对路径则直接用。解析不出来返回 null。
     */
    private fun resolveProjectDir(projectPath: String): File? {
        val resolved = runCatching { PRootKernel.resolveHostPath(projectPath) }.getOrNull()
        if (resolved != null) return resolved
        return if (projectPath.startsWith("/")) File(projectPath) else null
    }

    /**
     * 同一分钟内连写两次时，第二次的备份不能覆盖第一次的（备份的意义就是留旧值），
     * 重名则退到 `名字_2.md`、`名字_3.md`。
     */
    private fun uniqueChild(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = File(dir, "${stem}_$i$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }
}
