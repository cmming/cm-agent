# 技能版本发布、依赖预检与指定版本试运行实现说明

## 1. 当前状态

- 日期与主题：2026-09-22，`skill-release-validation`。
- 阶段：设计规格已确认，实施计划已编写，业务实现尚未开始。
- 当前仓库行为仍是上传版本后直接切换当前版本；本文不能作为功能已经可用的证据。
- 设计依据：[设计规格](../specs/2026-09-22-skill-release-validation-design.md)。
- 执行依据：[实施计划](../plans/2026-09-22-skill-release-validation.md)。
- 进度记录：[进度账本](../progress/2026-09-22-skill-release-validation-ledger.md)。

## 2. 规划后的实现边界

实施将保持现有模块职责：

- Core 定义候选/发布、依赖、预检、试运行、Run 类型、绑定策略和 format 2 快照契约。
- Persistence 与 memory store 提供同一 Repository 合同；V13 分别使用 PostgreSQL/MySQL 方言迁移。
- Server 编排候选、映射、预检、发布、回滚和 TEST Run，并复用现有权限、严格审计、诊断和工具治理。
- Adapter 只接收服务端固定的版本和依赖解析结果，不读取数据库、不自动增加工具、不开放脚本执行。
- Console 延续现有中文列表—详情—操作工作区，把发布流程放在同一详情上下文中。

## 3. 计划调用链

### 3.1 发布

```text
SkillReleaseController
  -> SkillReleaseService
      -> 锁定 SkillDefinition 并核对候选/发布指针
      -> SkillPreflightService 即时检查当前映射与全部跟随 Agent
      -> SkillTrialRepository 核对 PASSED、版本和映射修订
      -> SkillReleaseRepository 追加发布记录
      -> SkillDefinitionRepository 双指针 CAS
      -> AuditAppender 严格审计
```

### 3.2 试运行

```text
SkillTrialController
  -> SkillTrialService 创建 TEST Run 和试运行记录
  -> SkillPreflightService 检查目标 Agent
  -> RunExecutionService.runTrialPrepared
  -> SkillRuntimeService 临时注入指定版本并保存 format 2 快照
  -> AgentScope Runtime / ToolInvocationGateway / ToolApprovalService
  -> SkillLoadRecordRepository 判断目标技能是否实际读取
  -> SkillTrialRepository 收口 PASSED、NOT_TRIGGERED 或 FAILED
```

### 3.3 正式运行取版

```text
AgentSkillBinding
  -> FOLLOW_PUBLISHED 解析 SkillDefinition.publishedVersionId
  -> PINNED 解析 binding.pinnedVersionId
  -> SkillRuntimeService 固定版本、绑定/试运行授权来源和依赖工具 ID
  -> AgentScopeSkillSession 仅向模型说明逻辑依赖和已授权工具名称
```

## 4. 与原方案的当前差异

尚无实现差异。执行阶段若发现已确认设计无法安全落地，先更新规格和计划并说明原因，不能在代码中静默改变发布门禁、授权或迁移语义。

## 5. 验证状态

- 已完成：设计和计划的占位符、结构、任务覆盖与路径自检。
- 未执行：Java、Node、MockMvc、AgentScope、PostgreSQL/MySQL 和浏览器验证，因为业务实现尚未开始。
- 用户原有配置及未跟踪目录不在本规划文档改动范围内。

## 6. 实施后必须补充的证据

执行 T1～T11 时持续更新本文，记录最终类、接口、数据表、API、控制台流程、与计划不同之处及其理由。完成时不得保留“计划调用链”冒充实际调用链；应以最终源码、测试结果和浏览器/双库证据重写相关章节。
