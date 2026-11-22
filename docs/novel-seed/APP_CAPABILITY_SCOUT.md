# OpenAnise-Novel App 能力落地可行性报告

> 调研角色：app-scout（只读调研，未修改任何 App 代码、未提交远端）
> 代码来源：`vx1375828505/OpenAnise-Novel` main 分支（HEAD `f3e4472`，2026-11-22）。本地镜像 `C:\temp\git-transfer\OpenAnise-Novel.git` 为中断的空壳（无 refs，fetch 报 `invalid index-pack output`），故全部证据取自 GitHub raw API 只读拉取。
> 说明：文中 `文件:行` 均指上述 main 分支快照。

---

## ① 结论（一句话）

**能做，而且大部分能力现成就有**：App 已有完整的 Linux 沙箱文件系统（`/var/minis/mounts/小说/` 正是用户当前路径）、通用的 `file_read/file_write/file_edit/shell_execute` 工具、`novel_write/novel_review/novel_polish/novel_memory_*` 小说专用工具、可承载规则文件的 skill 机制、以及强制用户确认的 `user_ask` 工具；**唯一真缺口是「一键开新书即生成整套目录骨架 + 初始规则文件」的初始化器，以及若干唯一性维护工具的缺失**——MVP 甚至可以零 Kotlin 改动、纯靠 skill + 提示词落地。

---

## ② 现有能力清单（带文件路径 + 证据）

### 2.1 文件系统读写与沙箱 / 挂载目录机制 —— 有，且直接命中用户路径

| 能力 | 证据（文件:行） | 说明 |
|---|---|---|
| 通用读文件工具 `file_read` | `app/src/main/java/com/ai/assistance/quro/tools/FileReadTool.kt:10-26`（定义：参数 `path` 示例即 `/var/minis/workspace/data.csv`）；`execute` 在 `:28-88`，路径经 `PRootKernel.resolveSessionHostPath` 解析于 `:54` | 读时 80KB 硬上限分页（`:45-46`），流式读取防 OOM（`:127-130`） |
| 通用写文件工具 `file_write` | `tools/FileWriteTool.kt:11-32`（定义含 `create_dirs` 参数）；`execute` 在 `:34-145`，`parent.mkdirs()` 自动建父目录于 `:104-108` | **`:125-140` 专门对 `/var/minis/mounts/` 下的写入做落盘校验日志**——证明该路径是官方支持的用户库路径 |
| 精确编辑工具 `file_edit` | `tools/FileEditTool.kt:11-26, :28-94, :100-157` | 精确串替换；>8MB 拒编辑（`:112-119`） |
| 统一 I/O 基建 | `tools/FileToolIO.kt:32-112` | 独立线程池 + 45s 超时（`:34`）；挂载卡死不再挂住 agent loop（`:20-24`） |
| 挂载前缀常量 | `sandbox/PRootKernel.kt:305` `MOUNTS_LINUX_PREFIX = "/var/minis/mounts/"` | 用户说的 `/var/minis/mounts/小说/` 就是此机制下 name=`小说` 的外部挂载 |
| 挂载快照协调 | `sandbox/PRootKernel.kt:322-360` `applyMountedFoldersSnapshot()`；`:391-420` `materializeMountTargets()` | 把用户 SAF 选的目录经 PRoot `-b host:linux` 绑定到 `/var/minis/mounts/<name>`；并物化占位目录保证绑定生效 |
| 只读挂载写保护 | `sandbox/PRootKernel.kt:423-440` `isLinuxPathUnderReadOnlyMount()`；`FileWriteTool.kt:66-77`、`FileEditTool.kt:59-66` 前置拦截 | 用户在「设置→挂载外部文件夹」可切换可写性（提示语见 `FileWriteTool.kt:73-75`） |
| 路径解析（按会话） | `sandbox/PRootKernel.kt:786-807` `resolveSessionHostPath()`；`:810-842` `resolveHostPath()` | `/var/minis/{attachments,offloads,workspace,browser}/...` 落到 `filesDir/minis-sessions/<sessionId>/`；共享目录落到 `filesDir/minis-global/`；其余走 bindMounts |
| 每个会话挂载布局 | `sandbox/SessionMounts.kt:33` `SESSION_SUBDIRS=[attachments,offloads,workspace,browser]`；`:36` `GLOBAL_SUBDIRS=[memory,skills,shared,mcp-servers]`；`:130-134` `externalMountsSnapshot()` 产出 `/var/minis/mounts/<name>`；`:104-117` 转 PRoot `-b` 参数 | 全局/会话隔离设计清晰 |
| SAF 选取 + 持久化 | `data/MountedFoldersStore.kt:45-72`（`Entry{name,treeUri,isWritable,userAllowWrite,resolvedHostPath}`，落 `filesDir/minis-config/mounted-folders.json`）；`:104-121` `add()`；`:78-85` `onChange` 通知 PRoot 刷新 | 注释 `:25-43` 说明用 tree URI + `takePersistableUriPermission`，重启后仍有效 |
| FileProvider 暴露 | `app/src/main/res/xml/file_provider_paths.xml:41-50` `<root-path name="device-storage" path="storage/" />` | 挂载目录内文件可分享/预览 |
| Shell 执行（可 mkdir / ls / python） | `tools/AgentTools.kt:130-146` `shellExecuteDefinition()`（Alpine via PRoot，`/bin/sh -c`，默认 15 分钟）；`ui/chat/ChatViewModel.kt:15392` `executeShellCommand()` | 没有独立 `list_dir` 工具，但 `ls` 走 shell_execute；用户的 `project_read.py` / `make_diff.py` 可写文件后用 `python3` 运行 |

**核实结论**：用户所述 `/var/minis/mounts/小说/` 在 App 中就是「SAF 外部挂载目录」的 Linux 视图，代码里有多处为该路径专门写的逻辑（`FileWriteTool.kt:125`、`NovelTools.kt:20`）。这不是臆想路径，是一等公民。

### 2.2 AI 工具系统：通用文件工具 + 注册机制 —— 有

**注册链路（如何给到模型）**：

1. `tools/AgentTools.kt:18-82` `makeAgentTools()` 是**中央注册表**，依次 `add`：
   - `:57` `shellExecuteDefinition()`
   - `:58` `FileReadTool.definition()`
   - `:59` `FileWriteTool.definition()`
   - `:60` `FileEditTool.definition()`
   - `:62` `NovelTools.definition()`（注释明写「操作 `/var/minis/mounts/` 下的 .txt 章节」）
   - `:64-65` `NovelReviewTools` / `NovelPolishTools`
   - `:67-69` `NovelMemoryTextTool` / `NovelMemoryLinkTool` / `NovelMemoryGraphTool`
   - `:73-81` `browser_use` / `memory_write` / `memory_get` / `subagent_task` / `user_ask`
2. `ui/chat/ChatViewModel.kt:2505-2524` `agentTools` getter 每次读取都重建（注释 `:2499-2504`：保证 `/memory` 等开关即时生效）。
3. `ChatViewModel.kt:11447` 与 `:11925`：请求里 `tools = if (helperWrapUpInjected) emptyList() else agentTools`，把 schema 发给模型。
4. `ChatViewModel.kt:14131-14200` `executeTool` 分发：`FileReadTool.NAME`→`:14136`、`FileWriteTool.NAME`→`:14151`、`FileEditTool.NAME`→`:14154`、`NovelTools.NAME`→`:14161`、`shell_execute`→`:14172`、`user_ask` 等。

**结论**：AI 不仅能读写文件，还能建目录（`file_write` 的 `create_dirs` 或 shell `mkdir -p`）、列目录（shell `ls`）、写脚本并运行（shell_execute + python3）。注册机制是「静态 Kotlin object 定义 → 每轮重建列表 → 随请求下发 → 按 NAME 分发」，`AgentToolDefinition` 定义在 `data/model/AgentToolDefinition.kt`。

### 2.3 小说专用工具（与用户库结构逐字对齐）—— 有，且高度契合

`tools/NovelTools.kt` 是**按用户的手工库结构写的**：

- `:20` `DEFAULT_PROJECT = "/var/minis/mounts/小说/小说项目"` —— 与用户真实路径几乎一致。
- `:21-23` `TEXT_DIR="03_正文"`、`RULE_DIR="01_项目中枢"`、`BACKUP_DIR="05_草稿"` —— 直接对应 `03_正文`、`01_项目中枢`、草稿备份目录。
- `:25` 七个 action：`list / read / write / search / stats / rule / backup`。
- `:29-32` 工具描述里写「`backup` 把章节备份到 `05_草稿/`（**规则.1 铁律：改前必做**）」—— App 代码里已有「铁律」概念。
- `:180-190` `readRule()` 在 `01_项目中中枢` 及 `写文规则/文章精修规则/禁则/逻辑判断` 四个子目录找规则文件。
- `:192-204` `backupChapter()` 带时间戳备份。
- `:124-139` `writeChapter()` 接受 `content_file`（先用 `file_write` 写好再传路径），写前**描述**要求确认，但**代码无强制门**（见缺口）。

审读 / 精修：

- `tools/NovelReviewTools.kt:37,45-50`：单一 `novel_review`（`humanize` 去 AI 味体检、`title` 标题候选、`shuangdian` 爽点节奏、`water` 注水检测），**只报不改**。`:193` 明说改写用 `novel_polish`（prepare→apply）。
- `tools/NovelPolishTools.kt:36-38,51-56`：`novel_polish` 的 `prepare` 产出【润色任务单】（含铁律 / 输出契约 / 原文），`apply` **先把原稿备份到 `05_草稿/`、备份失败即中止**（`:24-27, :186` 起）。`:135-164` 支持显式注入精修规则文件，未指定时只**列出**可用规则「不替模型猜默认规则」——与用户「一轮只按 061 路由选一份规则」的工作方式一致。
- 辅助：`tools/NovelEnhanceSupport.kt:23-119` 复用同一套章节/规则定位口径，注释明写「不改动新基座已交付文件（铁律 5）」。

记忆双轨：

- `core/tools/novel/NovelMemoryGraphTools.kt:25-42` 三个已注册工具：`novel_memory_text`（十节记忆快照，5 分钟 TTL）、`novel_memory_link`（建边）、`novel_memory_graph`（节点/边/五表计数）。数据落 `filesDir/novel/`（`:39`），由 `core/novel/graph/GraphStore.kt`、`graph/memory/NovelMemoryStore.kt` 等支撑（文件树已确认存在）。

用户确认（铁律的关键抓手）：

- `agent/userask/UserAskCore.kt:719-745` `UserAskPromptText.TOOL_DESCRIPTION`：`user_ask` 支持 `options`（决策）与 `confirm`（**执行某一具体动作前的批准**，用于不可逆/破坏性/影响他人/超出用户要求的操作）；「**The app only relays what the user did; it never decides for them**」「**Only `approved` authorizes a confirm; otherwise don't perform the action**」「On timeout nothing was applied」。
- 同文件 `:684-713` `UserAskExposure` 决定每轮是否暴露该工具（设置关闭 / 子代理才移除；后台运行走 deferred 模式，卡片留到用户下次消息）。
- 注册见 `AgentTools.kt:81` + `ChatViewModel.kt:2505-2524`（`userAskExposedThisRun`）。

### 2.4 skill 机制 —— 可作为规则文件载体

- 落盘路径：`data/repository/SkillRepository.kt:203-204` `skillsDir = File(context.filesDir, "minis-global/skills")`；Linux 视图 `:396`、`:532` 为 `/var/minis/skills/<id>/SKILL.md`（与 `SessionMounts.kt:36` 的 `GLOBAL_SUBDIRS` 含 `skills` 对齐）。
- 写入 API（现成）：
  - `:940-950` `writeSkillFile(skillId, relativePath, content): Boolean`——公开，自动建父目录，写 `SKILL.md` 时触发 `rescanFromDisk` 回写 DB。
  - `:932-938` `readSkillFile()`；`:851-864` `listSkillFiles()`（递归列举 siblings）；`:866-867` `skillFileHostPath()`。
  - `:1572-1584` `writeSkillMd(skill)` 生成 YAML frontmatter（name/description/version）+ body。
  - 导入：`add(...)`→`writeSkillMd`（`:255-262`）；`importFromMarkdown`（`:489` 起）；zip 导入 `:538-570`；URL 导入 `:620-745`；`exportSkillToZip` `:772`。
- 注入模型：`SkillRepository.kt:349-414` `skillPromptFragment(sessionId)` 产出 `<available_skills>`，每条带 `<path>/var/minis/skills/<id>/SKILL.md</path>`，并明确提示「**Read the SKILL.md file to load full instructions before using a skill**」；`ChatViewModel.kt:16490` 在 system prompt 中注入（skills → 全局记忆 → 近日记忆，顺序见 `:16478`）。
- AI 写 skill 后能刷新：`ChatViewModel.kt:14136-14159` 读/写/编辑 SKILL.md 会 `recordSkillUse` / `maybeReloadSkillsForPath`；`:14177` shell_execute 后 `skillRepository?.requestReload("shell_execute")`；`SkillRepository.kt:1289-1306` `reloadFromDisk/requestReload` 做目录签名比对。
- **现状边界**：仓库内**没有** `assets/skills-novel`，也没有任何 `SKILL.md` 文件；唯一内置 skill 是 `SkillRepository.kt:1238-1270` `installBundledSkills()` 硬编码的 `skill-creator`（正文常量在 `:1686` 起）。团队先前产出的 `SeedNovelSkills.kt` / `NovelSkillGuard.kt` / 25 个流派 SKILL.md 属于**另一个项目 `C:\1111\openanise-skills`**，不在 App 仓库内——需作为「外部 skill 导入」落地（用 `writeSkillFile` 或 zip/URL 导入），不是 App 自带。

### 2.5 项目初始化 / 模板 / 脚手架概念 —— 部分有

- `core/novel/NovelRepository.kt:75-96` `createWork(title, genre, defaultVolumeTitle)`：新建作品并自动带一个默认卷；元数据落 `filesDir/novel/works.json`，正文落 `chapters/{workId}/{chapterId}.md`（目录布局注释 `:13-29`）。删除作品/卷/章的级联逻辑在 `:98-191`；原子写 `:381-390`。
- 该文件 `:27-28` 自述 Phase 1 **不做**「8 类资料 / 伏笔 / 关系图 / 记忆」——与用户那套目录库是**两套并行的数据模型**（结构化 JSON vs 纯文件目录）。
- `SkillRepository.kt:1238` `installBundledSkills()` 是 skill 域的「播种」概念。
- `SessionMounts.kt:64-97` `build()` 是会话文件系统目录的初始化（缺目录则建，注释 `:23-27` 说明不能建要显式报错）。
- **没有**：一键生成 `00_~08_/tools/` 整套骨架的脚手架；`ui/novel/` 下只有关系图可视化（4 个文件），没有作品管理/新建书界面；`AgentTools` 里也没有暴露 `createWork` 的工具。

---

## ③ 缺口清单

1. **缺「项目初始化器」**：没有把 `00_项目导引 / 01_项目中枢 / 02_核心资料库 / 03_正文 / 08_数据库维护 / tools` 骨架 + 初始规则文件（061 路由表、062—083 规则、总路由、进度、全局记忆/交互铁律）一次性落盘的代码。`NovelRepository.createWork` 建的是另一套 `works.json` 数据模型，不能直接复用。
2. **缺 `list_dir` 专用工具**：列目录只能靠 shell_execute 的 `ls`（能用，但不是一等工具，也没有结构化返回）。
3. **缺 `novel_memory_save` / `novel_memory_query` / `novel_memory`**：`NovelMemoryGraphTools.kt:33` 注释说它们在 `NovelGraphTools.kt`，但**该文件在 main 分支树中不存在**（已用 GitHub code search 再次确认 total_count=0）。因此「AI 更新剧情记忆/人物状态」目前只有文本快照与建边，缺**写入/检索**入口（底层 `GraphStore`/`NovelMemoryStore` 数据层在，可补工具）。
4. **写前确认无代码硬门**：`NovelTools.kt:124-139` `writeChapter()` 描述写「覆盖前先确认」，但实现直接 `chapter.writeText(content)`。若模型不自觉，`user_ask` 不会被触发。需在工具或分发层加强制门。
5. **缺对照页 / diff 工具**：仓库只有 `speech/correction/VoiceCorrectionDiff.kt`（语音纠错用），没有 `make_diff.py` 对应的小说对照页生成工具。可由通用 file_write + shell(python3) 覆盖，但不是专门能力。
6. **两套 novel 数据模型未打通**：`filesDir/novel/`（结构化）与 `/var/minis/mounts/小说/`（纯文本库）并存，需明确哪套是唯一数据源（Single Source of Truth），否则「更新记忆」写错地方。
7. **skill 不是 App 自带的**：团队产出的 novel skills 需走导入流程进入 `minis-global/skills/`，且 `ask` 规则要写进 SKILL.md/参考文件而非硬编码 Kotlin。
8. **外部挂载可写性依赖用户设置**：挂载被标只读时 `file_write` 直接拒（`FileWriteTool.kt:71-77`）；shell 写入又绕过该守卫（`PRootKernel.kt:428-431` 注释承认 `-b` 无只读修饰），需要 UX 与文档兜底。

---

## ④ MVP 落地方案（具体到文件）

### 4.1 零 Kotlin 改动版（最快，推荐先验证）

利用现成能力，把「开新书」做成**一个 skill 驱动的 SOP**，不改 App 代码：

| 步骤 | 落点（现有文件/API） |
|---|---|
| 1. 把团队的 25 流派 skill + `novel-writing` 父技能（061 路由表 + 062—083 规则 + references）导入 App | `SkillRepository.kt:940-950` `writeSkillFile()` 或 `:489` `importFromMarkdown()`；落 `filesDir/minis-global/skills/<id>/`，Linux 视图 `/var/minis/skills/<id>/SKILL.md` |
| 2. 在父技能 SKILL.md 里写「一键开新书 SOP」：列出要建的目录与初始文件模板；规定动作全部先 `user_ask(type=confirm)` 再执行 | skill 内容（参考文件放 `references/`，脚本放 `scripts/project_read.py`、`scripts/make_diff.py`） |
| 3. AI 执行骨架落盘 | `FileWriteTool.kt`（`create_dirs=true`）或 `AgentTools.kt:130-146` `shell_execute` 跑 `mkdir -p`；路径 `/var/minis/mounts/小说/<新书名>/` |
| 4. 写正文 / 体检 / 变更日志 / 归档 | `NovelTools`（list/write/rule/backup）、`NovelReviewTools`（humanize/water）、`NovelPolishTools`（prepare→apply）、`shell_execute` + `python3` |
| 5. 每一步改动前请求确认 | `UserAskCore.kt:719-745` 的 `user_ask` confirm；`AgentTools.kt:81` 已注册 |

**MVP 交付物**：一组 SKILL.md + references + scripts（内容资产），通过现有导入 API 安装；App 代码零改动。
**局限**：没有真正的「按钮」；骨架生成质量依赖模型遵循 SKILL.md；`user_ask` 是提示词层约束，非代码硬门。

### 4.2 带按钮的最小代码版（MVP+）

如需「一键开新书」实体入口，新增/修改 **4 个文件**：

1. **新增** `app/src/main/java/com/ai/assistance/quro/tools/NovelProjectInitTool.kt`
   - `object NovelProjectInitTool`，`NAME="novel_project_init"`，单一复合工具 `action=init|validate`。
   - 复用 `NovelEnhanceSupport.kt:23-119` 的路径/章节/规则定位口径，避免改已交付文件（遵循其中「铁律 5」）。
   - 用 `PRootKernel.resolveSessionHostPath(sessionId, projectPath, context)` 解析；建 `00_~08_, tools` 骨架；把内嵌模板（总路由/进度/全局记忆/061/062—083）写入。
   - **写前调用 `user_ask` confirm；未 approved 直接返回 `aborted`**（见第 ⑥ 节）。
2. **修改** `app/src/main/java/com/ai/assistance/quro/tools/AgentTools.kt:56-82`：在 `:60` 后 `add(NovelProjectInitTool.definition())`。
3. **修改** `app/src/main/java/com/ai/assistance/quro/ui/chat/ChatViewModel.kt`（分发 `executeTool`，约 `:14161` 附近）：加 `NovelProjectInitTool.NAME -> NovelProjectInitTool.execute(argsJson, fsSessionId, context)`。
4. **修改** `app/src/main/java/com/ai/assistance/quro/ui/chat/ChatViewModelSlashExt.kt`（或新增一个设置/小说页按钮）：加 `/新书` 斜杠命令或入口，预填 `novel_project_init` 参数并触发一轮带确认的 agent 运行。

> 模板内容建议**不要硬编码在 Kotlin**，而放 skill 的 `assets/templates/`，初始化器只负责「按清单复制 SKILL.md/references 到项目库对应目录」——这样改规则不用改 App 代码、不用发版。

---

## ⑤ 完整方案

在 MVP+ 基础上补齐「AI 全权读写维护」闭环，约 10 个文件：

| 层 | 文件（新增/修改） | 职责 |
|---|---|---|
| 模板/领域 | 新增 `core/novel/project/NovelProjectTemplate.kt` | 定义 9 目录骨架 + 初始文件清单 + 061 路由表/062—083 规则的默认内容（从 skill assets 读取，可版本化） |
| 工具 | 修改 `tools/NovelTools.kt`（`:25` ACTIONS 增 `plan/route/log/archive`） | 开工只读「总路由+进度」；`plan` 出方案；`log` 写变更日志；`archive` 退役归档（均走确认） |
| 工具 | 新增 `tools/NovelDbMaintenanceTool.kt` | `action=checkup`（体检）/ `metrics`（度量）/ `changelog`（变更日志）/ `retire`（退役归档），操作 `08_数据库维护/` |
| 工具 | 新增 `tools/NovelDiffTool.kt`（或扩展 `NovelPolishTools.kt:186` 的 apply） | 生成「稿页 + 对照页」，复用 `NovelEnhanceSupport` 定位；对照页可调用 skill 自带 `make_diff.py` |
| 记忆 | 新增 `core/tools/novel/NovelGraphTools.kt` | 补 `novel_memory_save` / `novel_memory_query` / `novel_memory`（注释 `NovelMemoryGraphTools.kt:33` 指向但缺失），基于 `GraphStore.fromContext(context).memoryStore` |
| 注册 | 修改 `tools/AgentTools.kt:56-82` | 注册上面全部新工具；`user_ask` 保持 `:81` |
| 分发 | 修改 `ui/chat/ChatViewModel.kt:14131-14200` | 新工具的 `when(name)` 分支；写类工具统一接 `UserAsk` 确认门 |
| 数据模型 | 决策：明确 `/var/minis/mounts/小说/` 纯文本库为唯一数据源；`NovelRepository.kt:75` 的 `createWork` 降级为「可选结构化索引」，或给 `NovelProjectInitTool` 同时写两边并定同步规则 | 消除双模型冲突 |
| skill | 导入团队 `SeedNovelSkills.kt`/25 流派 skill/父技能到 `minis-global/skills/`（用 `SkillRepository.writeSkillFile`）；规则更新走 skill 导入而非改 Kotlin | 规则可热更新 |
| 测试 | 新增 `app/src/test/java/com/openminis/app/tools/NovelProjectInitToolTest.kt` 等 | 覆盖骨架生成、确认门、备份、只读挂载、8MB/80KB 边界 |

完整形态下 AI 的闭环应为：
**开工读总路由+进度 → 按 061 路由选一份规则 → 出方案 → `user_ask` 确认 → 执行（写正文/精修/更新记忆/体检/日志/归档）→ 交付稿页+对照页**。每一步都是现有工具或上述小补丁，无颠覆性改造。

---

## ⑥ 风险和红线

### 6.1 用户铁律「所有决定要用户确认、不擅自做主」如何体现

这是**必须做成代码硬门**的，不能只写进提示词：

1. **用现成的 `user_ask` confirm 类型**：`UserAskCore.kt:719-745` 已定义 `confirm`（行动前批准，影响不可逆/破坏性操作时用），且语义保证「只有 `approved` 才授权；超时=什么都没发生；`denied` 后不得重试或改写重问」。把写类工具（`novel_project_init`、`novel_write write`、`novel_polish apply`、体检修复、变更日志、退役归档）的入口统一接这个门。
2. **在分发层兜底**：`ChatViewModel.kt:14131-14200` 的 `executeTool` 是唯一收口，新增 `WriteGuard` 逻辑——对标记为「需确认」的工具名，检查本轮是否已有对应 `approved` 结果；没有就直接返回 `aborted`，不执行落盘。这样模型即使被提示词带偏也无法绕过（`user_ask` 结果也在该分发循环里回传，见 `UserAskToolRunner`）。
3. **修掉现有软门**：`NovelTools.kt:124-139` 当前 `writeChapter` 描述说「覆盖前先确认」但代码直写——必须补确认，或在 `NovelTools` 侧要求 `content_file` 之外再传一个「已批准」凭证。
4. **保留已有好习惯**：`NovelPolishTools.kt:186` 起 apply 前先备份、备份失败即中止——这是「改前必做」的正确实现，初始化器/归档也应照做。
5. **不要把「决定」外包给模型自行拍板**：`NovelPolishTools.kt:135-164` 「未指定规则时只列出、不替模型猜默认」是好范例；初始化器遇到「书名叫什么/流派选哪个/归档哪卷」应逐项 `user_ask(options 或 confirm)`，把卡片结果作为输入，而不是让模型自选。

### 6.2 其他风险

- **可写性/挂载**：`/var/minis/mounts/小说/` 若是 SAF 只读挂载，`file_write/edit` 直接拒（`FileWriteTool.kt:71-77`）；shell 写入绕过该守卫（`PRootKernel.kt:428-431`）。需在初始化前显式检查 `effectiveWritable`，只读时引导用户去「设置→挂载外部文件夹」解锁，而不是让 AI 用 shell 偷写。
- **文件大小边界**：`file_edit` 上限 8MB（`FileToolIO.kt:41`、`FileEditTool.kt:112-119`），`file_read` 单次硬顶 80KB 需分页（`FileReadTool.kt:45-46, :157-190`）。大体量章节/规则库要靠分块与 shell 脚本，不能一次灌。
- **双数据源**：`filesDir/novel/works.json` 与 `/var/minis/mounts/小说/` 必须择一；否则「更新记忆」可能写进 JSON 而用户看不到，或反过来。
- **skill 热更新一致性**：写入新 SKILL.md 后，`skillPromptFragment` 读的是内存列表（`ChatViewModel.kt:16478-16490`），**必须触发 `requestReload`**（`:14177` 或 `SkillRepository.kt:1289-1306`）才能在本轮/下轮可见；否则 AI「看不到」刚装的规则。
- **只读调研边界**：本报告未改 App 代码、未提交远端；所有源码经 GitHub raw API 匿名/token 只读拉取，token 仅用于 API 读取。
- **法律/合规**：仓库 HEAD 为 GPL-3.0（commit `f3e4472` 合并换证，commit `d6ee5f4` 说明），任何新增代码/衍生交付需注意 GPL 归属与 NOTICE。

---

## 附：关键证据索引（文件路径一览）

- `app/src/main/java/com/ai/assistance/quro/tools/FileReadTool.kt`
- `app/src/main/java/com/ai/assistance/quro/tools/FileWriteTool.kt`
- `app/src/main/java/com/ai/assistance/quro/tools/FileEditTool.kt`
- `app/src/main/java/com/ai/assistance/quro/tools/FileToolIO.kt`
- `app/src/main/java/com/ai/assistance/quro/tools/AgentTools.kt`
- `app/src/main/java/com/ai/assistance/quro/tools/NovelTools.kt`
- `app/src/main/java/com/ai/assistance/quro/tools/NovelReviewTools.kt`
- `app/src/main/java/com/ai/assistance/quro/tools/NovelPolishTools.kt`
- `app/src/main/java/com/ai/assistance/quro/tools/NovelEnhanceSupport.kt`
- `app/src/main/java/com/ai/assistance/quro/core/tools/novel/NovelMemoryGraphTools.kt`
- `app/src/main/java/com/ai/assistance/quro/sandbox/PRootKernel.kt`
- `app/src/main/java/com/ai/assistance/quro/sandbox/SessionMounts.kt`
- `app/src/main/java/com/ai/assistance/quro/data/MountedFoldersStore.kt`
- `app/src/main/java/com/ai/assistance/quro/data/repository/SkillRepository.kt`
- `app/src/main/java/com/ai/assistance/quro/agent/userask/UserAskCore.kt`
- `app/src/main/java/com/ai/assistance/quro/ui/chat/ChatViewModel.kt`
- `app/src/main/java/com/ai/assistance/quro/core/novel/NovelRepository.kt`
- `app/src/main/res/xml/file_provider_paths.xml`

---

## 八、工作区机制调研

> 补充调研（仍为只读，未改任何文件）。代码来源同上（`vx1375828505/OpenAnise-Novel` main，HEAD `f3e4472`）。

### 8.0 一句话结论

**能，但 App 里没有一块叫「工作区」的独立板块**：它只有「会话分组（Groups）」「聊天文件浏览器」「设置→共享文件夹 / 挂载外部文件夹」这几个面；用户想要「选个预设 → AI 把目录建出来」是**可以在这些现有面上加一个"预设"入口实现的**，而且预设本身完全可以写成 skill（SKILL.md + references + scripts），与用户说的"写成技能也可以"一致。**关键前提**：小说项目库绝不能放在 `/var/minis/workspace/`（会话删了就没了），必须放 `/var/minis/shared/` 或 `/var/minis/mounts/<name>/`。

### 8.1 问题1：App 里的"工作区"到底是什么？有没有独立 UI 板块？

**没有独立 UI 板块，也没有任何 `workspace` 命名的 Screen/ViewModel/Repository。** 证据：

- 全仓文件树按 `workspace` 检索，`app/src/main/**` 下 **0 个**文件名含 workspace（`ui/` 下只有 `sandbox/FileBrowserScreen.kt`、`sessions/SessionListScreen.kt` 等）。
- 用户可见文案里，"工作区"只出现 1 次，是对英文 `workspace` 的翻译：`app/src/main/res/values-zh/strings.xml:1358` `<string name="chat_clear_dialog_body">删除此会话的所有消息？工作区文件、附件和卸载内容会保留。</string>`（英文 `values/strings.xml:1498` 同一句）。
- 因此 `/var/minis/workspace/` 只是一个**会话级目录**，不是一个可点选的"工作区"。

用户直觉中的"板块"，实际对应以下四个现有面（按接近程度排序）：

| 现有面 | 入口/证据 | 它其实是什么 |
|---|---|---|
| **会话分组（Groups，代码叫 Folder）** | `ui/sessions/SessionListScreen.kt:864` 有 `"分组"` 段头；`:1667` `GroupPickerSheet(...)`；`data/db/FolderEntity.kt:36-72`；`data/db/ChatSessionEntity.kt:70` `folder_id` | **只是把会话归档到分组**，不创建任何目录、不绑定任何文件路径 |
| **聊天文件浏览器** | `ui/navigation/AppNavigation.kt:166` 注释「Chat-files browser: opens FileBrowser rooted at /var/minis for the session」；`ui/chat/ChatScreen.kt:3487-3492` 菜单项 `chat_menu_browse_chat_files`；`Routes.CHAT_FILES` | 浏览**本会话**的 `/var/minis`（workspace/attachments 等） |
| **设置→共享文件夹** | `ui/settings/SharedFoldersScreen.kt:36-44` 头注释：三个固定入口 `Shared (R/W)`、`Skills (read-only)`、`Memory (read-only)`，暴露 `/var/minis/{shared,skills,memory}` | 全局、跨会话、可浏览；**Shared 可写** |
| **设置→挂载外部文件夹** | `ui/settings/MountedFoldersScreen.kt`；`data/MountedFoldersStore.kt:45-72`；`Routes.MOUNTED_FOLDERS`（`AppNavigation.kt:881`） | 用户用 SAF 选的真机目录，绑定到 `/var/minis/mounts/<name>` |

> 结论：如果用户说"工作区功能板块"，最可能指**会话分组列表**或**共享/挂载文件浏览**；两者都不是"项目工作区"，需要新增"预设"概念才能承载小说项目库。

### 8.2 问题2：`/var/minis/workspace/` 与 `/var/minis/mounts/<name>` 的关系；用户能不能"选一个工作区"？

**两者是不同生命周期、不同隔离级别的东西：**

| 维度 | `/var/minis/workspace/` | `/var/minis/mounts/<name>/` |
|---|---|---|
| 创建 | 每个会话惰性创建（`sandbox/SessionMounts.kt:33` `SESSION_SUBDIRS` 含 `workspace`；`:71` 按 `sessionDir(filesDir, sessionId, sub)` 建） | 用户在 SAF 里选目录后注册（`data/MountedFoldersStore.kt:104-121` `add()`；`sandbox/PRootKernel.kt:322-360` `applyMountedFoldersSnapshot()` 绑到 `/var/minis/mounts/<name>`） |
| 作用域 | **仅当前会话**（`filesDir/minis-sessions/<sessionId>/workspace`，`data/session/SessionStorage.kt:9`） | **全局**：所有会话都看到同一个挂载（`SessionMounts.kt:130-134` `externalMountsSnapshot()`） |
| 生命周期 | 随会话删除而删除（`data/session/SessionDeleter.kt:20-24, 95-97`：`storage.deleteFiles(id)` 连 workspace 一起删） | 用户真机目录，会话/App 删除不影响（除非用户自己删） |
| 持久性 | 弱（会话级） | 强（真机存储） |

**用户能不能在 UI 上"选一个工作区"？**
- 不能"选一个 workspace 让会话切过去"。会话一旦建立，`/var/minis/workspace` 就固定指向该 `sessionId` 的目录（`SessionMounts.kt:71`），**没有切换/重绑入口**。
- 能"选一个外部目录"：设置→挂载外部文件夹（`MountedFoldersScreen.kt`）选目录 → 得到 `/var/minis/mounts/<name>`，这个在**所有会话**都可见。
- 能"浏览共享目录"：设置→共享文件夹（`SharedFoldersScreen.kt`）里的 `Shared` 是 `/var/minis/shared`，**全局可写**。

> 所以"选工作区"在现有 App 里只能落成"选一个挂载目录"或"用 shared 目录"；没有"把会话绑定到某个项目工作区"的概念。

### 8.3 问题3：会话（Session）和工作区是什么关系？一个会话一个 workspace 吗？

**一个会话 = 一个 workspace，不能切换，也不能一个会话绑多个 workspace。** 证据链：

1. 新建会话：`ui/sessions/SessionListViewModel.kt:980` `createNewSession(groupId, folderId)` → `ChatViewModelStore.newChatDraftId(groupId, folderId)`（草稿 id 里编码了分组/文件夹，见 `ui/chat/ChatViewModelStore.kt:619-627`）。
2. 首条消息落正式 session 后，shell 启动时 `SessionMounts.build(filesDir, sessionId, externalMounts)` 为**该 sessionId** 建 `attachments/offloads/workspace/browser` 四个目录（`SessionMounts.kt:64-97`）。
3. 路径解析：`PRootKernel.resolveSessionHostPath(sessionId, "/var/minis/workspace/…")` → `filesDir/minis-sessions/<sessionId>/workspace/...`（`PRootKernel.kt:795-807`）。
4. 删除会话：`SessionDeleter.deleteTree` 对该 sessionId 调 `storage.deleteFiles(id)`（`SessionDeleter.kt:95-97`），workspace 一并删除。

**但"跨会话共享"是可行的**，靠另外两类目录：
- 全局目录 `/var/minis/{memory,skills,shared}`：`SessionMounts.kt:36,74` + `PRootKernel.kt:277-290` `registerGlobalBindMounts()`，所有会话同一份，且**无需 PRoot 启动即可被 file_read/file_edit 解析**。
- 外部挂载 `/var/minis/mounts/<name>`：`SessionMounts.kt:130-134`，所有会话同一份。

> 结论：小说项目库要"一个项目多个会话共同维护"，必须放 **shared 或 mounts**；放 workspace 会被会话删除连带清掉。

### 8.4 问题4：实现"新建工作区时选一个预设，然后 AI 按预设把目录建出来"的最小改动 + 预设选择放哪

**能做。** 分两条路线：

#### 路线 A：零 Kotlin 改动（把预设写成 skill，先用斜杠/技能入口触发）

- 预设 = 一个 skill（`SKILL.md` 写"开新书 SOP + 目录清单 + 初始规则"），siblings 放 `references/`（061 路由表、062—083 规则）与 `scripts/`（`project_read.py`、`make_diff.py`）。
- 安装：`data/repository/SkillRepository.kt:940-950` `writeSkillFile()` 或导入 API（`:489` `importFromMarkdown` / `:538` zip / `:620` URL）。
- 触发：用户发一句"用《XX》预设开新书"或走 skill 列表；AI 用 `file_write(create_dirs=true)` / `shell_execute mkdir -p` 在 `/var/minis/shared/小说项目库/<书名>/`（或 `/var/minis/mounts/小说/<书名>/`）建骨架，写前用 `user_ask` confirm（`UserAskCore.kt:719-745`）。
- 优点：不动 App 代码、预设可热更新；缺点：没有"选预设"的图形入口，靠对话触发。

#### 路线 B：加"预设选择"UI（推荐，用户明确要"选择好了"）

**最小改动 4 处：**

1. **预设清单来源**：复用 skill 元数据（`SkillRepository` 的 `skills` StateFlow / `skillPromptFragment`），把带 `preset` 标记的 skill 列出来即可，无需新表。
2. **选择入口（二选一，推荐第 2 个）**：
   - (a) 挂到现有**分组创建**流程：`ui/sessions/GroupPickerSheet.kt:92` 的 Create 区加一个"预设"下拉；`ui/sessions/SessionListViewModel.kt:758-790` `applyGroupChoice` 的 `GroupChoice.Create` 分支里，`createFolder` 之后若选了预设，创建一条新会话并投递"按预设建库"的指令。
   - (b) 新增独立**"新建项目/工作区"入口**：在 `ui/settings/SharedFoldersScreen.kt`（Shared 可写，天然适合放项目）或聊天菜单（`ChatScreen.kt:3487` 附近）加一项；点击弹预设选择 → 建目录 → 起新会话注入预设。
3. **持久化"这个分组/项目用了哪个预设"**（若走分组路线）：`data/db/FolderEntity.kt` 加一列 `preset_id: String? = null`，并加 Room 迁移（该实体已有 `origin`/`description` 等列，加列风险低）。若不想动 DB，可把 preset 存进 `description` 前缀（不推荐，污染语义）。
4. **触发 AI 建库**：`ui/chat/ChatViewModel.kt` 已有 `agentTools` 与 `executeTool` 收口（见第 ② 节）；新增一个 `novel_project_init` 工具（见第 ④ 节 MVP+）或在 skill 里定义 SOP，由 `user_ask` confirm 后执行。项目根目录建议：
   - `/var/minis/shared/小说项目库/<书名>/`（全局、可写、可在设置→共享文件夹里浏览）；或
   - `/var/minis/mounts/小说/<书名>/`（与 `NovelTools.kt:20` 的 `DEFAULT_PROJECT="/var/minis/mounts/小说/小说项目"` 对齐，且是用户现成路径）。

**预设选择 UI 放哪最合理**：放在**新建项目/工作区**的入口（路线 B 的 (b)），而不是会话分组里——因为"预设"是项目级属性，分组只是会话归档，二者语义不同；若产品希望"分组即项目"，再退而求其次挂 `GroupPickerSheet`。

### 8.5 问题5：现有代码里有没有类似"项目模板/脚手架"的钩子可复用？

有可复用的"种子/写文件"钩子，但**没有完整的项目脚手架抽象**：

| 钩子 | 证据 | 可复用点 / 局限 |
|---|---|---|
| 内置 skill 播种 | `SkillRepository.kt:1238-1270` `installBundledSkills()`（启动时安装 `skill-creator`，正文常量 `:1686` 起） | 可照此在启动时播种"小说预设" skill；但只支持单个硬编码 skill，扩展性一般 |
| 任意 skill 文件写入 | `SkillRepository.kt:940-950` `writeSkillFile()`（自动建父目录、写 SKILL.md 触发 rescan） | 可把预设模板（SKILL.md/references/scripts）写进 `minis-global/skills/` |
| 作品/卷/章元数据初始化 | `core/novel/NovelRepository.kt:75-96` `createWork()`（建 work + 默认 volume，落 `filesDir/novel/works.json`） | 是"结构化作品"的初始化，不是用户那套目录骨架；可作为可选索引 |
| 会话目录初始化 | `sandbox/SessionMounts.kt:64-97` `build()`（缺目录则 `mkdirs`，失败显式报错） | 证明"按清单建目录"的模式现成；但它是每会话 shell 启动时调用，不适合放项目模板 |
| 挂载占位目录 | `sandbox/PRootKernel.kt:391-420` `materializeMountTargets()` | 建占位目录的示例，不是模板系统 |
| FileProvider 根 | `res/xml/file_provider_paths.xml:41-50` | 挂载目录可预览/分享，项目库文件可复用 |
| 用户确认 | `agent/userask/UserAskCore.kt:719-745` | 任何建库/写文件前都可强制 confirm |

> 结论：**没有现成的"项目模板/脚手架"API**，但有"写文件 + 建目录 + skill 播种 + 确认"四块积木，拼起来即可；最小实现就是"预设 skill + 一个 init 工具/入口"。

### 8.6 推荐落地形态（回答"能不能让用户在工作区层面选预设"）

**能，推荐这样落地：**

1. **概念对齐**：把"工作区"定义为**项目库（Project）**，落在 `/var/minis/shared/小说项目库/<书名>/` 或 `/var/minis/mounts/小说/<书名>/`；**不要**用 `/var/minis/workspace`（会话级、会被删）。
2. **预设 = skill**：每个预设一个 `SKILL.md`（+ `references/` 规则、+ `scripts/` 工具），可用 `SkillRepository.writeSkillFile` 安装；这与"写成技能也可以"完全吻合。
3. **选择入口**：新增"新建项目"入口（设置→共享文件夹 或 聊天菜单），列出预设 skill；选中后建目录、起新会话、注入预设。
4. **AI 建库**：复用 `file_write(create_dirs)`/`shell_execute mkdir -p`；如需实体按钮，加 `tools/NovelProjectInitTool.kt`（第 ④ 节 MVP+），并在 `AgentTools.kt:56-82` 注册、`ChatViewModel.kt:14131-14200` 分发。
5. **确认铁律**：建库、覆盖、归档前统一走 `user_ask` confirm；未 `approved` 不落盘（详见第 ⑥ 节）。
6. **可选增强**：若希望"分组即项目"，给 `FolderEntity` 加 `preset_id` 列并在 `GroupPickerSheet` 的 Create 流程里选预设；但这是会话归档语义的借用，不如独立"项目"入口干净。

### 8.7 该方向的风险/红线

- **最大坑：把项目库放错目录**。`/var/minis/workspace` 随会话删除（`SessionDeleter.kt:95-97`），用户会丢稿。必须放 `shared` 或 `mounts`，并在 UI/文档里写清。
- **挂载可写性**：`/var/minis/mounts/小说/` 若是 SAF 只读挂载，`file_write/edit` 会拒（`FileWriteTool.kt:71-77`）；建库前要检查 `effectiveWritable` 并引导解锁。
- **预设选择与会话分组混淆**：分组只是归档，不产生目录；若强行把预设塞进分组，会让"分组"语义过载，后续难维护。建议独立"项目"概念。
- **skill 热更新**：写/装预设 skill 后要触发 `SkillRepository.requestReload`（`:1289-1306`；`ChatViewModel.kt:14177`）才能被下轮 prompt 看到。
- **DB 迁移**：若给 `FolderEntity` 加 `preset_id`，必须写 Room 迁移，否则升级崩；本报告建议优先走"独立项目入口 + skill 预设"，避免动 DB。
- **只读边界**：本节仍为只读调研，未改 App 代码、未提交远端。
