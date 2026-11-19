# 桌面项目聊天实施账本

日期 2026-11-18 · 主题 `desktop-project-chat-delivery` · 修订 `R0` · `mode=default`

## 范围

用户在 2026-11-18 明确要求实施 [R2](../specs/2026-11-18-desktop-project-chat-design.md)，包括前后端、选文件夹、项目会话、SSE、三档权限及 Windows x64 试装验收。现有 JDBC 工作方式继续使用。Git 基线 `c8e1ec3`，存在与任务无关的本地配置改动。提交状态与最终构建摘要由本账本在提交后更新。

| 编号 | 状态 | 证据或继续条件 |
| --- | --- | --- |
| T1 | 完成 | 聊天页项目入口和 SSE，240 项 Node 测试与模拟接口 Edge 流程通过。 |
| T2 | 完成 | 目录重复选择、幂等建会话、预授权和 SSE 拒绝通过 Java 回归。现有 JDBC 表结构继续使用。 |
| T3 | 完成 | 三档权限、固定命令参数、ReAct 与 Harness 的模型发送审批通过定向回归。 |
| T4 | 完成 | Windows x64 0.1.2 MSI 生成、SHA-256 校验及普通用户升级安装成功。 |
| T5 | 部分完成 | 安装版健康检查、回环监听、模型片段三档、完全访问文件/命令副作用及重启历史恢复通过；两种询问模式下真实文件/命令的批准与拒绝，以及真实命令停止仍待补测。 |

## 2026-11-19 验证证据

- `node --test` 执行控制台 240 项测试，全通过。Edge 模拟接口验证项目切换、取消与登记、首条 SSE、审批预览和流式恢复。
- Java 21.0.11、Maven 3.9.4 执行 `mvn -pl cm-agent-server -am -Dtest=<定向用例> -Dsurefire.failIfNoSpecifiedTests=false install`，91 项中 90 项通过、1 项跳过、0 失败。覆盖项目接口、原生文件夹选择器辅助校验、Windows 命令执行、模型披露和启动器。`HarnessProtectedToolPayloadTest` 11 项覆盖审批前未发送、审批恢复、模型变化重审和脱敏。使用 `mvn -pl cm-agent-server -DskipTests package spring-boot:repackage` 生成可执行 JAR。
- Rocky Docker Server 23.0.6 使用 `maven:3.9.9-eclipse-temurin-21`。最新验证快照两侧 SHA 均为 `997a302800ee57ed3e5bca8de990f7f9d8572ac4`，`MigrationTest`、`JdbcLocalProjectRepositoryTest`、`JdbcLocalProjectOperationRecoveryTest` 共 23 项通过，0 跳过。独立 PostgreSQL 16.14 试装库 Flyway V31，31 条成功记录、0 失败。
- 原 0.1.0 试装包安装成功。恢复网络并重试时发现 Boot 拒绝共享端口上的独立管理地址。修正启动器，重新执行 `WindowsPilotLauncherTest` 2 项通过。`scripts/package-windows-pilot.ps1` 使用 JDK 21、WiX 3.14.1 生成最新的 `CM Agent Pilot-0.1.1.msi`，133225867 字节，SHA-256 为 `0ad07d9491cc98c85b8eab98a12dd5ac51497adce147a9a89b9ae7e770f22291`。`msiexec /i` 退出码 0，安装到当前 Windows 用户范围，安装后的 JAR 与交付镜像一致。
- 安装版以随包 Java 21 连接试装 JDBC，`/actuator/health` 返回 `UP`，仅在 `127.0.0.1` 监听。实际普通会话返回 `started`、`message-started`、`delta`、`completed`，Run 为 `SUCCEEDED`。
- Rocky 桥接间歇断连时，试装候选报告 `PILOT_JDBC_UNAVAILABLE`，启动器可显示与安全日志匹配的诊断编号。用户恢复网络后重新建立专用 SSH 隧道和独立试装库，继续后续验证。

## 0.1.2 安装版验收

- 当前工作区以 JDK 21.0.11、Maven 3.9.4 执行控制台 240 项 Node 测试，240 项通过、0 失败；项目会话、审批恢复、模型片段预览和三档模式的定向 Java 测试共 73 项，72 项通过、1 项按既有平台条件跳过、0 失败。可执行 JAR 的 MANIFEST 由 Spring Boot `JarLauncher` 启动，且包含 `WindowsPilotLauncher`。
- 预提交候选使用 JDK 21 和 WiX 3.14.1 生成过 `CM Agent Pilot-0.1.2.msi`，Windows Installer 以退出码 `0` 完成升级，卸载注册版本为 `0.1.2`，安装目录为 `C:\Users\chmi\AppData\Local\CM Agent Pilot\`。该候选不绑定最终提交，不能作为推送提交的交付摘要；推送后须在干净源码中重新构建。
- 安装版使用演示项目 `演示项目` 完成本轮权限矩阵：`ASK_ON_RISK` 先展示目标模型与真实项目片段预览，批准前 Provider 的项目正文请求计数不变，批准后收到正文并继续 SSE 至成功；`ASK_ON_EFFECT` 在拒绝文件正文外发后 Provider 计数仍不变，Run 结束为 `DENIED`；`FULL_ACCESS` 完成合规读取，并实际创建项目文件及运行已登记白名单命令。
- 当前尚未在两种询问模式下分别验证真实文件变更和命令的批准、拒绝，也未在安装版验证运行中命令停止；单元与组件回归不能替代这两项真实副作用验收。
- 结束安装版进程后以同一外部 JDBC、同一项目和同一会话重新启动，`/actuator/health` 为 `UP`，仅监听 `127.0.0.1:18180`。重启后读取到既有的“项目文件读取验收通过”消息，项目绑定与对话历史恢复成功。验收证据保存在 `target/desktop-r2-evidence/pilot-acceptance-0.1.2-final.log`、`pilot-restart-0.1.2.log`、`package-0.1.2.log` 和 `maven-tests-0.1.2.log`。
- 试装配置和项目状态已放入当前用户默认目录 `C:\Users\chmi\AppData\Local\CM Agent Pilot Data`，状态目录与凭据配置的 DACL 只保留当前用户、SYSTEM 和 Administrators。清除临时环境变量后从安装后的默认入口重新启动成功，聊天页返回 HTTP 200，证据保存在 `pilot-restart-default-0.1.2.log`。
- 本轮未改变现有 JDBC/Flyway 架构；免数据库配置、Windows 以外桌面平台、签名分发和生产 TLS 仍不在 0.1.2 试装范围。

[清单](../checklists/2026-11-18-desktop-project-chat-delivery-checklist.md) · [设计](../specs/2026-11-18-desktop-project-chat-delivery-design.md) · [计划](../plans/2026-11-18-desktop-project-chat-delivery.md) · [提示词](../prompts/2026-11-18-desktop-project-chat-delivery-prompt.md) · [实现说明](../implementation/2026-11-18-desktop-project-chat-delivery-implementation-design.md)
