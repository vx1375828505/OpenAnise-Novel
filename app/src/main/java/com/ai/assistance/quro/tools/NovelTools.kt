package com.ai.assistance.quro.tools

import android.content.Context
import com.ai.assistance.quro.data.model.AgentToolDefinition
import com.ai.assistance.quro.data.model.AgentToolParam
import com.ai.assistance.quro.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * OpenAnise-Novel 小说写作工具集 —— 基于 OpenMinis 原生工具契约。
 *
 * 直接读写 `/var/minis/mounts/<name>/` 挂载目录下的小说项目（纯文本 .txt 章节），
 * 不依赖旧 ZorvAI 的 `QuroTool` 契约和 Android 私有存储。
 *
 * 单一复合工具 `novel_write`（action 枚举分发），参照 browser_use 的模式。
 */
object NovelTools {
    const val NAME = "novel_write"
    const val DEFAULT_PROJECT = "/var/minis/mounts/小说/小说项目"
    /** 目录名：优先无序号（novel-project 模板），回退带序号（存量项目，如 03_正文/）。 */
    const val TEXT_DIR = "正文"
    const val RULE_DIR = "项目中枢"
    const val BACKUP_DIR = "草稿"

    private const val TEXT_DIR_LEGACY = "03_正文"
    private const val RULE_DIR_LEGACY = "01_项目中枢"
    private const val BACKUP_DIR_LEGACY = "05_草稿"

    /**
     * 解析项目下的标准目录：先找无序号（新模板），再回落带序号（旧库 / 存量项目）。
     * 两套目录并存时以无序号为准；都不存在则返回无序号路径，供错误信息展示。
     *
     * 为什么需要它：novel-project 模板建出的库是无序号的（正文/项目中枢/草稿），
     * 而 2026-11 之前建的存量项目是带序号的（03_正文/01_项目中枢/05_草稿）。
     * 只认一种会让另一类项目直接失效。
     */
    fun resolveDir(project: File, dirName: String): File {
        val primary = File(project, dirName)
        if (primary.isDirectory) return primary
        val legacyName = when (dirName) {
            TEXT_DIR -> TEXT_DIR_LEGACY
            RULE_DIR -> RULE_DIR_LEGACY
            BACKUP_DIR -> BACKUP_DIR_LEGACY
            else -> return primary
        }
        val legacy = File(project, legacyName)
        return if (legacy.isDirectory) legacy else primary
    }

    private val ACTIONS = listOf("list", "read", "write", "search", "stats", "rule", "backup")

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "小说写作工具：操作挂载目录下的纯文本章节（第NNN章：标题.txt）。" +
            "action=list 列出全部章节；read 读取章节；write 把 content_file 写入章节（覆盖前先确认）；" +
            "search 全文搜索关键词（一致性检查辅助）；stats 统计章数/字数；rule 读取写作规则文件；" +
            "backup 把章节备份到 草稿/（兼容 05_草稿/）（规则.1 铁律：改前必做）。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "Operation to perform", enumValues = ACTIONS),
            "project_path" to AgentToolParam("string", "小说项目根目录（默认 $DEFAULT_PROJECT）。必须是 /var/minis/ 下的路径。"),
            "chapter" to AgentToolParam("string", "章节号或文件名（如 第005章 或 第005章：躺平第一日.txt）。read/write/backup 必填。"),
            "keyword" to AgentToolParam("string", "全文搜索关键词。search 必填。"),
            "content_file" to AgentToolParam("string", "修改后章节内容的完整文件路径（先用 file_write 写好，再传路径过来）。write 必填。"),
            "rule_name" to AgentToolParam("string", "规则文件名（如 规则.1（精修流程）.md 或 禁则·句式.md）。rule 必填。"),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "project_path", "chapter", "keyword", "content_file", "rule_name"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val action = args.optString("action", "").trim().lowercase()
        val projectPath = args.optString("project_path", DEFAULT_PROJECT)
            .ifBlank { DEFAULT_PROJECT }
        val toolTitle = args.optString("tool_title", NAME)

        // 解析 /var/minis/... 路径到宿主文件系统
        val projectDir = PRootKernel.resolveSessionHostPath(sessionId, projectPath, context)
            ?: return ToolExecutionResult("Error: Cannot resolve project path: $projectPath", false, toolTitle = toolTitle)
        if (!projectDir.isDirectory) {
            return ToolExecutionResult("Error: Project directory not found: $projectPath", false, toolTitle = toolTitle)
        }

        return when (action) {
            "list" -> listChapters(projectDir, toolTitle)
            "read" -> readChapter(args, projectDir, toolTitle)
            "write" -> writeChapter(args, projectDir, sessionId, context, toolTitle)
            "search" -> searchKeyword(args, projectDir, toolTitle)
            "stats" -> stats(projectDir, toolTitle)
            "rule" -> readRule(args, projectDir, toolTitle)
            "backup" -> backupChapter(args, projectDir, toolTitle)
            else -> ToolExecutionResult("Error: Unknown action '$action'. Valid: ${ACTIONS.joinToString("/")}", false, toolTitle = toolTitle)
        }
    }

    private fun textDir(project: File): File = resolveDir(project, TEXT_DIR)

    private fun naturalKey(name: String): Pair<Int, String> {
        val m = Regex("(\\d+)").find(name)
        return (m?.groupValues?.get(1)?.toIntOrNull() ?: 99999) to name
    }

    private fun txtFiles(dir: File): List<File> =
        // sortedBy 要求选择器返回 Comparable<R>；naturalKey 返回 Pair（非 Comparable），
        // 因此用 compareBy 显式按（编号, 文件名）两键排序。
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.sortedWith(compareBy({ naturalKey(it.name).first }, { naturalKey(it.name).second }))
            ?: emptyList()

    private fun listChapters(project: File, toolTitle: String): ToolExecutionResult {
        val dir = textDir(project)
        if (!dir.isDirectory) return ToolExecutionResult("Error: $TEXT_DIR 目录不存在于 ${project.name}", false, toolTitle = toolTitle)
        val files = txtFiles(dir)
        if (files.isEmpty()) return ToolExecutionResult("（无章节文件）", true, toolTitle = toolTitle)
        val sb = StringBuilder()
        var total = 0L
        for (f in files) {
            total += f.length()
            val first = f.bufferedReader().use { it.readLine() } ?: ""
            sb.append("${f.name}  (${f.length()} 字节)  ${first.take(60)}\n")
        }
        sb.append("\n共 ${files.size} 章，约 $total 字节")
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    private fun resolveChapter(args: JSONObject, project: File, toolTitle: String): File? {
        val chapter = args.optString("chapter", "").trim()
        if (chapter.isBlank()) return null
        val chapterNum = Regex("(\\d+)").find(chapter)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val dir = textDir(project)
        if (!dir.isDirectory) return null
        return txtFiles(dir).firstOrNull { f ->
            Regex("(\\d+)").find(f.name)?.groupValues?.get(1)?.toIntOrNull() == chapterNum
        }
    }

    private fun readChapter(args: JSONObject, project: File, toolTitle: String): ToolExecutionResult {
        val chapter = resolveChapter(args, project, toolTitle)
            ?: return ToolExecutionResult("Error: 找不到章节 '${args.optString("chapter", "")}'", false, toolTitle = toolTitle)
        val content = chapter.readText()
        return ToolExecutionResult(
            "# ${chapter.name}\n\n$content\n\n---\n（共 ${content.length} 字符）",
            true,
            toolTitle = toolTitle,
        )
    }

    private fun writeChapter(
        args: JSONObject, project: File,
        sessionId: String, context: Context,
        toolTitle: String,
    ): ToolExecutionResult {
        val chapter = resolveChapter(args, project, toolTitle)
            ?: return ToolExecutionResult("Error: 找不到章节 '${args.optString("chapter", "")}'", false, toolTitle = toolTitle)
        val contentFileArg = args.optString("content_file", "")
        if (contentFileArg.isBlank()) return ToolExecutionResult("Error: write 需要 content_file 参数（先用 file_write 写好修改后的章节内容，再传路径）", false, toolTitle = toolTitle)
        val contentFile = PRootKernel.resolveSessionHostPath(sessionId, contentFileArg, context)
            ?: return ToolExecutionResult("Error: Cannot resolve content_file: $contentFileArg", false, toolTitle = toolTitle)
        if (!contentFile.isFile) return ToolExecutionResult("Error: content_file 不存在: $contentFileArg", false, toolTitle = toolTitle)
        val content = contentFile.readText()
        // 空内容保护：content_file 指向空文件/纯空白文件时会把正文清空（数据损坏）。
        if (content.trim().isEmpty()) {
            return ToolExecutionResult(
                "Error: content_file 内容为空（${contentFileArg}），未写入 ${chapter.name}——" +
                    "正文不允许被写成空文件。",
                false, toolTitle = toolTitle,
            )
        }
        chapter.writeText(content)
        return ToolExecutionResult("已写入 ${chapter.name}（${content.length} 字符）", true, toolTitle = toolTitle)
    }

    private fun searchKeyword(args: JSONObject, project: File, toolTitle: String): ToolExecutionResult {
        val keyword = args.optString("keyword", "").trim()
        if (keyword.isBlank()) return ToolExecutionResult("Error: search 需要 keyword 参数", false, toolTitle = toolTitle)
        val dir = textDir(project)
        if (!dir.isDirectory) return ToolExecutionResult("Error: $TEXT_DIR 目录不存在", false, toolTitle = toolTitle)
        val sb = StringBuilder()
        var found = 0
        for (f in txtFiles(dir)) {
            val lines = f.readLines()
            for ((i, line) in lines.withIndex()) {
                if (keyword in line) {
                    sb.append("${f.name}:${i + 1}: ${line.trim().take(200)}\n")
                    found++
                    if (found >= 500) break
                }
            }
            if (found >= 500) break
        }
        return if (found == 0) {
            ToolExecutionResult("（全书未找到 '$keyword'）", true, toolTitle = toolTitle)
        } else {
            ToolExecutionResult("共 $found 处命中：\n\n${sb.toString()}", true, toolTitle = toolTitle)
        }
    }

    private fun stats(project: File, toolTitle: String): ToolExecutionResult {
        val dir = textDir(project)
        if (!dir.isDirectory) return ToolExecutionResult("Error: $TEXT_DIR 目录不存在", false, toolTitle = toolTitle)
        val files = txtFiles(dir)
        var totalChars = 0
        for (f in files) totalChars += f.readText().length
        val avg = if (files.isNotEmpty()) totalChars / files.size else 0
        return ToolExecutionResult(
            "章节数：${files.size}\n总字符数：$totalChars\n平均每章：$avg 字符",
            true,
            toolTitle = toolTitle,
        )
    }

    private fun readRule(args: JSONObject, project: File, toolTitle: String): ToolExecutionResult {
        val ruleName = args.optString("rule_name", "").trim()
        if (ruleName.isBlank()) return ToolExecutionResult("Error: rule 需要 rule_name 参数", false, toolTitle = toolTitle)
        val ruleDir = resolveDir(project, RULE_DIR)
        val subDirs = listOf("写文规则", "文章精修规则", "禁则", "逻辑判断")
        val candidates = mutableListOf(File(ruleDir, ruleName))
        for (sub in subDirs) candidates.add(File(ruleDir, "$sub/$ruleName"))
        val found = candidates.firstOrNull { it.isFile }
            ?: return ToolExecutionResult("Error: 找不到规则 '$ruleName'。可在 $RULE_DIR/ 或其子目录中查找。", false, toolTitle = toolTitle)
        return ToolExecutionResult(found.readText(), true, toolTitle = toolTitle)
    }

    private fun backupChapter(args: JSONObject, project: File, toolTitle: String): ToolExecutionResult {
        val chapter = resolveChapter(args, project, toolTitle)
            ?: return ToolExecutionResult("Error: 找不到章节 '${args.optString("chapter", "")}'", false, toolTitle = toolTitle)
        val backupDir = resolveDir(project, BACKUP_DIR)
        backupDir.mkdirs()
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .format(java.time.LocalDateTime.now())
        val baseName = chapter.nameWithoutExtension
        val backupName = "${baseName}_备份_${stamp}.txt"
        val dst = File(backupDir, backupName)
        dst.writeText(chapter.readText())
        return ToolExecutionResult("已备份 ${chapter.name} -> $BACKUP_DIR/$backupName", true, toolTitle = toolTitle)
    }
}
