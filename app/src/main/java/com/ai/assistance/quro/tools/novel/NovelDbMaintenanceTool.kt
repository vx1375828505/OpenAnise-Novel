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
 * 文本小说库 · 体检与归档工具（`novel_db_maintenance`）。
 *
 * 项目模板里 `数据库维护/` 那一套是库的「免疫系统」：`055_约束.md` 定规矩、`056_变更日志.md` 记流水、
 * `057_度量.md` 看趋势、`体检/` 出报告、`退役归档/` 收废弃文件。这套东西以前只能靠模型
 * file_read + file_edit 手工拼，字段口径和插入位置全靠临场发挥；本工具把它固化成四个 action。
 *
 * | action | 落盘路径 | 干什么 |
 * |---|---|---|
 * | `checkup` | `数据库维护/体检/<yyyyMMdd>_体检.md` | 扫全库 .md，统计孤儿/死链，出体检报告 |
 * | `metrics` | `数据库维护/057_度量.md` | 趋势表追加一行（先备份该文件） |
 * | `changelog` | `数据库维护/056_变更日志.md` | 分隔线后插一条新条目（先备份该文件） |
 * | `retire` | `数据库维护/退役归档/_废弃_<原名>` | 退役一个文件，并补一条变更日志 |
 *
 * 两条与 [NovelGraphTools] 一致的硬约束：**改前必备份**（备份失败即中止），
 * **所有 IO 用 runCatching**（失败返回错误结果，不抛异常）。
 *
 * 路径解析走 [PRootKernel.resolveHostPath]，不接收 sessionId 与 Context。
 */
object NovelDbMaintenanceTool {
    const val NAME = "novel_db_maintenance"

    private val ACTIONS = listOf("checkup", "metrics", "changelog", "retire")

    private const val MAINT_DIR = "数据库维护"
    private const val CHECKUP_DIR = "$MAINT_DIR/体检"
    private const val METRICS_PATH = "$MAINT_DIR/057_度量.md"
    private const val CHANGELOG_PATH = "$MAINT_DIR/056_变更日志.md"
    private const val RETIRE_DIR = "$MAINT_DIR/退役归档"
    private const val BACKUP_ROOT = "草稿/归档备份"

    /** 体检不计入的目录（备份区是历史留档，算进去指标就永远超标）。 */
    private const val TEXT_EXCLUDE_PREFIX = "草稿/归档备份"

    /** 报告里每类问题最多列这么多条，剩下的只给总数。 */
    private const val LIST_LIMIT = 40

    private const val DEPRECATED_PREFIX = "_废弃_"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description =
            "维护文本小说库的『免疫系统』（数据库维护/ 那一套），四个 action：" +
                "checkup=扫全库 .md 统计孤儿文件与死链，写一份 数据库维护/体检/<日期>_体检.md；" +
                "metrics=在 数据库维护/057_度量.md 趋势表追加一行（体检日/文件总数/孤儿/死链自动算，周新增与检索跳数填 —）；" +
                "changelog=在 数据库维护/056_变更日志.md 的分隔线后插一条（最新在最上面）；" +
                "retire=把指定文件改名 _废弃_<原名> 移进 数据库维护/退役归档/，并补一条变更日志。" +
                "何时用：写完一批章节后的例行体检（先 checkup 再 metrics）；动过库结构后补 changelog；" +
                "清理过期/重复文件用 retire。metrics / changelog / retire 都会先备份目标文件，备份失败即中止。" +
                "payload 是 JSON 字符串：changelog 用 {involved,fix,reason,backup,approved}；" +
                "retire 用 {file,reason,approved}（file 可写相对项目根的路径）。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."),
            "project_path" to AgentToolParam("string", "小说项目根目录（/var/minis/ 下，如 /var/minis/mounts/小说/我的书）"),
            "action" to AgentToolParam(
                "string",
                "checkup=体检并出报告 / metrics=度量趋势表追加 / changelog=补变更日志 / retire=退役归档",
                enumValues = ACTIONS,
            ),
            "payload" to AgentToolParam(
                "string",
                "JSON 字符串（changelog / retire 需要）。changelog: {\"involved\":\"涉及的文件/目录\",\"fix\":\"具体怎么改\"," +
                    "\"reason\":\"为什么改\",\"backup\":\"备份路径或 无\",\"approved\":true|false}；" +
                    "retire: {\"file\":\"相对项目根的路径\",\"reason\":\"为什么退役\",\"approved\":false}",
            ),
        ),
        required = listOf("project_path", "action"),
        propertyOrdering = listOf("tool_title", "project_path", "action", "payload"),
    )

    fun execute(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("Error: 参数必须是合法 JSON", false, toolTitle = NAME)
        val toolTitle = args.optString("tool_title", NAME).ifBlank { NAME }

        val projectPath = args.optString("project_path", "").trim()
        if (projectPath.isEmpty()) {
            return ToolExecutionResult(
                "Error: project_path 不能为空（项目根目录，如 /var/minis/mounts/小说/我的书）",
                false, toolTitle = toolTitle,
            )
        }
        val action = args.optString("action", "").trim().lowercase()
        if (action !in ACTIONS) {
            return ToolExecutionResult(
                "Error: action 必须是 ${ACTIONS.joinToString(" / ")}，收到 '$action'",
                false, toolTitle = toolTitle,
            )
        }
        val projectDir = resolveDir(projectPath)
            ?: return ToolExecutionResult("Error: 无法解析项目路径：$projectPath", false, toolTitle = toolTitle)
        if (!projectDir.isDirectory) {
            return ToolExecutionResult("Error: 项目目录不存在：$projectPath", false, toolTitle = toolTitle)
        }

        return when (action) {
            "checkup" -> checkup(projectDir, projectPath, toolTitle)
            "metrics" -> metrics(projectDir, projectPath, toolTitle)
            "changelog" -> changelog(projectDir, args, toolTitle)
            "retire" -> retire(projectDir, args, toolTitle)
            else -> ToolExecutionResult("Error: 未实现的 action '$action'", false, toolTitle = toolTitle)
        }
    }

    // ── checkup ─────────────────────────────────────────────────────────────

    /**
     * 扫全库 .md 出体检报告。
     *
     * 口径（对齐 `057_度量.md`）：
     * - 文件总数：全库 .md，排除 `草稿/归档备份`（历史留档）与 `.trashed`；
     * - 孤儿：其文件名没有出现在任何**其他**文件的正文里；
     * - 死链：正文里反引号包起来的 `xxx.md`，目标文件不存在。
     *   跳过 `{{...}}` 占位、`_废弃_` 前缀、`.trashed`，以及带空格或通配符的假路径。
     */
    private fun checkup(projectDir: File, projectPath: String, toolTitle: String): ToolExecutionResult {
        val scan = runCatching { scan(projectDir) }.getOrElse {
            return ToolExecutionResult(
                "Error: 扫描失败：${it.message ?: it.javaClass.simpleName}",
                false, toolTitle = toolTitle,
            )
        }
        val today = dayStamp()
        val outFile = File(File(projectDir, CHECKUP_DIR), "${dayStampCompact()}_体检.md")

        val writeError = runCatching {
            outFile.parentFile?.mkdirs()
            outFile.writeText(renderCheckupReport(scan, today), Charsets.UTF_8)
        }.exceptionOrNull()
        if (writeError != null) {
            return ToolExecutionResult(
                "Error: 体检报告写入失败：${writeError.message ?: writeError.javaClass.simpleName}",
                false, toolTitle = toolTitle,
            )
        }

        val sb = StringBuilder()
        sb.append("体检完成：《${projectDir.name}》\n")
        sb.append("文件总数：${scan.total}（排除 $TEXT_EXCLUDE_PREFIX 与 .trashed）\n")
        sb.append("孤儿文件：${scan.orphans.size}\n")
        sb.append("死链：${scan.deadLinks.size}\n")
        sb.append("报告：$projectPath/$CHECKUP_DIR/${outFile.name}")
        if (scan.orphans.isNotEmpty() || scan.deadLinks.isNotEmpty()) {
            sb.append("\n（问题清单已写进报告；要修结构就先 user_ask 再动手，修完用 changelog 记一笔。）")
        }
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    private fun renderCheckupReport(scan: Scan, today: String): String {
        val sb = StringBuilder()
        sb.append("# $today 体检\n\n")
        sb.append("> 由 novel_db_maintenance 自动生成（checkup）。字段口径见 `../057_度量.md`，约束见 `../055_约束.md`，")
        sb.append("修完的库结构变更记入 `../056_变更日志.md`。\n\n")

        sb.append("## 结论\n\n")
        sb.append(scan.verdict())

        sb.append("\n\n## 体检数据\n\n")
        sb.append("| 字段 | 值 | 说明 |\n|---|---|---|\n")
        sb.append("| 体检日期 | $today | 自动体检 |\n")
        sb.append("| 文件总数 | ${scan.total} | 全库 .md，排除 $TEXT_EXCLUDE_PREFIX 与 .trashed |\n")
        sb.append("| 周新增 | — | 需与上周快照对比，工具不推算 |\n")
        sb.append("| 孤儿文件 | ${scan.orphans.size} | 没有任何其他文件引用其文件名；健康值 0 |\n")
        sb.append("| 死链 | ${scan.deadLinks.size} | 反引号里引用了不存在的 .md；健康值 0 |\n")
        sb.append("| 检索跳数 | — | 需人工从入口数一次；健康区间 ≤ 3 |\n")

        sb.append("\n## 发现的问题清单\n\n")
        if (scan.orphans.isEmpty() && scan.deadLinks.isEmpty()) {
            sb.append("无。\n")
        } else {
            sb.append("（逐条写：问题位置 / 现象 / 判定依据。）\n\n")
            var n = 1
            for (rel in scan.orphans.take(LIST_LIMIT)) {
                sb.append("$n. `$rel` — 孤儿文件 — 全库正文里没有出现过该文件名\n")
                n++
            }
            for ((from, target) in scan.deadLinks.take(LIST_LIMIT)) {
                sb.append("$n. `$from` → `$target` — 死链 — 目标文件不存在\n")
                n++
            }
            val hidden = (scan.orphans.size + scan.deadLinks.size) - (n - 1)
            if (hidden > 0) sb.append("\n（另有 $hidden 条未列出，已超出每类 $LIST_LIMIT 条的上限。）\n")
        }

        sb.append("\n## 处置建议\n\n")
        sb.append("**必改**\n\n")
        if (scan.deadLinks.isEmpty()) {
            sb.append("无。\n")
        } else {
            sb.append("1. 修死链：把指向不存在文件的引用改成真实路径，或删掉引用（改前先 user_ask，改后用 changelog 记一笔）。\n")
        }
        sb.append("\n**建议**\n\n")
        if (scan.orphans.isEmpty()) {
            sb.append("无。\n")
        } else {
            sb.append("1. 收孤儿：判断每个孤儿文件是「该被引用」（补进上级子路由）还是「该退役」（用 retire），不要放着不管。\n")
        }
        sb.append("\n**可保留**\n\n")
        sb.append("- 子路由 / 索引类文件即使暂时没被引用也可保留，等目录补齐后自然会挂上。\n")
        return sb.toString()
    }

    // ── metrics ─────────────────────────────────────────────────────────────

    /** 在 `057_度量.md` 趋势表末尾追加一行；追加前备份该文件。 */
    private fun metrics(projectDir: File, projectPath: String, toolTitle: String): ToolExecutionResult {
        val target = File(projectDir, METRICS_PATH)
        if (!target.isFile) {
            return ToolExecutionResult(
                "Error: 度量文件不存在：$projectPath/$METRICS_PATH（先用模板补齐 数据库维护/ 这套文件）",
                false, toolTitle = toolTitle,
            )
        }
        val scan = runCatching { scan(projectDir) }.getOrElse {
            return ToolExecutionResult(
                "Error: 扫描失败，未追加：${it.message ?: it.javaClass.simpleName}",
                false, toolTitle = toolTitle,
            )
        }
        val backup = backupOf(projectDir, target, "度量")
            ?: return ToolExecutionResult(
                "Error: 写前备份失败，已中止（未改动 ${target.name}）",
                false, toolTitle = toolTitle,
            )

        val text = runCatching { target.readText(Charsets.UTF_8) }.getOrElse {
            return ToolExecutionResult(
                "Error: 读取度量文件失败：${it.message ?: it.javaClass.simpleName}",
                false, toolTitle = toolTitle,
            )
        }
        // 周新增要从上周快照算，检索跳数要人工数——工具不编数字，一律填 —
        val row = "| ${dayStamp()} | ${scan.total} | — | ${scan.orphans.size} | ${scan.deadLinks.size} | — | checkup 自动追加 |"
        val updated = appendToTrendTable(text, row)
            ?: return ToolExecutionResult(
                "Error: 度量文件里找不到「## 趋势表」的表格，未追加（请检查 $METRICS_PATH 结构）",
                false, toolTitle = toolTitle,
            )

        val writeError = runCatching { target.writeText(updated, Charsets.UTF_8) }.exceptionOrNull()
        if (writeError != null) {
            return ToolExecutionResult(
                "Error: 写入度量文件失败：${writeError.message ?: writeError.javaClass.simpleName}",
                false, toolTitle = toolTitle,
            )
        }

        val sb = StringBuilder()
        sb.append("已在 057_度量.md 趋势表追加一行：\n$row\n")
        sb.append("备份：$projectPath/$backup")
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    /**
     * 在「## 趋势表」标题之后最后一行表格行后面插入 [row]。
     * 模板里那行 `| {{体检日}} | ... |` 是占位，第一次追加时直接替换它，不留假行。
     * 找不到趋势表或表体返回 null（调用方按错误处理，不猜位置硬插）。
     */
    private fun appendToTrendTable(text: String, row: String): String? {
        val lines = text.split("\n").toMutableList()
        val heading = lines.indexOfFirst { it.trim() == "## 趋势表" }
        if (heading < 0) return null
        var lastTable = -1
        var i = heading + 1
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("#")) break
            if (t.startsWith("|")) lastTable = i
            i++
        }
        if (lastTable < 0) return null
        if (lines[lastTable].contains("{{")) {
            lines[lastTable] = row
        } else {
            lines.add(lastTable + 1, row)
        }
        return lines.joinToString("\n")
    }

    // ── changelog ───────────────────────────────────────────────────────────

    /** 在 `056_变更日志.md` 的 `---` 分隔线之后插一条新条目（最新在最上面）；插前备份。 */
    private fun changelog(projectDir: File, args: JSONObject, toolTitle: String): ToolExecutionResult {
        val payload = payloadOf(args)
            ?: return ToolExecutionResult(
                "Error: payload 必须是 JSON 字符串，形如 {\"involved\":\"…\",\"fix\":\"…\",\"reason\":\"…\",\"backup\":\"…\",\"approved\":false}",
                false, toolTitle = toolTitle,
            )
        val involved = payload.optString("involved", "").trim()
        val fix = payload.optString("fix", "").trim()
        if (involved.isEmpty() || fix.isEmpty()) {
            return ToolExecutionResult(
                "Error: changelog 需要 payload.involved（涉及的文件/目录）与 payload.fix（具体怎么改），两者不能为空",
                false, toolTitle = toolTitle,
            )
        }
        val entry = buildChangelogEntry(
            item = payload.optString("item", "").trim().ifBlank { summarize(fix) },
            type = payload.optString("type", "").trim().ifBlank { "结构变更" },
            involved = involved,
            fix = fix,
            reason = payload.optString("reason", "").trim().ifBlank { "未记录" },
            backup = payload.optString("backup", "").trim().ifBlank { "无" },
            approved = approvedText(payload.opt("approved")),
        )
        val res = appendChangelog(projectDir, entry)
            ?: return ToolExecutionResult(
                "Error: 变更日志不存在或找不到分隔线，未写入（期望 $CHANGELOG_PATH 里有 `---` 分隔线）",
                false, toolTitle = toolTitle,
            )
        if (res.error != null) {
            return ToolExecutionResult("Error: ${res.error}", false, toolTitle = toolTitle)
        }
        val sb = StringBuilder()
        sb.append("已在 056_变更日志.md 顶部插入一条：\n$entry\n")
        sb.append("备份：${res.backupPath ?: "无（文件不存在）"}")
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    private fun buildChangelogEntry(
        item: String,
        type: String,
        involved: String,
        fix: String,
        reason: String,
        backup: String,
        approved: String,
    ): String = buildString {
        append("## ${dayStamp()} ｜ $type ｜ $item\n")
        append("- 涉及：$involved\n")
        append("- 改法：$fix\n")
        append("- 原因：$reason\n")
        append("- 备份：$backup\n")
        append("- 是否拍板：**$approved**\n")
    }

    /** 没给 item 时用 fix 的首行摘要当标题（一条日志不超过 8 行）。 */
    private fun summarize(fix: String): String {
        val first = fix.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (first.length <= 40) first else first.take(40) + "…"
    }

    private class ChangelogResult(val backupPath: String?, val error: String?)

    /** 备份 → 在第一条 `---` 之后插 [entry]；返回备份路径或错误。 */
    private fun appendChangelog(projectDir: File, entry: String): ChangelogResult? {
        val target = File(projectDir, CHANGELOG_PATH)
        if (!target.isFile) return null
        val backup = backupOf(projectDir, target, "变更日志")
            ?: return ChangelogResult(null, "写前备份失败，已中止（未改动 ${target.name}）")
        val text = runCatching { target.readText(Charsets.UTF_8) }.getOrElse {
            return ChangelogResult(backup, "读取变更日志失败：${it.message ?: it.javaClass.simpleName}")
        }
        val lines = text.split("\n").toMutableList()
        val sep = lines.indexOfFirst { it.trim() == "---" }
        if (sep < 0) return ChangelogResult(backup, "变更日志里找不到 `---` 分隔线，未写入")
        val block = entry.trimEnd('\n').split("\n")
        val hasBlank = sep + 1 < lines.size && lines[sep + 1].isBlank()
        val insertAt = if (hasBlank) sep + 2 else sep + 1
        val insertBlock = if (hasBlank) block + "" else listOf("") + block + ""
        lines.addAll(insertAt, insertBlock)
        val writeError = runCatching { target.writeText(lines.joinToString("\n"), Charsets.UTF_8) }.exceptionOrNull()
        if (writeError != null) {
            return ChangelogResult(backup, "写入变更日志失败：${writeError.message ?: writeError.javaClass.simpleName}")
        }
        return ChangelogResult(backup, null)
    }

    // ── retire ──────────────────────────────────────────────────────────────

    /** 退役：改名 `_废弃_<原名>` 移进 `数据库维护/退役归档/`，并补一条变更日志。 */
    private fun retire(projectDir: File, args: JSONObject, toolTitle: String): ToolExecutionResult {
        val payload = payloadOf(args)
            ?: return ToolExecutionResult(
                "Error: retire 需要 payload：{\"file\":\"相对项目根的路径\",\"reason\":\"为什么退役\",\"approved\":false}",
                false, toolTitle = toolTitle,
            )
        val fileArg = payload.optString("file", "").trim()
        if (fileArg.isEmpty()) {
            return ToolExecutionResult("Error: payload.file 不能为空（相对项目根的路径）", false, toolTitle = toolTitle)
        }
        val src = resolveProjectFile(projectDir, fileArg)
            ?: return ToolExecutionResult("Error: 无法解析文件路径：$fileArg", false, toolTitle = toolTitle)
        if (!src.isFile) {
            return ToolExecutionResult("Error: 文件不存在或不是普通文件：$fileArg", false, toolTitle = toolTitle)
        }
        if (src.name.startsWith(DEPRECATED_PREFIX)) {
            return ToolExecutionResult(
                "Error: ${src.name} 已经是退役件，不能重复退役",
                false, toolTitle = toolTitle,
            )
        }
        val rootPath = runCatching { projectDir.canonicalPath }.getOrDefault(projectDir.absolutePath)
        val srcPath = runCatching { src.canonicalPath }.getOrDefault(src.absolutePath)
        if (!srcPath.startsWith(rootPath)) {
            return ToolExecutionResult(
                "Error: 只能退役项目目录内的文件（$fileArg 在项目根之外）",
                false, toolTitle = toolTitle,
            )
        }

        val retireDir = File(projectDir, RETIRE_DIR)
        val newName = DEPRECATED_PREFIX + src.name
        val dst = uniqueChild(retireDir, newName)
        val moveError = runCatching {
            if (!retireDir.isDirectory && !retireDir.mkdirs()) error("无法创建退役归档目录：${retireDir.absolutePath}")
            if (!src.renameTo(dst)) {
                // 跨卷 / 挂载点不同时 rename 会失败，退回复制后删除。
                src.copyTo(dst, overwrite = false)
                if (!src.delete()) error("已复制到 ${dst.absolutePath}，但原文件删除失败")
            }
        }.exceptionOrNull()
        if (moveError != null) {
            return ToolExecutionResult(
                "Error: 退役失败（原文件未动）：${moveError.message ?: moveError.javaClass.simpleName}",
                false, toolTitle = toolTitle,
            )
        }

        val relFrom = relativePath(projectDir, src)
        val relTo = relativePath(projectDir, dst)
        val logEntry = buildChangelogEntry(
            item = "退役 ${src.name}",
            type = "退役归档",
            involved = relFrom,
            fix = "重命名为 ${dst.name} 并移入 $RETIRE_DIR/",
            reason = payload.optString("reason", "").trim().ifBlank { "未记录" },
            backup = "原文件即归档件：$relTo",
            approved = approvedText(payload.opt("approved")),
        )
        val log = appendChangelog(projectDir, logEntry)
        val logNote = if (log == null) {
            "变更日志不存在，未记录（建议补 $CHANGELOG_PATH）"
        } else if (log.error != null) {
            "变更日志未写入：${log.error}"
        } else {
            "变更日志：已补一条（备份 ${log.backupPath ?: "无"}）"
        }

        val sb = StringBuilder()
        sb.append("已退役：$relFrom → $relTo\n")
        sb.append(logNote)
        return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
    }

    // ── 扫描 ────────────────────────────────────────────────────────────────

    private class Scan(
        val total: Int,
        val orphans: List<String>,
        val deadLinks: List<Pair<String, String>>,
    ) {
        fun verdict(): String = when {
            total == 0 -> "空库：一个 .md 都没有，先确认项目模板是否已经铺好。"
            orphans.isEmpty() && deadLinks.isEmpty() ->
                "结构完整：$total 个文件，没有孤儿也没有死链。"
            deadLinks.isNotEmpty() && orphans.isNotEmpty() ->
                "需先收口：${deadLinks.size} 条死链（引用已断）与 ${orphans.size} 个孤儿文件（没人引用的囤积）。"
            deadLinks.isNotEmpty() -> "需先收口：${deadLinks.size} 条死链，引用指向了不存在的文件。"
            else -> "结构可用但有余量：${orphans.size} 个孤儿文件，判断该补引用还是该退役。"
        }
    }

    /** 孤儿统计的豁免目录：体检报告是每次生成的、退役归档按定义就没人引用。 */
    private val ORPHAN_EXEMPT_PREFIXES = listOf("$CHECKUP_DIR/", "$RETIRE_DIR/")

    /** 文件名提及（含路径形式）：允许中文与全角括号，遇到空白/反引号/竖线/半角括号即断。 */
    private val MD_MENTION = Regex("""[^\s`|<>\[\]"'()*?]+\.md""")

    /** 反引号里的 .md 引用，死链判定只认这种显式写法。 */
    private val BACKTICK_MD = Regex("""`([^`\n]+?\.md)`""")

    private fun scan(projectDir: File): Scan {
        val files = projectDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .filterNot { isExcluded(relativePath(projectDir, it)) }
            .sortedBy { relativePath(projectDir, it) }
            .toList()
        val names = files.map { it.name }.toSet()
        val texts = files.associateWith { f -> runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("") }

        // 孤儿：该文件名没有出现在任何其他文件的正文里
        val referenced = mutableSetOf<String>()
        for ((f, text) in texts) {
            for (m in MD_MENTION.findAll(text)) {
                val token = m.value
                val base = token.substringAfterLast('/')
                if (base == f.name) continue
                if (base in names) referenced.add(base)
            }
        }
        val orphans = files.filter { it.name !in referenced }
            .map { relativePath(projectDir, it) }
            .filterNot { rel -> ORPHAN_EXEMPT_PREFIXES.any { rel.startsWith(it) } }

        // 死链：反引号引用的 .md 目标不存在
        val deadLinks = mutableListOf<Pair<String, String>>()
        val allNames = names
        for ((f, text) in texts) {
            for (m in BACKTICK_MD.findAll(text)) {
                val raw = m.groupValues[1].trim()
                if (raw.isEmpty()) continue
                if (raw.contains("{{") || raw.contains("}}")) continue
                if (raw.contains(".trashed")) continue
                val base = raw.substringAfterLast('/')
                if (base.startsWith(DEPRECATED_PREFIX)) continue
                if (raw.contains(' ') || raw.contains('*') || raw.contains('?')) continue
                val exists = if (raw.contains('/')) {
                    File(projectDir, raw).exists() || File(f.parentFile, raw).exists()
                } else {
                    base in allNames
                }
                if (!exists) deadLinks.add(relativePath(projectDir, f) to raw)
            }
        }
        return Scan(total = files.size, orphans = orphans, deadLinks = deadLinks)
    }

    // ── 公共小工具 ──────────────────────────────────────────────────────────

    private fun payloadOf(args: JSONObject): JSONObject? {
        (args.opt("payload") as? JSONObject)?.let { return it }
        val raw = args.optString("payload", "").trim()
        if (raw.isEmpty()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /** approved 一律落到「是/否」：只有明确肯定才算拍板，其余（含缺省）都记「否」。 */
    private fun approvedText(raw: Any?): String = when (raw?.toString()?.trim()?.lowercase()) {
        "true", "是", "yes", "y", "approved", "1" -> "是"
        else -> "否"
    }

    private fun dayStamp(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun dayStampCompact(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun isExcluded(rel: String): Boolean {
        if (rel.startsWith(TEXT_EXCLUDE_PREFIX)) return true
        return rel.split('/').any { it.startsWith(".trashed") }
    }

    private fun relativePath(root: File, f: File): String {
        val rootAbs = root.absolutePath
        val abs = f.absolutePath
        val rel = if (abs.startsWith("$rootAbs${File.separator}")) {
            abs.substring(rootAbs.length + 1)
        } else {
            f.name
        }
        return rel.replace(File.separatorChar, '/')
    }

    private fun resolveDir(projectPath: String): File? {
        val resolved = runCatching { PRootKernel.resolveHostPath(projectPath) }.getOrNull()
        if (resolved != null) return resolved
        return if (projectPath.startsWith("/")) File(projectPath) else null
    }

    private fun resolveProjectFile(projectDir: File, fileArg: String): File? = when {
        fileArg.startsWith("/") -> runCatching { PRootKernel.resolveHostPath(fileArg) }.getOrNull()
        else -> File(projectDir, fileArg)
    }

    /**
     * 把 [target] 复制到 `草稿/归档备份/<prefix>_<yyyyMMdd_HHmm>/<原名>`，
     * 返回相对项目根的备份路径；失败返回 null（调用方中止写入）。
     */
    private fun backupOf(projectDir: File, target: File, prefix: String): String? {
        if (!target.isFile) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val backupDir = File(File(projectDir, BACKUP_ROOT), "${prefix}_$stamp")
        val dst = runCatching {
            if (!backupDir.isDirectory && !backupDir.mkdirs()) error("无法创建备份目录")
            val d = uniqueChild(backupDir, target.name)
            target.copyTo(d, overwrite = false)
            d
        }.getOrNull() ?: return null
        return relativePath(projectDir, dst)
    }

    /** 重名退到 `名字_2.md`、`名字_3.md`，绝不覆盖已有的备份/归档。 */
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
