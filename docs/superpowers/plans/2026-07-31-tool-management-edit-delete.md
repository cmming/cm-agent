# 工具编辑、删除与 Agent 解除关联 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为工具治理提供安全的编辑、删除和 Agent 解除关联接口，并在控制台完成对应交互。

**Architecture:** `ManagementCommandService` 是写操作的事务和审计边界，Controller 只处理 HTTP、认证与权限。Repository 新增最小更新/删除/解除关联契约并同时落地内存与 JDBC；控制台保留单页状态模型，新增表单编辑状态和资源卡片动作。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring MVC/Security、JdbcClient、JUnit Jupiter/Mockito/MockMvc、Node 内置测试运行器。

## Global Constraints

- Maven 必须运行于 JDK 21；新增项目文字与注释使用中文。
- 全部读写依据 JWT 主体 tenant；不得接受客户端 tenant。
- 编辑、授权和解除关联使用 `tool:grant`；删除使用新增 `tool:delete`；拒绝必须审计。
- 不引入 JPA、MyBatis、数据库迁移或数据库特有 JSON 查询。
- HTTP 工具只保存 Secret 引用；响应、审计和页面不得显示 Secret 原文。
- 每项行为均先写失败测试、确认红灯，再写最小实现并确认绿灯。
- JDBC/Testcontainers 验证仅在 `ssh rocky` 的指定容器环境执行。

---

### Task 1: 扩展 Repository 的更新、清理和解除关联能力

**Files:**
- Modify: `cm-agent-core/src/main/java/com/cmagent/core/repository/ToolDefinitionRepository.java`
- Modify: `cm-agent-core/src/main/java/com/cmagent/core/repository/ToolGrantRepository.java`
- Modify: `cm-agent-core/src/main/java/com/cmagent/core/repository/AgentDefinitionRepository.java`
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java`
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java`
- Modify: `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcToolDefinitionRepository.java`
- Modify: `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcToolGrantRepository.java`
- Modify: `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcAgentDefinitionRepository.java`
- Test: `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcToolDefinitionRepositoryTest.java`
- Test: `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcToolGrantRepositoryTest.java`
- Test: `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcAgentDefinitionRepositoryTest.java`
- Test: `cm-agent-server/src/test/java/com/cmagent/server/config/ServerRepositoryConfigurationTest.java`

**Interfaces:**
- Produces: `ToolDefinitionRepository.update(ToolDefinition tool)`、`ToolGrantRepository.delete(UUID tenantId, UUID agentId, UUID toolId)`、`ToolGrantRepository.deleteByTenantAndToolId(UUID tenantId, UUID toolId)`、`AgentDefinitionRepository.removeToolFromAgent(UUID tenantId, UUID agentId, UUID toolId)`。

- [ ] **Step 1: 写入失败的 Repository 测试**

```java
assertThat(toolRepository.update(updated)).isEqualTo(updated);
assertThat(toolRepository.findByTenantAndId(TENANT_ID, tool.id())).contains(updated);
grantRepository.delete(TENANT_ID, agentId, toolId);
assertThat(grantRepository.listByTenantAgentAndTool(TENANT_ID, agentId, toolId)).isEmpty();
assertThat(agentRepository.removeToolFromAgent(TENANT_ID, agentId, toolId).toolIds())
        .containsExactly(otherToolId);
```

覆盖更新字段及 tenant 隔离、单授权删除、按工具授权清理、移除 `toolIds` 后保留其他工具，并为 memory Bean 覆盖同一语义。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl cm-agent-persistence -am -Dtest=JdbcToolDefinitionRepositoryTest,JdbcToolGrantRepositoryTest,JdbcAgentDefinitionRepositoryTest test`

Expected: 编译失败，提示新增 Repository 方法不存在；Testcontainers/Docker 不可用不算红灯通过。

- [ ] **Step 3: 实现最小 Repository 方法**

工具更新使用 tenant 与 ID 双条件的 `UPDATE`；授权删除分别采用 tenant/agent/tool 和 tenant/tool 条件；Agent 读取后构造移除目标 ID 的不可变记录并更新 `tool_ids_json` 与 `updated_at`。memory 实现同步更新 Map/集合。

```java
int count = jdbcClient.sql("""
        UPDATE tool_definitions SET name = :name, description = :description,
        input_schema = :inputSchema, risk_level = :riskLevel, enabled = :enabled,
        endpoint = :endpoint, updated_by = :updatedBy, updated_at = :updatedAt
        WHERE tenant_id = :tenantId AND id = :id
        """).param("tenantId", tool.tenantId().toString()).param("id", tool.id().toString()).update();
```

- [ ] **Step 4: 重跑测试并确认绿灯**

Run: `mvn -pl cm-agent-persistence -am -Dtest=JdbcToolDefinitionRepositoryTest,JdbcToolGrantRepositoryTest,JdbcAgentDefinitionRepositoryTest test`

Expected: 指定测试全部通过；若需容器，切换 Rocky Linux 环境执行并记录结果。

- [ ] **Step 5: 提交 Task 1**

```powershell
git add cm-agent-core/src/main/java/com/cmagent/core/repository cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java cm-agent-persistence/src/main/java/com/cmagent/persistence cm-agent-persistence/src/test/java/com/cmagent/persistence cm-agent-server/src/test/java/com/cmagent/server/config/ServerRepositoryConfigurationTest.java
git commit -m "feat: 支持工具关联解除持久化"
```

### Task 2: 实现命令服务事务、校验和审计

**Files:**
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/service/ManagementCommandService.java`
- Test: `cm-agent-server/src/test/java/com/cmagent/server/service/ManagementCommandServiceTest.java`
- Test: `cm-agent-server/src/test/java/com/cmagent/server/service/ManagementCommandServiceJdbcPersistenceTest.java`

**Interfaces:**
- Consumes: Task 1 的更新、授权删除、授权清理和 Agent 解除关联方法。
- Produces: `updateTool(PrincipalRef, UUID, ToolUpdateSpec)`、`deleteTool(PrincipalRef, UUID)`、`revokeTool(PrincipalRef, UUID, UUID)`。
- Defines: `record ToolUpdateSpec(String name, String description, ToolType type, ToolRiskLevel riskLevel, boolean enabled, @Nullable HttpToolCreateSpec httpToolCreateSpec, boolean mcpPublished)`；Controller 从更新请求构造该记录，命令服务据此保留不可变字段并替换可编辑配置。

- [ ] **Step 1: 写入失败的命令服务测试**

```java
assertThatThrownBy(() -> service.deleteTool(PRINCIPAL, toolId))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("请先解除关联");
verify(toolRepository, never()).delete(TENANT_ID, toolId);
AgentDefinition updated = service.revokeTool(PRINCIPAL, toolId, agentId);
assertThat(updated.toolIds()).doesNotContain(toolId);
verify(grantRepository).delete(TENANT_ID, agentId, toolId);
```

覆盖 HTTP 工具更新会替换定义、HTTP 配置与 MCP 状态；LOCAL 改名被拒绝；同 tenant 重名为 409；被引用删除为 409 且无副作用；删除清理附属数据；解除关联与三个成功路径均写审计。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl cm-agent-server -am -Dtest=ManagementCommandServiceTest,ManagementCommandServiceJdbcPersistenceTest test`

Expected: 新命令方法缺失或未发生预期事务、清理、审计交互而失败。

- [ ] **Step 3: 实现最小命令服务**

更新前读取同 tenant 工具、固定类型/ID/tenant/createdBy、限制 LOCAL 改名、排除自身检查重名并复用 HTTP 创建校验。删除前以 `agentRepository.listByTenant` 过滤 `toolIds`；无引用时在同一 `TransactionTemplate` 删除 HTTP、MCP、授权和定义并写 `TOOL_DELETE`。解除关联在同一事务确认工具/Agent、删除授权、移除 `toolIds` 并写 `TOOL_GRANT_REVOKE`。

```java
boolean referenced = agentRepository.listByTenant(principal.tenantId()).stream()
        .anyMatch(agent -> agent.toolIds().contains(toolId));
if (referenced) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
            "工具仍被 Agent 关联，请先解除关联后再删除");
}
```

- [ ] **Step 4: 重跑测试并确认绿灯**

Run: `mvn -pl cm-agent-server -am -Dtest=ManagementCommandServiceTest,ManagementCommandServiceJdbcPersistenceTest test`

Expected: 指定服务测试全部通过。

- [ ] **Step 5: 提交 Task 2**

```powershell
git add cm-agent-server/src/main/java/com/cmagent/server/service/ManagementCommandService.java cm-agent-server/src/test/java/com/cmagent/server/service/ManagementCommandServiceTest.java cm-agent-server/src/test/java/com/cmagent/server/service/ManagementCommandServiceJdbcPersistenceTest.java
git commit -m "feat: 增加工具编辑删除与解除关联编排"
```

### Task 3: 暴露 REST 接口并加入删除权限

**Files:**
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/web/ToolController.java`
- Modify: `cm-agent-server/src/main/java/com/cmagent/server/web/AuthController.java`
- Test: `cm-agent-server/src/test/java/com/cmagent/server/web/ToolControllerTest.java`
- Test: `cm-agent-server/src/test/java/com/cmagent/server/web/AuthControllerTest.java`

**Interfaces:**
- Produces: `PUT /api/tools/{id}`、`DELETE /api/tools/{id}`、`DELETE /api/tools/{id}/grants/{agentId}`；默认管理员权限含 `tool:delete`。

- [ ] **Step 1: 写入失败的 MockMvc 与登录测试**

```java
mvc.perform(delete("/api/tools/{id}", toolId).principal(authentication("tool:delete")))
        .andExpect(status().isNoContent());
mvc.perform(delete("/api/tools/{toolId}/grants/{agentId}", toolId, agentId)
        .principal(authentication("tool:grant")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.toolIds").isEmpty());
```

覆盖 PUT 成功/403/404/409，DELETE 成功/403/409/跨 tenant，解除成功/403/404，及登录权限列表含 `tool:delete`。

- [ ] **Step 2: 运行测试并确认红灯**

Run: `mvn -pl cm-agent-server -am -Dtest=ToolControllerTest,AuthControllerTest test`

Expected: 新路由为 404/405 或默认权限断言失败。

- [ ] **Step 3: 实现 Controller 和权限清单**

抽取 POST/PUT 共用的请求解析；PUT 校验 `tool:grant` 后委托更新并返回 `ToolSummaryResponse`，DELETE 工具校验 `tool:delete` 后返回 204，DELETE grants 校验 `tool:grant` 后返回更新 Agent。`AuthController` 默认权限列表增加 `tool:delete`，同步固定长度断言。

- [ ] **Step 4: 重跑测试并确认绿灯**

Run: `mvn -pl cm-agent-server -am -Dtest=ToolControllerTest,AuthControllerTest test`

Expected: 指定 Web/认证测试通过，403 分支仍验证 `accessDenied` 审计。

- [ ] **Step 5: 提交 Task 3**

```powershell
git add cm-agent-server/src/main/java/com/cmagent/server/web/ToolController.java cm-agent-server/src/main/java/com/cmagent/server/web/AuthController.java cm-agent-server/src/test/java/com/cmagent/server/web/ToolControllerTest.java cm-agent-server/src/test/java/com/cmagent/server/web/AuthControllerTest.java
git commit -m "feat: 暴露工具编辑删除与解除关联接口"
```

### Task 4: 为控制台新增纯函数与其失败测试

**Files:**
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js`
- Test: `cm-agent-console/src/test/js/console-core.test.cjs`

**Interfaces:**
- Produces: `buildToolUpdatePayload(tool, fields)`、`buildToolUpdatePath(toolId)`、`buildToolDeletePath(toolId)`、`buildToolGrantDeletePath(toolId, agentId)` 与 `isToolDeleteConflict(error)`。

- [ ] **Step 1: 写入失败的 Node 测试**

```javascript
assert.equal(core.buildToolDeletePath("tool/id"), "/api/tools/tool%2Fid");
assert.equal(core.buildToolGrantDeletePath("tool", "agent/id"), "/api/tools/tool/grants/agent%2Fid");
assert.throws(() => core.buildToolUpdatePayload(localTool, {...fields, name: "renamed"}), /LOCAL/);
```

覆盖 HTTP 编辑保留 MCP/Secret 引用、LOCAL 改名拒绝、路径编码和 409 冲突识别；测试 Secret 仅使用 `secret/integration/token`。

- [ ] **Step 2: 运行 Node 测试确认红灯**

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: 函数未导出或断言失败。

- [ ] **Step 3: 实现最小纯函数**

复用 `buildHttpToolPayload` 校验，固定类型不可变，LOCAL 改名抛中文错误；路径函数仅拼接固定 REST 路径与 `encodeURIComponent`；冲突函数只识别明确的 409。

- [ ] **Step 4: 重跑 Node 测试确认绿灯并提交**

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: 测试全部通过。

```powershell
git add cm-agent-console/src/main/resources/META-INF/resources/assets/console-core.js cm-agent-console/src/test/js/console-core.test.cjs
git commit -m "feat: 增加工具编辑删除控制台核心函数"
```

### Task 5: 完成控制台交互、文档和最终验证

**Files:**
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/index.html`
- Modify: `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- Modify: `cm-agent-console/src/test/js/console-core.test.cjs`
- Modify: `README.md`
- Modify: `docs/release-notes.md`

**Interfaces:**
- Consumes: Task 3 REST 端点和 Task 4 的 API helper。
- Produces: 工具卡片编辑/删除、表单编辑模式、Agent 详情解除关联、中文使用说明。

- [ ] **Step 1: 写入失败的页面与文档契约测试**

```javascript
assert.match(html, /id="cancelToolEditBtn"/);
assert.match(app, /core\.buildToolUpdatePath/);
assert.match(app, /请先到 Agent 详情解除关联/);
assert.doesNotMatch(readme, /不提供编辑、删除/);
assert.match(releaseNotes, /工具编辑、删除与 Agent 解除关联/);
```

- [ ] **Step 2: 运行 Node 测试确认红灯**

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: 缺少编辑控件、API helper、冲突提示或文档内容导致失败。

- [ ] **Step 3: 实现页面和文档**

工具卡片增加编辑/删除；编辑设置 `state.editingToolId`、回填表单、锁类型并禁用 LOCAL 名称，提交时按状态 PUT/POST，成功后清状态并 `loadTools()`。删除须确认，409 只提示先到 Agent 详情解除。Agent 详情已关联工具增加确认后的 DELETE grants，并刷新 `selectAgent(agentId)` 与 `loadTools()`，继续使用既有 session/revision 防止旧响应回写。README 和发布说明以中文写明 `tool:grant` 编辑、`tool:delete` 删除、解除后才能删除。

- [ ] **Step 4: 重跑 Node 测试确认绿灯**

Run: `node --test cm-agent-console/src/test/js/console-core.test.cjs`

Expected: Node 测试全部通过。

- [ ] **Step 5: 运行最终验证**

Run: `mvn -pl cm-agent-server -am test`

Run: `mvn -q "-DskipTests" package`

Run: `ssh rocky` 后确认 Docker、Maven/JDK 21、本地与远程提交一致，再运行 `mvn -pl cm-agent-persistence -am test`。

Expected: 前两项和远程持久化测试均为 0 退出码；远程环境不可用时记录确切原因，不得声称已通过。

- [ ] **Step 6: 检查并提交 Task 5**

```powershell
git diff --check
git status --short
git add cm-agent-console/src/main/resources/META-INF/resources/index.html cm-agent-console/src/main/resources/META-INF/resources/assets/app.js cm-agent-console/src/test/js/console-core.test.cjs README.md docs/release-notes.md
git commit -m "feat: 支持控制台工具编辑删除与解除关联"
```

## 最终验收清单

- [ ] 编辑不改变工具 ID、tenant、类型或创建人；LOCAL 工具不能改名。
- [ ] 有任意同租户 Agent 引用时删除返回 409 且不产生副作用。
- [ ] 解除关联同时清理授权和 Agent `toolIds`，随后可删除工具。
- [ ] 新增写操作经过权限、tenant 和严格审计；控制台不显示 Secret。
- [ ] Node、服务端、打包和 Rocky 持久化测试具有本次运行证据。
