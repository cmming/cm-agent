# 本机客户端项目工作区实施提示词

日期：2026-11-09；当前修订 R3（2026-11-10）；mode=default。

## R3 续验结果（2026-11-10）

当前仍为 R3、mode=default；这是原任务的实现与验收收口，不新增需求修订或重复工作包。业务代码已提交并推送至 `d85f785`，客户端输出修复为 `c8d6ad3`。T6/T17 的真实 Windows 命令子集通过；T9 改为进行中，整体验收仍未闭合。

- Rocky：`17bf309` 上持久化全量 94/94 通过；`9e1745e` 上迁移/检查点/设备仓储及 core 命令定向通过；最终 `c8d6ad3` 对齐双库命令仓储增量 2/2 和 core 命令 3/3 通过。当前持久化/core 与该次验证无差异，不把全量 94 项冒充在最终提交重跑。
- Windows：真实已配对客户端、认证浏览器和 `OPENAI_COMPATIBLE / glm-5.3` 完成工具链查询、提案、HIGH 精确预览/批准、命令成功、Run 屏障、进程树停止、重复停止查询、客户端重启 UNKNOWN 回报与人工对账。所用 Agent 显示名为“测试Qwen”，实际模型以运行配置的 `glm-5.3` 为准。
- UNKNOWN：本机孤立回执为 UNKNOWN；服务端恢复后仍为 UNKNOWN/version=17，输出序号 69 与已有 1579 字节输出保留。人工对账 HTTP 200 不改变状态、版本、序号；真实审计存在；归档 HTTP 409 `CLIENT_PROJECT_TASK_PENDING`，测试进程数量为 0，未自动重放。
- 边界：使用既有登记的验收项目；原生目录窗口在当前自动化桌面不可操作，不能据此宣称完整“选择目录到开发”验收通过。远端 Server/TLS、多实例、设备撤销、Windows 注销/断电、文件写入竞争和完整窄屏/迟到响应矩阵仍未验。

具体命令、Run/命令编号、失败修复与证据适用范围以[账本](../progress/2026-11-09-client-project-workspace-delivery-ledger.md)为准。

关联：[清单](../checklists/2026-11-09-client-project-workspace-delivery-checklist.md)｜[设计](../specs/2026-11-09-client-project-workspace-delivery-design.md)｜[计划](../plans/2026-11-09-client-project-workspace-delivery.md)｜[实现说明](../implementation/2026-11-09-client-project-workspace-delivery-implementation-design.md)｜[账本](../progress/2026-11-09-client-project-workspace-delivery-ledger.md)。

```text
仓库：F:\java\cm-agent
先读取 AGENTS.md、原 R2 需求包以及本 R3 实施包六份文件。mode=default，普通工程细节自主取舍，保留相关依据。
用户已授权核对本次范围后提交并推送，完成 UNKNOWN 回执恢复、对账与隔离验收并同步六份文档；该授权仅覆盖本任务。

首先读取账本“R3 续验结果”。不要把历史未提交、未登录、未运行双库重新当成当前阻塞。业务代码已推送至 d85f785。T0/T13 完成，T6/T17 的 Windows 命令子集通过但相关完整故障矩阵仍进行中，T9 进行中。
当前已验证真实模型为 OPENAI_COMPATIBLE/glm-5.3；Agent 显示名“测试Qwen”不代表实际 Provider。现有项目的目录读取、精确 HIGH 批准、命令成功、Run 完成屏障、停止进程树、重复停止查询、重启 UNKNOWN 回报、人工对账审计与归档隔离均有证据。
UNKNOWN 保留最后输出和序号；人工确认不改变版本、状态或根隔离。不得重放已派发命令，不得用删回执、直接改库或固定目录选择器制造通过。

后续仅处理账本列明的真实原生选择器、文件/ACL 竞争、设备撤销/脱离进程树、注销/断电、跨主机 TLS/多实例、旧版/回滚栅栏和完整浏览器矩阵；开始前重新核对当前 Git 与运行状态。不能把本机 Server+Windows 客户端+Rocky JDBC 的命令子集说成远端 TLS 完整部署通过。

先用 CodeGraph 定位源码，新增未索引文件再定点读取；保护配置、设计、忽略规则和其他用户改动。功能默认关闭，协议 5 与 Server 本机来源独立。租户、owner、来源和命令许可只来自认证上下文与服务端持久见证；始终保留权限、精确预览/版本/摘要、审批和严格审计门禁。
Java 使用 JDK 21；Maven 串行运行，避免共享 target 冲突。Docker/Testcontainers/JDBC/Flyway 只在 ssh rocky 的 maven:3.9.9-eclipse-temurin-21 容器验证，先确认 Docker、磁盘、JDK、Git 提交一致，不清理无关容器/卷。迁移已至 V29，不修改发布历史。
修改安全/失败分支同步中文注释和回归，保留 errorCode/errorId 及脱敏诊断。每次新增代码后只运行适用验证并记录确切覆盖范围；已通过且未受影响的测试不机械重跑。同步原六份路径、稳定 T0–T18 编号和当前范围，不新建重复主题。提交前核对暂存区、差异、链接和敏感信息，只纳入本次范围。
```
