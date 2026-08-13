# RunExecutionService.run 业务流程注释实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `RunExecutionService.run` 添加能够解释完整业务流程的中文 JavaDoc 和阶段性行内注释，同时保持所有可执行行为不变。

**Architecture:** 本次不调整类结构和调用链，仅在编排入口的方法契约与业务阶段边界补充说明。注释覆盖租户内资源校验、工具授权、运行记录启动、Runtime 调用、三类异常收口、终态持久化和响应脱敏。

**Tech Stack:** Java 21、Spring Boot 3.5.0、Maven 多模块工程、JUnit Jupiter 5.12.2。

## Global Constraints

- 新增文字必须使用中文，代码标识符和领域术语沿用项目既有形式。
- 不修改业务逻辑、异常映射、日志内容、事务边界、接口或依赖。
- 不在注释或文档中写入密钥、内部地址或其他敏感配置。
- 不触碰工作区中已有的配置文件修改。
- 本次没有可执行行为变化，因此不新增测试，只执行差异检查和服务端模块验证。

---

### Task 1: 补充 run 方法业务流程注释

**Files:**
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java:88`

**Interfaces:**
- Consumes: `AgentRunResult run(PrincipalRef principal, UUID agentId, String input)` 现有公开方法及其全部依赖。
- Produces: 签名和行为不变、业务流程说明更完整的同一公开方法。

- [x] **Step 1: 扩展方法级 JavaDoc**

在现有摘要后增加流程说明，明确入口权限已由 Controller 校验，并概括成功路径和失败路径：

```java
/**
 * 执行 Agent 的单轮运行，并持久化运行状态和工具调用结果。
 *
 * <p>调用方已完成运行权限校验；本方法负责在租户边界内校验运行资源、筛选授权工具，
 * 创建运行记录后调用 Runtime。执行成功时持久化终态和工具调用并返回脱敏结果；执行失败时
 * 根据异常类型完成失败状态与审计收口，并保留审计或数据库异常的原始语义。</p>
 */
```

- [x] **Step 2: 在主流程阶段边界添加行内注释**

在对应代码块前添加中文注释，说明租户资源校验、工具授权、运行记录启动、Runtime 调用、异常收口、终态持久化和响应脱敏，不改变任何可执行语句。

- [x] **Step 3: 检查 Java 文件差异**

Run: `git diff -- cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`

Expected: 只有 JavaDoc 和 `//` 注释新增，无可执行语句变化。

### Task 2: 补齐实现说明和进度账本

**Files:**
- Create: `docs/superpowers/implementation/2026-08-13-run-execution-service-run-comments-implementation-design.md`
- Create: `docs/superpowers/progress/2026-08-13-run-execution-service-run-comments-ledger.md`
- Modify: `docs/superpowers/plans/2026-08-13-run-execution-service-run-comments.md`

**Interfaces:**
- Consumes: 已确认的设计说明和 Task 1 的实际差异。
- Produces: 与设计、计划和实际变更一致的实现记录与验证账本。

- [x] **Step 1: 记录最终实现**

实现说明需列出注释所在类和方法、业务阶段、异常路径解释，以及“与原方案无差异”。

- [x] **Step 2: 记录实际进度**

进度账本需逐项记录完成状态、实际验证命令和结果、未触碰的现有配置改动，并在未创建实现提交时写明“未提交”。

- [x] **Step 3: 更新本计划复选框**

完成每一步后，将对应的 `- [ ]` 更新为 `- [x]`，确保计划状态与实际执行一致。

### Task 3: 验证注释变更

**Files:**
- Verify: `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`
- Verify: `docs/superpowers/specs/2026-08-13-run-execution-service-run-comments-design.md`
- Verify: `docs/superpowers/plans/2026-08-13-run-execution-service-run-comments.md`
- Verify: `docs/superpowers/implementation/2026-08-13-run-execution-service-run-comments-implementation-design.md`
- Verify: `docs/superpowers/progress/2026-08-13-run-execution-service-run-comments-ledger.md`

**Interfaces:**
- Consumes: Task 1 和 Task 2 的全部产物。
- Produces: 可交付的注释与完整过程记录。

- [x] **Step 1: 确认构建环境**

Run: `java -version` and `mvn -v`

Expected: Java 21，Maven 3.9+ 且 Maven 运行在 JDK 21。

- [x] **Step 2: 执行格式和空白检查**

Run: `git diff --check`

Expected: 退出码为 0，无尾随空格或冲突标记。

- [x] **Step 3: 执行服务端模块测试**

Run: `mvn -pl cm-agent-server -am test`

Expected: 所有相关模块测试通过。若环境导致无法执行，记录确切原因，不声称测试通过。

Actual: Java 21 环境下 Core 的 62 个测试通过；持久化 Testcontainers 测试因本机无可用 Docker 环境而停止。经用户确认，本次不执行 Rocky Linux 远程容器测试，改为执行全部相关模块的跳过测试编译。

- [x] **Step 4: 复核最终范围**

Run: `git status --short` and `git diff --stat`

Expected: 本任务只新增 `RunExecutionService.java` 注释及同主题过程文档；用户原有配置文件改动仍保留在主工作区且未纳入隔离分支。
