# 技能版本发布、依赖预检与指定版本试运行实现说明

## 1. 交付状态

- 日期与主题：2026-09-22，`skill-release-validation`。
- 实现完成：候选/发布双指针、版本历史、依赖映射与预检、指定版本 TEST Run、运行级审批、固定依赖快照、AgentScope 受限提示和 v2 连续工作区均已落地。
- 设计依据：[设计规格](../specs/2026-09-22-skill-release-validation-design.md)；执行依据：[实施计划](../plans/2026-09-22-skill-release-validation.md)；验证记录见[进度账本](../progress/2026-09-22-skill-release-validation-ledger.md)。

## 2. 实际调用链

### 2.1 候选、发布与绑定

`SkillManagementService` 上传不可变 `SkillVersion` 后仅更新候选指针。`SkillReleaseService` 在工作单元内锁定 `SkillDefinition`，以候选/发布指针进行 CAS，重新执行预检并核对同一映射修订的合格 `SkillTrial`，然后追加 `SkillRelease` 和严格审计。回滚以新的 `ROLLBACK` 事实重新发布历史版本，不修改历史记录。

`AgentSkillController` 保留空请求体的旧绑定契约；带 `BindingStrategyRequest` 时进入 `SkillReleaseService.bindOrUpdate`。`SkillQueryService` 在绑定响应中返回 `mode`、`pinnedVersionId` 和 `revision`，使控制台可在冲突时按服务端权威版本刷新。

### 2.2 依赖与 TEST Run

`SkillDependencyController` 提供逻辑 key 到当前租户工具的条件映射及结构/Agent 预检。映射服务不信任客户端租户，保存后增加技能级映射修订；它不会创建 ToolGrant 或改变工具风险等级。

`SkillTrialController` 通过 `SkillTrialService` 创建 `RunKind.TEST` 和 `SkillTrial`。`SkillRuntimeService.prepare` 以 `SkillRuntimeSelection` 在本次 Run 临时替换或注入目标技能，写入带 `TRIAL` 来源的 format 2 快照；`RunExecutionService` 仍复用正式执行、脱敏、网关和审计链路。终态以 `SkillLoadRecordRepository` 的目标 skill/version 成功读取记录判定 `PASSED`、`NOT_TRIGGERED`、`FAILED` 或 `WAITING_APPROVAL`。运行级审批由 `ToolApprovalService` 使用 tenant、Agent、Run 和审批 ID 恢复同一 TEST Run，不创建会话消息。

### 2.3 正式运行与适配器

`SkillRuntimeService` 对 `FOLLOW_PUBLISHED` 解析正式指针，对 `PINNED` 解析固定历史版本，并将依赖逻辑 key、工具 ID、必需性写入快照。恢复只信任快照，同时复核工具启用、授权和撤销状态。

`AgentScopeSkillSession` 只把已固定的逻辑依赖、已授权工具名称和必需性加入目录提示。实际工具仍由现有 `ToolInvocationGateway` 执行，适配器不读取映射仓储、不自动增加工具，也不暴露 endpoint、请求头或密钥。

## 3. 控制台实现

`console/v2/assets/skills.js` 保留列表—详情—操作结构，在同一详情中展示候选/正式指针、依赖映射修复、候选结构预检、指定版本试运行、发布门禁、版本历史和回滚。试运行的发布按钮还本地核对候选版本与映射修订；服务端仍是唯一门禁。`assets/app.js` 的 Agent 详情提供跟随发布/固定历史版本选择，提交携带 `expectedRevision`。

界面遵循既有受控工作台：冷白工作区、克制蓝色操作、桌面详情栈和窄屏单列，不改变权限、审计、导入或启停行为。

## 4. 与原方案的差异

- 计划中的完整浏览器端到端流未作为通过项：隔离测试服务的单模块启动解析到本地仓库旧 core 产物，无法加载新接口；未触碰用户占用 8080 的 IntelliJ 实例。静态资源、Node 与 Java 回归均通过。
- 控制台试运行目前以同步试运行响应呈现终态；运行级审批 API 已实现，但连续 SSE 输出和刷新后审批卡片仍应作为后续增强，不得宣称已经完成浏览器闭环。

## 5. 验证状态

- Java 21 reactor 打包及 core、adapter、console 测试通过。
- Node 的 `skills.test.cjs` 与 `console-core.test.cjs` 通过，共 75 项。
- `ConsoleResourceTest`、`AgentSkillControllerTest`、`SkillControllerTest` 通过。
- Rocky 验证未执行：已在隔离的 Maven 3.9.9 / Java 21.0.7 容器中固定最终提交，但 Maven Central DNS 解析失败，无法下载父 POM；浏览器真实工作流的限制与后续前置条件均见进度账本。
