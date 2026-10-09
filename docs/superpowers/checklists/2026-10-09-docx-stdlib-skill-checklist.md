# 标准库 DOCX 技能交付清单

当前修订：R1；实际修订日：2026-10-09；mode=default。当前交付为 1.0.1，修复沙箱输入流兼容缺陷。下方 R0 和首次提交记录保留历史；当前范围与验证以 R1 为准。本轮提交记录见进度账本，历史未提交状态保留。

日期：2026-10-09；修订：R0；mode=default。基线：`c8e7a90a78915703cbbf5443de54280ba96af548`。用户明确目标为 CM Agent，要求生成使用 Python 标准库的 DOCX 技能插件 ZIP。

范围：独立技能资源、打包器、验证、六份工作包。非目标：修改平台、配置、数据库，或自动导入/发布技能；不改动已有脏文件、不提交。

当前证据：`SkillPackageParser.java` 允许单入口文本归档；`SkillProperties#getAllowedResourceTypes` 在沙箱启用后增加 `.py`；`AgentScopeSkillExecutionBridge` 使用 `run_skill_script`；README 与 `docs/configuration.md` 规定 `CM_AGENT_ARTIFACT_DIR` 和文件产物开关。CodeGraph 两次未命中目标解析器，按当前源码复核。

| 编号 | 状态 | 目标/文件 | 依赖 | 验收/实际证据 |
|---|---|---|---|---|
| T0 | 完成 | 导入与运行契约、六份工作包 | 无 | 目标系统确认；源码与配置复核；相对链接校验 |
| T1 | 完成 | `skill-packages/docx-stdlib` 四份资源 | T0 | 固定脚本、UTF-8 JSON、仅标准库；支持中文内容和安全文件名 |
| T2 | 完成 | 打包器、`dist/skills/docx-stdlib-1.0.0.zip` | T1 | 单 SKILL.md 与三资源；实际当前 Java 解析器通过 |
| T3 | 完成 | Python 测试、Rocky 容器和 Word 示例检查 | T2 | Windows 与 Python 3.12 均 12 项通过；示例 Word 打开、导出 PDF、单页 PNG 检查通过 |
| T4 | 完成 | 使用说明、账本与交付链接 | T3 | 真实模型闭环未执行与环境前置条件明确；未提交 |

真实系统导入、模型 TEST、发布、绑定与下载是部署使用阶段验收，本任务未执行，不能视为已通过。脚本生成不等于 Run 成功或附件下载可用。

## 2026-10-09 提交交付

用户后续指令“将修改的内容提交”授权提交本任务的技能源码、测试、使用说明与六份工作包，覆盖上方首次交付时“不提交”的限制。既有验证仍适用于未变更的技能源码；本轮只同步提交记录，未改变功能。提交说明为“新增标准库 DOCX 生成技能包”；最终编号见本主题路径的 Git 提交记录。生成 ZIP、预览及临时验证材料仍留在忽略目录；不推送、不导入或发布。

关联：[提示词](../prompts/2026-10-09-docx-stdlib-skill-prompt.md)、[设计](../specs/2026-10-09-docx-stdlib-skill-design.md)、[计划](../plans/2026-10-09-docx-stdlib-skill.md)、[实现](../implementation/2026-10-09-docx-stdlib-skill-implementation-design.md)、[账本](../progress/2026-10-09-docx-stdlib-skill-ledger.md)。


## R1 沙箱执行兼容修复

用户报告 SKILL_SANDBOX_FAILED，错误编号 4d252c56-8272-4f0e-8af1-bd4669324ec6。当前源码的文本引导使用 StringIO，文件引导使用子进程二进制管道。R0 测试只覆盖后者，不能证明文本启动兼容。

| 编号 | 状态 | 目标/依赖 | 新验收 |
|---|---|---|---|
| T5 | 完成 | 定位和复现；依赖 T0～T3 | 旧包在原始文本引导、有效输出目录下退出 2；定位 stdin.buffer 不兼容 |
| T6 | 完成 | 修复固定脚本；依赖 T5 | 文本/二进制输入均按 UTF-8 字节限制，兼容 stdout 无 reconfigure；四项新增回归通过 |
| T7 | 完成 | 原始引导与新 ZIP；依赖 T6 | 本地/Rocky 各16项；原始文本和文件引导通过；CMA1 文件字节/摘要/OOXML一致；当前解析器通过 |
| T8 | 待做（部署使用阶段） | 当前用户系统 TEST；依赖 T7、环境 | 上传新候选，确认 sandboxEnabled/artifacts.enabled、真实模型输入和附件下载；当前环境状态尚未获得 |

文件产物关闭时仍须失败；不修改平台退出码策略、不伪造成功、不更改用户配置。技能包修复已交付，T8 不能以隔离脚本替代。原有 T0～T4 证据保留，1.0.0 ZIP 仅作为历史复现，不再推荐使用。
