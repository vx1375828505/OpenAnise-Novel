# OpenAnise-Novel 技能包移植 · B0 决策批（已拍板）

> 本文件是团队并行开工的共享输入。D1-D6 已由项目负责人于 2026-11-23 全部拍板，均选推荐方案。

## 决策清单

| 编号 | 决策点 | 拍板结果 |
|---|---|---|
| D1 | 25 流派技能 id 生成 | **a) `genre-<slug>`**：播种器显式分配英文 id（如 `genre-urban_zhuixu`），UI 仍显示中文名。不动 iOS 同步路径。 |
| D2 | 流派技能默认开关 + 选用入口 | **默认关 + SessionSkillsSheet**：入口放会话技能面板，用哪个开哪个，不挤占提示词预算。 |
| D3 | 6 子代理打包形态 | **a) 单父技能 + references**：一个 `novel-writing` 父技能，references 里放 6 个角色，prompt 只常驻一份索引。 |
| D4 | 流派规约强制生效 | **c) 混合：前 60 行入提示词**：流派 SKILL.md 正文前 60 行（定位+规约）拼进系统提示词，其余按需读。 |
| D5 | persona.md 归属 | **应用内置人格常量**：用户不能当普通技能关掉。 |
| D6 | style_tokens 归属 | **novel-writing/references**：放父技能的 `references/style-tokens.md`，跟父技能一起管理。 |

## 对下游批次的约束

- B1 播种器必须按 `id_map.json` 的 `new_id`（genre-<genre_id>）播种，is_enabled 默认 false。
- B2 只改 frontmatter（desc 压缩到 ≤200、trigger 合并、version 字段），正文零改。
- B3 父技能名为 `novel-writing`，6 角色 + DELEGATION/CONVERGENCE + style_tokens 全部进 references/。
- B4 作品 genre 字段 → `genre-<genre_id>` 映射（复用 id_map.json），用 setSessionOverride 按作品选用。
- D5/D6 为跨批次约束：persona 不进技能系统；style-tokens 归 B3。
