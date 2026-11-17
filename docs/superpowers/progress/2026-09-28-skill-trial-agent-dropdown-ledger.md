# 技能试运行目标 Agent 下拉选择进度账本

关联：[设计](../specs/2026-09-28-skill-trial-agent-dropdown-design.md)、[计划](../plans/2026-09-28-skill-trial-agent-dropdown.md)、[实现说明](../implementation/2026-09-28-skill-trial-agent-dropdown-implementation-design.md)。

## 状态

| 项目 | 状态 | 结果 |
| --- | --- | --- |
| 前端 Agent 下拉和状态提示 | 完成 | 复用既有 Agent 列表与试运行接口；仅显示启用且 ID 有效的选项 |
| 过滤逻辑回归测试 | 完成 | Node 测试 4 项通过 |
| JavaScript 语法检查 | 完成 | `node --check` 通过 |
| 控制台资源测试 | 完成 | `mvn -pl cm-agent-console -am test`（JDK 21.0.11）通过，14 项通过 |
| 浏览器桌面流程 | 完成 | test profile 页面加载并选中 `dropdown-browser-agent · qwen-max`；未发起试运行 |
| 窄屏检查 | 完成 | 390px 视口下文档宽度与滚动宽度均为 375px，表单下拉宽度为 275.8px，无横向溢出 |
| 构建 | 完成 | `mvn -pl cm-agent-server -am -DskipTests package` 使用 JDK 21 成功 |
| 数据库双库验证 | 不适用 | 本次无服务端、数据库、Flyway 或容器改动 |
| 提交 | 未提交 | 保持工作树状态，由用户决定是否提交 |

## 注意事项

工作树中原有 `application*.yml`、`.codex/`、`.impeccable/`、`.workbuddy/` 与 `application-ok.yml` 改动均未触碰。全仓 `git diff --check` 曾报告用户已有 `application.yml` 尾随空白；最终检查须限于本任务文件，以免把既有脏改动误归到本任务。

## 全量核定记录（2026-11-13）

- 核定结论：已归档。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：目标 Agent 下拉、桌面/窄屏与资源验证已完成，不涉及数据库或真实试运行。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：无未关闭的本包事项移交；原记录中的独立后续需求、生产使用条件及范围外限制继续保留，不作为其已实现或已验收的证明。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
