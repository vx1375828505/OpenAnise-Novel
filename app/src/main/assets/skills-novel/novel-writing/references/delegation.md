# 子代理委派契约（delegation）

> 本文件是 `novel-writing` 技能的参考资料，冻结 6 个创作角色
> （outline / character / foreshadow / polish / review / consistency）通过基座原生
> **`subagent_task`** 工具派发的输入/输出协议：如何用 TaskArgs 写自包含 `task`、
> 如何用 `context` 递材料、子代理结果如何返回、零写纪律与失败处理。
> 上位依据是 `convergence.md`（编排/仲裁/收敛）。本文件取代历史上的
> 专用调度器 JSON 信封契约——**旧信封字段（delegation_id、round、
> 公共 payload 信封等）一律作废**，不得在新派发中引用。

---

## 1. 工具与协议总览

- **唯一派发入口**：`subagent_task`（工具名常量 `SubAgentDefinition.TOOL_NAME`）。
  它把「发起委派」和「查看/停止委派」合并为一个工具：`action` 默认 `delegate`。
- **子代理形态**：隔离的隐藏子会话，有独立工具循环，**看不到主对话、没有记忆**。
  因此 `task` 必须是一份自包含 brief，`context` 必须带齐原始材料。
- **零写**：子代理只产建议态结果；落库、改稿、改状态由主 AI 在拿到结果后显式执行。
- **并发**：同一会话最多有限个子代理同时运行；多派发可并行，超出部分排队
  （`status=queued`），**不要为排队任务重复派发，也不要轮询**。

### 1.1 为什么不再有 JSON 信封

旧机制由专用调度工具冻结一套请求/响应 JSON 信封（含 `delegation_id`/`round`/
公共 `payload` 外壳）。新机制下：

- 调度与状态查询合并进 `subagent_task` 的 `action`，信封编号与轮次字段不再有载体；
- 子代理的产出是**它的最终自然语言回复**，由主 AI 直接阅读，不再是被解析的 JSON 对象；
- 因此本契约改为约束：`task` 写什么、`context` 放什么、结果用什么结构写、
  主 AI 如何消费。各角色结果统一采用 `references/<role>.md` 里约定的
  Markdown 结构（含末尾「自检 · 状态」行）。

---

## 2. TaskArgs 字段表（派发参数）

派发时的完整参数（字段名即工具 schema，勿自造）：

| 字段 | 类型 | 必填 | 适用 action | 说明 |
|---|---|---|---|---|
| `tool_title` | string | 是 | 全部 | 5–10 词中文简述，显示在工具栏与记录里（如「大纲代理展开第三卷细纲」「查看审读代理进度」）。 |
| `action` | enum | 否 | 全部 | `delegate`（默认，发起委派）/ `status`（查看）/ `steer`（纠偏运行中的）/ `cancel`（停止）/ `resume`（恢复被中断的）。 |
| `task` | string | delegate 必填 | delegate | **自包含 brief**：目标、成功判据、材料位置、约束、要求返回什么。子代理只看得到它——写成给刚进门同事的完整交代。 |
| `agent` | enum（名册） | 否 | delegate | 指定哪个子代理执行；**省略则用内置通用子代理**。见 §3。 |
| `model_choice` | enum | 否 | delegate | 仅当所选子代理为 Auto 时生效（用户钉死模型组则忽略）。**默认 `same_as_me`**；`default_model` 仅用于确需更强推理的任务；`sub_model` 仅用于机械、边界清晰、可一眼核验的任务。 |
| `context` | string | 否 | delegate | 原始材料逐字提供（正文、大纲、资料卡、章节摘要、文风令牌等），追加在 `task` 之后。见 §4。 |
| `max_minutes` | integer | 否 | delegate | 墙钟预算（分钟），默认 10，最大 60；超时则返回已产出内容 + `status=timeout`。 |
| `wait` | boolean | 否 | delegate | `false`（默认）：立即返回 `status=running` + `job_id`，结果稍后以**新消息**送达。`true`：阻塞直到完成并在此返回结果——**仅在下一步必须依赖结果时才用**。用户在你等待期间发消息，运行会转后台、调用返回 `status=running`。 |
| `progress_report` | enum | 否 | delegate | `none`（默认）/ `moderate`（每分钟）/ `frequent`（每 15 秒）。每次进度消息消耗一轮，无特殊需要保持 `none`。`wait=true` 时忽略。 |
| `job_id` | string | steer/cancel 必填 | status/steer/cancel | 发起时返回的任务号（可用前缀）。`status` 省略则列出本会话全部子代理。 |
| `message` | string | steer 必填 | steer | 对运行中任务的纠偏指令（「聚焦定价，跳过迁移说明」）。保留已完成工作，不同于取消重派。 |
| `child_session_id` | string | 否 | resume | 指定恢复哪个被中断的子会话；省略则恢复本会话全部中断任务。 |

> 字段顺序（工具 schema）：`tool_title, action, task, agent, model_choice, context,
> max_minutes, wait, progress_report, job_id, message, child_session_id`。
> `task` 不在 schema 的必填列表里（因为 status/cancel 不需要它），但 **action=delegate 时必填**，
> 调度器会拒绝无 `task` 的委派。

---

## 3. agent 名册与 instructions 映射

`agent` 的合法值是用户在设置里配置的**子代理名册**（wire 名，非显示名；大小写/空白不敏感，
做折叠匹配）。名册里始终有一个内置通用子代理，省略 `agent` 或匹配不到名字时按规则处理
（匹配不到 → `unknown_agent`）。

6 个创作角色有两种落地方式（**推荐 A**，符合 D3「prompt 只常驻一份索引」）：

### A. 通用子代理 + task 粘贴角色说明（推荐）

- 不向名册注册 6 个具名子代理 → 系统提示词里只有 `novel-writing` 一份技能索引，
  不额外占用常驻预算。
- 派发时省略 `agent`（走内置通用子代理），把 `references/<role>.md` 的
  「任务说明（派发时粘贴）」整段**原样写进 `task`**，再接具体任务。
  instructions 上限约 4000 字符，故只粘「任务说明」段，不粘整个 reference。

### B. 具名子代理（可选，需 B4 播种注册）

- 若应用侧已按 `subagents.<id>.instructions` 注册 6 个具名子代理，可用 `agent=outline` 等
  直接指定；此时角色说明由该定义的 `instructions` 注入子会话，`task` 只写具体任务与要求。
- 注意：名册里每个具名子代理的 `description`（≤200 字符）会进入每轮系统提示词的名册，
  6 个即额外常驻一份索引——与 D3 的默认取舍相反，需产品侧确认后再启用。

> 无论 A/B，角色专业能力都以 `references/<role>.md` 为唯一权威源；B 方式的
> `instructions` 内容须与该文件的「任务说明」段保持一致，改动时同步更新。

---

## 4. task 与 context 的写法

### 4.1 task（自包含 brief）推荐模板

```
[角色说明]  <粘贴 references/<role>.md 的「任务说明（派发时粘贴）」整段>（方式 A）
[具体任务] 一句话目标：如「为第三卷展开第81–100章细纲」
[模式/范围] 如 outline=chapters 81–100；character=enrich；review=审第47章
[成功判据] 什么叫完成：如「每章含目标+2–4爽点+伏笔挂点，无空壳章」
[材料说明] 见 context；缺什么就声明「缺少：xxx」并停止，禁止编造
[返回要求] 按角色 reference 的「结果格式」输出，末尾带「自检 · 状态」行
[迭代] 第 2 轮起：附上上一轮结果，要求在旧址上优化、不推倒重来（见 §6）
```

### 4.2 context（原始材料）

- 材料由主 AI 用**当前可用的工具**或用户原文取得后**逐字**放进 `context`；
  新基座已无旧的数据预取工具，不得在 `task` 里承诺调用它们。
- 可用工具包括记忆三工具 `novel_memory_text` / `novel_memory_link` /
  `novel_memory_graph`；其余数据以会话中用户提供或工具实际返回的为准。
- **材料缺失处理**：取不到就留空并在 task 里注明「缺少：xxx」。**禁止用假数据填充**。
- 子代理只读 context，不主动取数（除非该子代理本身配置了工具权限）。

各角色的材料清单见其 reference 的「输入材料」节，汇总如下：

| 角色 | context 必备材料 | 缺失时 |
|---|---|---|
| outline | 作品设定、展开范围、既有大纲、已写章节摘要（续写时） | 缺摘要则声明缺项并停止 |
| character | 作品设定、模式、指定角色名、既有角色卡（丰满/推导时）、章节摘要 | 缺既有卡则声明缺项并停止 |
| foreshadow | 模式、伏笔台账（audit/both）、章节摘要、大纲（plan）、章区间 | 缺台账则声明缺项并停止 |
| polish | 待处理正文（必需）、文风令牌、用户明示要求、限定维度 | 缺正文则声明缺项并停止 |
| review | 待审正文（必需）、角色卡摘要、前文摘要（多章连审）、阈值 | 缺正文则声明缺项并停止 |
| consistency | 待检章节（必需）、资料库快照（必需）、冲突类型、查重阈值 | 缺章节或快照则声明缺项并停止 |

---

## 5. result 返回协议

- **结果即子代理的最终回复**：一段 Markdown 文本，不是 JSON 对象。主 AI 直接阅读并消费。
- **统一结构**（各角色以自身 reference 为准）：`## 结论` → 主体产物 → `## 自检`
  （含`- 状态：` 与 `- confidence：` 行）→ `## 上报`。
- **状态语义**（写在结果末尾「自检」里，供主 AI 分流）：

| 状态 | 含义 | 主 AI 处置 |
|---|---|---|
| `ok` | 正常产出 | 进入汇总/收敛流程 |
| `insufficient_input` | 关键材料缺失 | 按「上报 · 缺项」补取后重派（同一委派 ≤1 次），否则转用户 |
| `conflict_detected` | 发现与权威源冲突 | 不自行落库，按 `convergence.md` §3 处理 |
| `needs_user_decision` | 需用户拍板 | 进升级清单 |
| `error` | 执行失败 | 按 §7 处理 |

- **异步送达**：`wait=false` 时，结果以新消息（前缀含「后台任务完成」）到达主会话，
  附带 `job_id`；取消/超时/失败同样会回消息。**主 AI 收到结果消息后无需再调
  `action=status` 去取**。结束回合时若无事可做就结束，不轮询、不承诺稍后汇报。
- **零写保证**：子代理结果中不得出现「已保存」「已写入」等落盘表述；如有，主 AI
  视为无效声明并自行核验数据状态。

---

## 6. 迭代与收敛

- 新协议没有 `round` 字段；迭代由主 AI 在 **第 2 轮 `task` 里粘贴上一轮结果文本**
  实现（参考旧机制的 `previous_round` 语义，但载体是文本而非信封字段）。
- 轮次上限 **3 轮**，收敛判据与失败处理见 `convergence.md` §5：
  - 第 2 轮与第 1 轮结果长度差 <5% → 判定收敛，停止；
  - 第 3 轮后无论是否收敛都停止，注明未完全收敛；
  - 3 轮后仍有阻断级未解决问题 → 不自动重试，升级用户。
- 纠偏运行中的任务用 `action=steer` + `message`，保留已完成工作；
  不要取消重派来替代纠偏。

---

## 7. 失败处理

| 情形 | 表现 | 处置 |
|---|---|---|
| 未知 agent 名 | `unknown_agent` | 按名册实际名字重派，或省略 `agent` 走通用子代理 |
| 无 task 的委派 | 调度器拒绝 | 补 `task` 后重派 |
| 排队 | `status=queued` | 等待槽位释放后的结果消息，不重复派发、不轮询 |
| 超时 | `status=timeout` + 部分产物 | 可用则采用部分产物并注明；否则切更大 `max_minutes` 重派一次 |
| 失败/中断 | `status=failed` / `interrupted` | `action=resume` 恢复中断任务；真失败则引导重派（同一委派 ≤1 次） |
| 结果不可解析 | 结果缺「自检」结构 | 按 `insufficient_input` 处理，要求按结果格式重述一次 |

---

## 8. 零写纪律（硬约束）

1. 子代理只读：不得下发任何数据写入类工具；结果一律建议态。
2. 主 AI 是唯一编排者：派发、汇总、裁决、落库四件事收在主 AI 一处；
   子代理之间不发生直接调用。
3. 冲突不私了：按 `convergence.md` 显式仲裁，子代理只上报不裁决。
4. 失败可见：失败返回原因与可用角色清单，不返回空结果冒充成功。

---

## 9. 版本演进

- 本契约随 `novel-writing` 技能 `version` 走；结果结构变更须同步更新对应角色的
  「结果格式」节。
- 任何变更不得破坏四要素：task 自包含、context 真实、结果末尾「自检 · 状态」行、
  零写纪律。
