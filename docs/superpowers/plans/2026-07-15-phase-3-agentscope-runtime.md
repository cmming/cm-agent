# 阶段 3：真实 AgentScope Runtime 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans，按任务逐项执行。步骤使用 checkbox 跟踪。

**Goal:** 在 `cm-agent-agentscope-adapter` 中接入 AgentScope Java RC3 的可配置真实同步运行时，并保持现有权限、tenant、审计和两段式持久化契约。

**Architecture:** 扩展 core runtime 请求以携带完整 `AgentDefinition`，由 adapter 内部工厂构造 AgentScope 模型、Agent 和授权工具。Starter 通过条件配置装配真实 runtime；server 继续负责治理和持久化，adapter 不访问 Repository。

**Tech Stack:** Java 21、Maven 3.9+、Spring Boot 3.5.0、AgentScope Java 2.0.0-RC3、JUnit 5、AssertJ、Spring Boot Test。

## Global Constraints

- 所有新增代码注释、JavaDoc、测试说明、配置文档和提交说明使用中文。
- AgentScope 专属类型只允许出现在 `cm-agent-agentscope-adapter`。
- 不引入 JPA、MyBatis、新 Flyway 迁移或模型配置表。
- 每个 runtime 请求和工具执行均保持 tenant/principal 上下文；模型不能覆盖它们。
- 生产 profile 禁止 fake runtime 回退；缺少真实配置时启动失败。
- API Key 只能来自外部配置，不写入源码、日志、审计、错误响应或测试断言。
- 每个实现任务遵循 RED→确认失败→GREEN→确认通过→重构，并单独提交。

---

### Task 1：锁定 AgentScope RC3 API 与 core 运行契约

**Files:**
- Modify: `cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java`
- Modify: `cm-agent-core/src/main/java/com/cmagent/core/runtime/AgentRuntime.java`
- Modify: `cm-agent-core/src/main/java/com/cmagent/core/runtime/FakeAgentRuntime.java`
- Test: `cm-agent-core/src/test/java/com/cmagent/core/runtime/FakeAgentRuntimeTest.java`
- Test: `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeRuntimeAdapterTest.java`

**Interfaces:** `AgentRuntime.run(AgentRunRequest)` 保持方法名；`AgentRunRequest` 新增 `AgentDefinition agent` 并保留 `tenantId`、`agentId`、`principal`、`input`、`tools`，构造器校验 `agent.id/tenantId` 与请求一致。先用 Maven 解析依赖，再用 `jar tf`/`javap` 记录实际 RC3 Agent、模型、消息和工具 API。

- [ ] **Step 1: 写失败测试**：在 `FakeAgentRuntimeTest` 增加断言请求可携带 Agent 定义且结果仍保持原 fake 输出；在 adapter 测试中断言 `toRunSpec` 映射 Agent system prompt、modelName、temperature、maxIterations。
- [ ] **Step 2: 验证失败**：运行 `mvn -q -pl cm-agent-core,cm-agent-agentscope-adapter -am -Dtest=FakeAgentRuntimeTest,AgentScopeRuntimeAdapterTest test`，预期因缺少 `AgentRunRequest.agent` 映射方法或断言失败。
- [ ] **Step 3: 最小实现**：扩展 record 及构造器，更新 server 当前唯一构造点传入 Agent；Fake runtime 忽略新字段但保持原语义；记录 RC3 实际 API 到 progress ledger。
- [ ] **Step 4: 验证通过**：重复上述 Maven 命令，预期相关测试通过。
- [ ] **Step 5: 提交**：`git add cm-agent-core cm-agent-agentscope-adapter cm-agent-server && git commit -m "feat: 扩展真实运行时请求契约"`。

### Task 2：实现 adapter 内部模型、工具和结果映射

**Files:**
- Modify: `cm-agent-agentscope-adapter/pom.xml`
- Modify: `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeAdapter.java`
- Modify: `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRunSpec.java`
- Create: `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeModelFactory.java`
- Create: `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeToolBridge.java`
- Create: `cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeException.java`
- Test: `cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeRuntimeAdapterTest.java`

**Interfaces:** `AgentScopeModelFactory.create(AgentDefinition)` 返回 adapter 内部模型句柄；`AgentScopeToolBridge.toTools(AgentRunRequest)` 只接收已授权工具；`AgentScopeRuntimeAdapter.run` 返回 `AgentRunResult`。具体 RC3 类型以 Task 1 的依赖检查为准，统一封装在三个类内。

- [ ] **Step 1: 写失败测试**：增加成功文本映射、模型配置映射、工具 tenant 不一致拒绝、模型异常转受控失败、超时转失败的测试；使用可控 fake model/tool，不调用真实供应商。
- [ ] **Step 2: 验证失败**：运行 `mvn -q -pl cm-agent-agentscope-adapter -am -Dtest=AgentScopeRuntimeAdapterTest test`，预期因工厂、桥接器和真实执行路径尚不存在而失败。
- [ ] **Step 3: 最小实现**：依据 RC3 API 创建 DashScope/兼容 OpenAI 模型；将 system prompt、模型名、temperature、maxIterations 和用户输入映射到 AgentScope；工具桥接器固定 tenant/principal，并将事件映射到 `ToolCallRecord`；catch 超时、取消、供应商异常并脱敏消息。
- [ ] **Step 4: 验证通过**：重复 adapter 测试，预期全部通过且不输出凭据。
- [ ] **Step 5: 重构并提交**：仅整理 adapter 内重复映射，保持测试绿色；执行 `git add cm-agent-agentscope-adapter && git commit -m "feat: 接入AgentScope真实运行桥接"`。

### Task 3：加入 Starter 配置与条件装配

**Files:**
- Modify: `cm-agent-spring-boot-starter/src/main/java/com/cmagent/starter/CmAgentProperties.java`
- Modify: `cm-agent-spring-boot-starter/src/main/java/com/cmagent/starter/CmAgentAutoConfiguration.java`
- Modify: `cm-agent-spring-boot-starter/pom.xml`
- Test: `cm-agent-spring-boot-starter/src/test/java/com/cmagent/starter/CmAgentAutoConfigurationTest.java`
- Modify: `cm-agent-server/src/main/resources/application.yml`

**Interfaces:** `CmAgentProperties.AgentScopeProperties` 提供 `enabled/provider/apiKey/baseUrl/defaultModel/timeout`；真实 Bean 仅在 fake 关闭、AgentScope 启用且 adapter 在 classpath 时创建；缺失 provider/API Key/defaultModel 时抛出启动配置异常。

- [ ] **Step 1: 写失败测试**：增加 real runtime 条件 Bean、fake/real 互斥、缺少 API Key 启动失败和默认配置绑定测试。
- [ ] **Step 2: 验证失败**：运行 `mvn -q -pl cm-agent-spring-boot-starter -am -Dtest=CmAgentAutoConfigurationTest test`，预期新条件断言失败。
- [ ] **Step 3: 最小实现**：增加中文配置属性、条件注解和真实 adapter Bean；不修改用户已有 YAML 改动，根配置只补安全的占位符绑定。
- [ ] **Step 4: 验证通过**：重复 Starter 测试，预期通过。
- [ ] **Step 5: 提交**：`git add cm-agent-spring-boot-starter cm-agent-server/src/main/resources/application.yml && git commit -m "feat: 配置AgentScope真实运行时"`。

### Task 4：补 server 治理回归与中文文档

**Files:**
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/runtime/RunExecutionService.java`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerTest.java`
- Modify: `cm-agent-server/src/test/java/com/cmagent/server/runtime/RunPersistenceServiceTest.java`
- Modify: `docs/configuration.md`
- Modify: `docs/deployment.md`
- Modify: `docs/roadmap.md`
- Modify: `docs/release-notes.md`
- Create: `docs/superpowers/progress/2026-07-15-phase-3-agentscope-runtime-ledger.md`

- [ ] **Step 1: 写失败测试**：增加真实 runtime 返回成功/失败时 Run 状态、tool call 和审计断言；增加跨 tenant agent/tool 不可运行和拒绝审计断言。
- [ ] **Step 2: 验证失败**：运行 `mvn -q -pl cm-agent-server -am -Dtest=RunControllerTest,RunPersistenceServiceTest test`，预期新增断言先失败。
- [ ] **Step 3: 最小实现**：只调整 runtime 请求构造和异常边界，确保 Repository 仍按 tenant 查询、权限拒绝仍审计、持久化异常不被吞掉；更新配置/部署/路线图/发布说明和 ledger，明确无 Flyway 变更。
- [ ] **Step 4: 验证通过**：重复 server 测试并检查日志断言无敏感信息。
- [ ] **Step 5: 提交**：`git add cm-agent-server docs && git commit -m "test: 收口真实运行时治理回归"`。

### Task 5：完整验证、远程环境核对与 Draft PR

**Files:**
- Verify: 全部变更与 Git diff；不新增源码文件。

- [ ] **Step 1：环境检查**：运行 `java -version`、`mvn -v`，确认 Maven 使用 JDK 21。
- [ ] **Step 2：受影响模块测试**：运行 `mvn -q -pl cm-agent-server -am test` 与 `mvn -q -pl cm-agent-agentscope-adapter -am test`，记录 exit code、测试数和失败数。
- [ ] **Step 3：全量测试**：运行 `mvn -q test`；若失败，按失败测试先补回归测试再修复并重跑。
- [ ] **Step 4：检查 diff 和安全边界**：运行 `git diff --check`、`git status --short`，确认用户原有两个 YAML 修改未被覆盖且无 target/凭据。
- [ ] **Step 5：提交并推送**：创建中文提交说明，`git push -u origin codex/phase-3-agentscope-runtime`。
- [ ] **Step 6：创建 Draft PR**：中文描述包含变更摘要、测试证据、无数据库迁移说明、凭据和兼容性风险；使用 GitHub connector/`gh` 创建 Draft PR，不合并。

## 计划自检

- 已覆盖设计中的 runtime 接入、供应商配置、工具授权、tenant/principal 传递、异常、审计、测试、文档、验证和回滚。
- 未新增数据库 schema，因此不触发 Testcontainers/Flyway 变更验证；全量测试仍必须执行。
- 所有类型名、方法名和配置键在任务间保持一致；RC3 的具体 API 只在 Task 1 依赖解析后填入 adapter 内部实现。
