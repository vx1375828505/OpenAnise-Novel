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
 * 1. 不在 [WRITE_TOOLS] 里的工具一律放行（读工具、shell、浏览器都不受影响）。
 * 2. 从参数里取目标路径：先 `path`（file_write / file_edit），再 `project_path`
 *    （novel_write / novel_polish / novel_memory_save）。
 * 3. 本轮批准过的条目原文（user_ask 里 confirm 问题的文本）只要有一条
 *    **包含**该路径（用户把路径写进了确认句），或**是该路径的前缀**（批准的是目录），
 *    就放行。
 * 4. 否则返回拒绝原因——固定话术，模型据此补一次 user_ask 再重试。
 * 5. `user_ask` 被用户关掉时（[userAskEnabled] = false）写操作一律拒绝并指路设置项：
 *    拿不到批准就等于永远被拒，静默拒绝会让用户只看到「要批准」却找不到地方批。
 *
 * 取不到路径时也拒绝：这几个工具的 `project_path` 都有默认值，参数缺省不等于没有目标，
 * 放行等于给默认目录开了一个后门。
 */
object WriteConfirmationGate {

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
     * 返回 null 表示放行；返回字符串表示拒绝，字符串本身就是要回给模型的 reason。
     *
     * @param toolName 即将执行的工具名
     * @param argsJson 模型给出的原始参数 JSON
     * @param approvals 本轮已获 user_ask(confirm) 批准的条目文本
     * @param userAskEnabled user_ask 工具开关；false 时写操作 fail-closed 并指路设置
     */
    fun check(
        toolName: String,
        argsJson: String,
        approvals: Set<String>,
        userAskEnabled: Boolean = true,
    ): String? {
        if (toolName !in WRITE_TOOLS) return null
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
        val path = targetPathOf(argsJson) ?: "无法从参数中解析路径"
        val approved = approvals.any { it.isNotBlank() && (it.contains(path) || path.startsWith(it)) }
        if (approved) return null
        return "需先通过 user_ask(confirm) 获得批准，未批准不落盘（目标：$path）"
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
