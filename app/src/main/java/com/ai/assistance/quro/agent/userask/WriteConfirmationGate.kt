package com.ai.assistance.quro.agent.userask

import org.json.JSONObject

/**
 * 写前确认硬门（纯 Kotlin + org.json，不碰 Android SDK，便于 JVM 单测）。
 *
 * 为什么要有「硬」门：提示词只能**建议**模型先征得同意，一条复读的历史调用、
 * 一次被改写的工具参数就能绕过去。所以真正的拦截放在调度器里——`executeTool`
 * 在 `when (name)` 之前先过这道门：本轮没有一条 `user_ask(confirm)` 批准过这次写入的
 * 目标路径，就直接返回拒绝结果，不落盘。
 *
 * 判定口径：
 * 1. 不在 [WRITE_TOOLS] 里的工具一律放行（读工具、浏览器都不受影响）；`shell_execute` 例外，
 *    见第 6 条——它不在 [WRITE_TOOLS] 里，但可能是最顺手的一把写文件的刀。
 * 2. 从参数里取目标路径：先 `path`（file_write / file_edit），再 `project_path`
 *    （novel_write / novel_polish / novel_memory_save / novel_db_maintenance），
 *    取到后先做 `..` 归一化，归一化失败直接拒绝（否则 `/a/书/../书2/x` 能绕过前缀判定）。
 * 3. 本轮批准过的条目只要有一条**就是**目标路径、**覆盖**目标路径（批准的是目录），
 *    或**落在**目标路径之下，就放行。比较按路径分段（[isSameOrUnder]），不再裸比字符串：
 *    `/…/我的书` 不能匹配 `/…/我的书2/x.md`。
 * 4. 否则返回拒绝原因——固定话术，模型据此补一次 user_ask 再重试。
 * 5. `user_ask` 被用户关掉时（[userAskEnabled] = false）写操作一律拒绝并指路设置项：
 *    拿不到批准就等于永远被拒，静默拒绝会让用户只看到「要批准」却找不到地方批。
 * 6. `shell_execute` 里出现写模式（`>`、`cp `、`mv `、`rm `、`sed -i`、`tee `、`mkdir `、
 *    `touch `、`dd `）时，按命令里出现的绝对路径逐条要求批准；找不到绝对路径则拒绝并
 *    要求模型补一条带路径的 user_ask。纯读命令（ls/cat/find/grep/head/tail/wc…）不含
 *    这些模式，照常放行。
 *
 * 取不到路径时也拒绝：这几个工具的 `project_path` 都有默认值，参数缺省不等于没有目标，
 * 放行等于给默认目录开了一个后门。
 */
object WriteConfirmationGate {

    /** shell 的注册名（AgentTools.shellExecuteDefinition）。 */
    private const val SHELL_TOOL = "shell_execute"

    /** 会落盘的工具。新增写工具时记得加进来。 */
    private val WRITE_TOOLS = setOf(
        "file_write",
        "file_edit",
        "novel_write",
        "novel_polish",
        "novel_memory_save",
        "novel_db_maintenance",
    )

    /** 这些工具的部分 action 是只读的，不该被写前确认拦住。 */
    private val READ_ONLY_ACTIONS = mapOf(
        "novel_write" to setOf("list", "read", "search", "stats", "rule"),
        "novel_polish" to setOf("prepare"),
        // 体检只扫描、不落盘业务文件（只写一份体检报告），不该要求批准。
        "novel_db_maintenance" to setOf("checkup"),
    )

    /**
     * shell 命令里出现任一模式，就按「可能写盘」处理。宁可多问一次，也不能让一行
     * `echo … > 章节.txt` 绕过整套铁律。
     */
    private val SHELL_WRITE_PATTERNS = listOf(
        ">", ">>", "cp ", "mv ", "rm ", "sed -i", "tee ", "mkdir ", "touch ", "dd ",
    )

    /** 命令里的绝对路径 token：`/` 开头，到空白或 shell 分隔符为止。 */
    private val SHELL_PATH_TOKEN = Regex("""(/[^\s"'`;|&<>()]+)""")

    /**
     * 返回 null 表示放行；返回字符串表示拒绝，字符串本身就是要回给模型的 reason。
     *
     * @param toolName 即将执行的工具名
     * @param argsJson 模型给出的原始参数 JSON
     * @param approvals 本轮已获 user_ask(confirm) 批准的条目文本（整句或抽取出的路径）
     * @param userAskEnabled user_ask 工具开关；false 时写操作 fail-closed 并指路设置
     */
    fun check(
        toolName: String,
        argsJson: String,
        approvals: Set<String>,
        userAskEnabled: Boolean = true,
    ): String? {
        if (toolName != SHELL_TOOL && toolName !in WRITE_TOOLS) return null
        // 只读动作放行：novel_write 的 read/list/search/stats/rule、novel_polish 的 prepare。
        // action 缺失或解析失败时不放行（fail-closed：宁可多问一次）。
        READ_ONLY_ACTIONS[toolName]?.let { allowed ->
            val action = actionOf(argsJson)
            if (action != null && allowed.contains(action)) return null
        }
        // user_ask 被关掉时无法获得批准，写操作一律拒绝并明确指路——
        // 否则用户会看到反复被拒却不知道原因（设置项把铁律架空了）。
        if (!userAskEnabled) {
            return "写前确认工具（user_ask）已关闭，写操作被拒绝。请到 设置 → 工具 开启 user_ask 后重试。"
        }
        if (toolName == SHELL_TOOL) return checkShellWrite(argsJson, approvals)

        val raw = targetPathOf(argsJson)
        // [严重] `..` 必须先归一化：`/a/书/../书2/x` 归一化前不会命中任何前缀判定。
        val path = raw?.let { normalizePath(it) }
        if (raw != null && path == null) {
            return "路径无法归一化（疑似非法字符），已拒绝：$raw。请改用规范绝对路径。"
        }
        val target = path ?: "无法从参数中解析路径"
        if (approvals.any { approvalCovers(it, target) }) return null
        return "需先通过 user_ask(confirm) 获得批准，未批准不落盘（目标：$target）"
    }

    /**
     * `shell_execute` 的写判定：含写模式 → 命令里的每个绝对路径都要被批准覆盖；
     * 没有绝对路径 → 视为对 `project_path` 的写，参数里也没有就拒绝。
     */
    private fun checkShellWrite(argsJson: String, approvals: Set<String>): String? {
        val command = runCatching { JSONObject(argsJson).optString("command", "") }.getOrNull().orEmpty()
        if (command.isBlank()) return null
        if (SHELL_WRITE_PATTERNS.none { command.contains(it) }) return null
        val targets = shellTargets(command, argsJson)
        if (targets.isEmpty()) {
            return "命令会写文件，但没有可核对的绝对路径目标。请先用 user_ask(confirm) 说明要写哪个目录，" +
                "并把命令里的目标改成完整绝对路径后重试。"
        }
        val approved = targets.all { t -> approvals.any { approvalCovers(it, t) } }
        if (approved) return null
        val joined = targets.joinToString("、")
        return "需先通过 user_ask(confirm) 获得批准，未批准不落盘（目标：$joined）"
    }

    /** 命令里出现的绝对路径（归一化后去重）；没有则退回参数里的 project_path。 */
    private fun shellTargets(command: String, argsJson: String): List<String> {
        val fromCommand = SHELL_PATH_TOKEN.findAll(command)
            .map { it.value.trimEnd('，', '。', '；', '、', '）', ')', ',', '.', ';', ':') }
            .filter { it.length > 1 && it != "/" }
            .mapNotNull { normalizePath(it) }
            .distinct()
            .toList()
        if (fromCommand.isNotEmpty()) return fromCommand
        val project = runCatching { JSONObject(argsJson).optString("project_path", "").trim() }.getOrNull()
        if (project.isNullOrEmpty()) return emptyList()
        return listOfNotNull(normalizePath(project))
    }

    /**
     * 一条批准是否覆盖 [path]：批准本身可能是目标路径、其上级目录，或一句含路径的话
     * （旧记录/用户原话）。后一种先按路径 token 抽出来再比。
     */
    private fun approvalCovers(approval: String, path: String): Boolean {
        if (approval.isBlank()) return false
        if (sameOrUnderEither(approval, path)) return true
        return SHELL_PATH_TOKEN.findAll(approval).any { sameOrUnderEither(it.value, path) }
    }

    private fun sameOrUnderEither(a: String, b: String): Boolean {
        val na = normalizePath(a) ?: a.trim()
        val nb = normalizePath(b) ?: b.trim()
        return isSameOrUnder(nb, na) || isSameOrUnder(na, nb)
    }

    /** [path] 是 [base] 本身或 [base] 之下（按 `/` 分段，`/a/书` 不覆盖 `/a/书2/x`）。 */
    private fun isSameOrUnder(path: String, base: String): Boolean {
        val p = path.trim().trimEnd('/')
        val b = base.trim().trimEnd('/')
        if (p.isEmpty() || b.isEmpty()) return false
        return p == b || p.startsWith("$b/")
    }

    /** `..` 归一化；非法路径返回 null（调用方按拒绝处理）。 */
    private fun normalizePath(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val normalized = runCatching { java.nio.file.Paths.get(t).normalize().toString() }.getOrNull() ?: return null
        // 归一化在 JVM 上按宿主平台分隔符输出；门里统一用 `/` 比较。
        return normalized.replace('\\', '/')
    }

    /** 取 action；解析失败或为空返回 null（fail-closed：宁可多问一次）。 */
    private fun actionOf(argsJson: String): String? = runCatching {
        JSONObject(argsJson).optString("action", "").trim().lowercase()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** `path` 优先，其次 `project_path`；参数不是合法 JSON 或两者都为空时返回 null。 */
    private fun targetPathOf(argsJson: String): String? = runCatching {
        val o = JSONObject(argsJson)
        val path = o.optString("path", "").trim()
        if (path.isNotEmpty()) path else o.optString("project_path", "").trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
