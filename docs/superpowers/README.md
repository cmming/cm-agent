# Superpowers 工作包索引

本索引保留两个长期页面需求组及三组 2026-11-05 合并工作包的摘要。[完整工作包状态目录](WORKPACK-STATUS.md) 记录截至 2026-11-13 的主题、文档集合、显式修订、账本和后续入口；其中 Git 栏为历史快照，不代表实时状态。之后的新主题见下方入口。

工作包遵守六件套职责：清单定义任务、依赖和验收，设计记录方案与决定，计划定义执行方法和顺序，提示词保存某一修订的执行输入，实现说明记录实际改动与设计差异，账本唯一承载实时状态、验证证据、阻塞和提交信息。其他五份不复制状态表，只链接账本。历史主题保留当时的文档集合和状态，不因为新规则自动补齐或改写。

## 2026-11-13 之后的工作包

- [2026-11-18 项目优先聊天](progress/2026-11-18-project-first-chat-ledger.md)覆盖控制台 v2 的项目和普通聊天入口、归档历史与文件版本引用。
- [2026-11-17 docs 可用性复核](progress/2026-11-17-docs-usability-review-ledger.md)：入口、长文导航和配置说明；本主题不在 2026-11-13 历史快照中。

## 2026-11-13 状态目录

- [打开 2026-11-13 工作包状态快照](WORKPACK-STATUS.md)
- [六件套职责与单一状态源契约](../../.agents/skills/requirement-workpack/references/artifact-contract.md)
- 状态目录中的修订只来自文档显式声明；没有声明时显示“未声明”，不根据文件名推测。
- “最新账本”为空时表示该历史主题没有进度账本，不用其他文档补造。
- “后续入口”优先指向提示词、计划或账本。提示词只代表形成时的执行输入，不构成新的执行授权。
- Git 栏保留 2026-11-13 的历史快照；实际状态用 `git status` 核对，不随每次工作树变化更新目录。

## 工作包归档导航

先从[归档导航](WORKPACK-STATUS.md#归档导航)区分当前、待续、已归档和归档状态待核实的主题，再进入原账本查看依据。分类是账本记录的导航投影，不是第二份任务状态或验证结果。

2026-11-13 的[全量整理快照](progress/2026-11-13-docs-full-review-ledger.md#逐组核定快照)覆盖全部历史主题及 docs 文件，包含无账本主题的依据不足说明。不能用目录中的已归档组证明其他待续组的业务验收完成。

工作包采用保留原路径的逻辑归档；依据责任范围完成、明确取消或剩余事项逐项移交，而非日期、提交或发布状态。恢复同一需求时追加恢复记录和修订，独立需求另建工作包；详细条件与历史纠错要求统一见[产物契约](../../.agents/skills/requirement-workpack/references/artifact-contract.md#工作包归档与恢复)。重要取舍见[文档治理 ADR](../adr/0001-documentation-governance.md)，概念含义见[术语表](../../GLOSSARY.md)。

## 页面需求基线组

以下两组是按页面重组的需求基线，均使用 `mode=default`。R0 不代表功能未实施；后续修订分别补全授权模式、权限、配对生命周期、设备管理状态和首次连接指引。跨页面实施和当前修订以[状态快照](WORKPACK-STATUS.md)中的原账本及其后续修订为准，不能只凭本节的基线日期判断业务当前状态。

### 本地项目页

页面：`/console/v2/projects.html`
主题：`2026-11-03-projects-page`

| 文档 | 入口 |
|---|---|
| 清单 | [本地项目页清单](checklists/2026-11-03-projects-page-checklist.md) |
| 设计 | [本地项目页设计](specs/2026-11-03-projects-page-design.md) |
| 计划 | [本地项目页计划](plans/2026-11-03-projects-page.md) |
| 提示词 | [本地项目页提示词](prompts/2026-11-03-projects-page-prompt.md) |
| 实现说明 | [本地项目页实现说明](implementation/2026-11-03-projects-page-implementation-design.md) |
| 账本 | [本地项目页账本](progress/2026-11-03-projects-page-ledger.md) |

范围：本地文件夹登记、项目列表/详情、文件树/搜索/预览、项目会话、固定 `project_*` 工具、手工工具目录登记兼容入口、三种项目内授权模式、审批/撤销/命令、异步未知状态和响应式页面。

### 本机客户端页

页面：`/console/v2/local-client.html`
主题：`2026-11-03-local-client-page`

| 文档 | 入口 |
|---|---|
| 清单 | [本机客户端页清单](checklists/2026-11-03-local-client-page-checklist.md) |
| 设计 | [本机客户端页设计](specs/2026-11-03-local-client-page-design.md) |
| 计划 | [本机客户端页计划](plans/2026-11-03-local-client-page.md) |
| 提示词 | [本机客户端页提示词](prompts/2026-11-03-local-client-page-prompt.md) |
| 实现说明 | [本机客户端页实现说明](implementation/2026-11-03-local-client-page-implementation-design.md) |
| 账本 | [本机客户端页账本](progress/2026-11-03-local-client-page-ledger.md) |

范围：签名配对包、本机确认、RBAC、设备密钥存储、客户端最终落盘、设备列表/详情、二次撤销、三种 Run 授权模式边界、可独立运行的 fat JAR 分发、Windows/Linux/macOS 客户端和远程 Server 后续门禁。页面本身不执行文件、网络或命令。

## 跨组边界

| 能力 | 本地项目页 | 本机客户端页 |
|---|---|---|
| 文件根 | Server 所在电脑上的已登记项目目录 | 选定配对设备上客户端用户账号可访问的范围 |
| 执行位置 | Server 运行账号 | 配对设备上的用户账号 |
| `FULL_ACCESS` | 当前项目、Agent、Run 的固定 `project_*` 工具 | 当前 Run 内已授权设备类别，受设备身份和服务端约束 |
| 页面职责 | 项目登记、文件工作台、项目会话和审批 | 配对包、设备列表/详情、撤销和授权边界说明 |

两组可互相引用，但不能用一个页面的验收结果证明另一个页面的能力。

### 2026-11-05 合并工作包

当日五个过程主题已按页面和共享运行边界合并为三组，不再保留单文件总记录或原五个独立工作包。

| 范围 | 合并来源 | 六件套入口 |
|---|---|---|
| 本机客户端页 | `local-client-tool-input-fix` R1 | [清单](checklists/2026-11-05-local-client-page-checklist.md)、[设计](specs/2026-11-05-local-client-page-design.md)、[计划](plans/2026-11-05-local-client-page.md)、[提示词](prompts/2026-11-05-local-client-page-prompt.md)、[实现](implementation/2026-11-05-local-client-page-implementation-design.md)、[账本](progress/2026-11-05-local-client-page-ledger.md) |
| Projects 页 | `project-layout-refinement` R0、`local-project-non-loopback-switch` R1 | [清单](checklists/2026-11-05-projects-page-checklist.md)、[设计](specs/2026-11-05-projects-page-design.md)、[计划](plans/2026-11-05-projects-page.md)、[提示词](prompts/2026-11-05-projects-page-prompt.md)、[实现](implementation/2026-11-05-projects-page-implementation-design.md)、[账本](progress/2026-11-05-projects-page-ledger.md) |
| 共享运行环境 | `local-runtime-script` R3、`lan-console-listening` R0 | [清单](checklists/2026-11-05-shared-runtime-checklist.md)、[设计](specs/2026-11-05-shared-runtime-design.md)、[计划](plans/2026-11-05-shared-runtime.md)、[提示词](prompts/2026-11-05-shared-runtime-prompt.md)、[实现](implementation/2026-11-05-shared-runtime-implementation-design.md)、[账本](progress/2026-11-05-shared-runtime-ledger.md) |

## 历史来源

原 2026-10-29 本地项目工作区、2026-11-02 智能体操作授权和 2026-11-02 项目页样式工作包已按页面合并到上述长期工作包；原 2026-11-05 的五组过程文档和中间单文件记录已删除。历史来源名称、关键决定和证据适用范围保留在三个合并工作包中。

### 关联主题导航

以下只提供追溯入口，不表示一个主题的验收可替代另一个主题；账本中的旧状态和测试结论仍以形成时的版本、日期和环境为限。

- 工作区目录选择：[需求分析账本](progress/2026-10-27-workspace-directory-selection-ledger.md) → [独立实施账本](progress/2026-10-27-workspace-directory-selection-implementation-ledger.md)，分别查看原需求与实际实施及剩余门禁。
- 技能沙箱与文件产物：[沙箱账本](progress/2026-09-30-skill-sandbox-ledger.md) → [文件产物账本](progress/2026-10-08-skill-artifacts-ledger.md)。文件产物是后续独立需求，不覆盖沙箱历史。
- 项目页：[长期项目页账本](progress/2026-11-03-projects-page-ledger.md)与[后续页面及非回环调整账本](progress/2026-11-05-projects-page-ledger.md)分别记录各自范围。

这些主题继续保留原日期和路径；需要了解最新业务条件时先查[文档总入口](../README.md)中的专项生产说明，再核对对应账本。
