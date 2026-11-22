# OpenAnise-Novel 技能包移植 · 工作日志

日期：2026-11-23

## 背景
- 主项目 `OpenAnise-Novel`（Android 网文写作 Agent），旧账号被封，代码已镜像到 `vx1375828505/OpenAnise-Novel`。
- 用户要求：先本地完成、不要急着提交远端。
- 用户说"做不过来叫团队" → 组建 `openanise-port` 团队并行推进。

## 关键发现
1. **原始资产找回**：25 流派 + 6 子代理 + 2 契约 + persona/style_tokens 共 37 个文件，保存在新仓库历史提交 `c170c5eb`（`c170c5eb5636`）里，不需要旧账号。已批量下载到 `C:\1111\openanise-skills\ref-old\`。
2. **主项目真实状态**：远端 main 比手机本地新；技能包移植是真正剩余的大头（约 9 人天）。
3. **D1-D6 决策全部拍板**（均选推荐）：见 `B0_DECISIONS.md`。

## 团队分工（4 路并行）
- **B1 infra-agent**：播种器 `SeedNovelSkills.kt` + 守卫 + 8 单测 → `out/kotlin/`。重要修正：上游 SkillRepository 无 prefs 字段（§4.5 是示意码），改接口注入；description/version/body 从 frontmatter 解析不硬编码；R7 播种失败不抛异常（GH#147 事故）。
- **B2 genre-agent**：25 流派 SKILL.md frontmatter 改造 → `out/genres/`。返工一次：初版按错误的任务书保留了 id/trigger 字段，按方案 §3.2/§4.2 权威规范改为只留 name/version/description，trigger 词融入 description（≤195 字符），正文逐字零改。
- **B3 subagent-agent**：novel-writing 父技能 + 9 references → `out/subagents/`。契约重写最重（delegation 190 行）。
- **B4 team-lead 亲自**：`NovelGenreSkillMapper.kt`（25 条映射自动生成）+ `B4_INTEGRATION_PLAN.md`。标注了「作品切换自动联动」超出 D2 授权范围，默认按保守方案（只提供 Mapper，不动 setSessionOverride），待用户确认。

## 三方一致性校验
B1 播种器 id/name、B2 目录名、id_map.json 三者 25 项逐项完全一致，零差异。已写入 `assemble.py` 交叉校验。

## 总装
`assemble.py` 把 B1/B2/B3/B4 产物拼成 `final/skills-novel/`，含 id/name 交叉校验、frontmatter 字段校验、旧工具调用形态校验（说明性提及不算）、清单生成。

## 待办
- 等 B3 的 style-tokens.md / PERSONA_NOTE.md / VALIDATION.txt 完成。
- 修正 novel-writing 父技能 frontmatter 多余的 `id:` 字段（§3.2 只准 name/version/description）。
- 运行 assemble.py 总装并校验。
- 向用户汇报成果，询问是否提交远端（用户明确要求提交前先问）。
- 单测修复（C:\1111\openanise-fix\）仍留本地未提交，与技能包移植是两批独立改动。

## 铁律遵守
- 本地不能编译，CI 是唯一编译器 → 所有 Kotlin 产出只声称静态一致，不声称编译通过。
- 提交远端前必须问用户 → 全程未提交（除早前已告知的 GPL-3.0 合并 f3e4472）。
- H:/111 不可写 → 所有产出存 C:/1111。

## 收尾（01:05）
- 总装脚本 assemble.py 运行通过：35 个 Markdown（26 SKILL.md = 25 流派 + novel-writing 父技能 + 9 references），清单 final/manifest.json。
- 总装校验覆盖：B1/B2/id_map 三方 25 id 逐项一致、父技能已纳入播种（解析 PARENT_ID 常量）、父技能 frontmatter 三字段、旧工具仅允许说明性提及（调用形态残留 0）。
- infra-agent 补充发现并修复：writeSkillMd 不复制 references → 加 copySiblingAssets 桥接（9 个 references 路径）；守卫 key v1→v2 让老设备补装父技能；父技能默认开启（D3，与流派默认关不同）；10 个单测覆盖。
- 团队三名成员（genre/subagent/infra-agent）均已关闭。
- 全程未提交远端。用户要求提交前先问——已准备就绪，等待用户决定。
- 两批独立本地改动：① 单测 9 错修复（C:/1111/openanise-fix/，7 文件）② 技能包移植（C:/1111/openanise-skills/）。

## 01:45 固定"项目库底子"（用户要求：先看清结构、固定底子）
- 用户真实库 C:/222/小说项目 只读摸清：9 大目录 + 全局连续编号(001-087) + 25 个门牌 + 文件卡四要素 + 四条铁律 + 055 四约束 + 061 一轮一规则 + 变更/体检/度量/退役维护机制。
- 抽象成空模板 C:/1111/novel-template/（不动 C:/222 原件）：88 文件、73 个含占位符、工具脚本 3 个。
- 团队并行：template-agent(核心导航 7 文件)、maint-agent(维护机制)、rules-agent(22 规则+3 词库+7 缺文件)，lead 搭骨架+门牌+设定空壳+清理残留。
- 总校验 validate_template.py：核心 10/10、目录 9/9、门牌 25、规则原文(四铁律/四约束/061路由/P0P4/四指标)全保留、失效引用 0、本书名词残留 0 → PASS。
- 结论：这套库能撑几千章的设计原理已固化成可复用模板，下一步可作为 App「一键开新书」生成蓝本（待用户拍板方向）。

## 02:00 底子→App 可导入 skill 包
- 用户确认"不能动我小说文件"→ 核查确认：C:/222 全程只读零改动（全库无 {{占位符}} 污染、正文完好、01:46 的改动是用户自己的另一会话写的 089_分层轮读诊断.md）。
- 把 C:/1111/novel-template 底子打包成 App 可导入 skill：
  - skill-package/novel-project/SKILL.md（86行：四条铁律 + 开新书/日常写作/库维护三套SOP + 路径约定）
  - template/ 122文件完整底子；scripts/ 3个工具脚本
  - novel-project.zip 128KB / 126文件，解压验证核心文件齐全
  - 导入指南.md（文件导入/URL导入两种方式 + 验收标准）
- 关键设计：SKILL.md 的 description 130字符（≤200上限）；frontmatter 仅 name/version/description 三字段（符合 SkillRepository 解析规则）；name/id=novel-project（ASCII，避 slugify 中文变空）
- 待用户：选择导入方式；若走 URL 导入需批准推远端。

## 02:20 按用户要求去掉文件夹序号
- 用户明确：文件夹序号不要（"不需要加序号"，那是他自己区分用的）。
- 已把模板 11 个目录的序号前缀去掉：00_项目导引→项目导引、01_项目中枢→项目中枢、02_核心资料库→核心资料库、03_正文→正文、05_草稿→草稿、06_封面→封面、07_参考资料→参考资料、08_数据库维护→数据库维护、04_支线→支线、02_体检→体检、04_退役归档→退役归档。
- 67 个文件内的路径引用同步替换（门牌/路由/文件卡/README/SKILL.md/导入指南）。
- 文件名编号（001/005/061 等）保留——061 路由表靠编号定位规则，去掉会让路由体系失效；已向用户说明。
- 终验：核心 10/10、九大目录 9/9、门牌 25、规则原文全保留、失效引用 0、目录序号残留 0。
- zip 重打包：127.8KB / 127 文件，template 顶层 9 目录全部无序号。
- C:/222 用户原库全程只读，未动一字节。

## 02:22 app-scout 调研结论（工作区机制）
- **App 没有独立"工作区"板块**。用户直觉中的"工作区"对应四个现有面：会话分组(chat_folders 表)、聊天文件浏览器、设置→共享文件夹(/var/minis/shared/)、设置→挂载外部文件夹(/var/minis/mounts/<name>/，用户现库在此)。
- **重大风险**：`/var/minis/workspace/` 是会话级目录，随会话删除而消失。小说库绝不能放这里，必须放 shared/ 或 mounts/。
- 会话与 workspace 一对一，界面层面无"切换工作区"能力。
- "选预设→AI 建库"可做，但需新增项目/预设入口（现有 UI 无此概念）；预设本身可完全写成 skill（符合用户"写成技能也可以"）。
- 无现成项目模板/脚手架 API，但四块积木齐备：file_write 建目录写文件、SkillRepository 播种、user_ask 确认、shell_execute。
- 落地方案：第一步零代码（skill + 对话触发，即现有 zip）；第二步加"新建项目"入口（约 4 处代码，含可能的 DB 迁移）。

## 02:30 推送 novel-preset 仓库
- 用户批准推送。新建公开仓库 https://github.com/vx1375828505/novel-preset
- 内容：SKILL.md（根）+ template/(113文件,9目录无序号) + scripts/(3) + novel-project.zip(123.7KB)
- 推送方式：git push 被网络代理挡住（CONNECT tunnel 502），改用 GitHub Git Data API：117 个 blob + tree + commit → commit 6af1def
- **关键限制发现**：App 的 importFromGitHub 逐文件调 api.github.com，匿名额度 60 次/小时；113 个文件必然超限 → URL 导入会残缺。故上传 zip，走"下载 zip → App zip 导入"路线。
- 推送前清理了 10 个带真实角色名的孤儿重复文件（045_凤舞_支线.md 等），门牌引用的是 045_支线_角色1.md 那套。
- 隐私提醒已告知用户：仓库公开，可随时删除。
