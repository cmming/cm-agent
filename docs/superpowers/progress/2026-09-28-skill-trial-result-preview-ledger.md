# Skill 试运行结果预览进度账本

设计：[2026-09-28-skill-trial-result-preview-design.md](../specs/2026-09-28-skill-trial-result-preview-design.md)；计划：[2026-09-28-skill-trial-result-preview.md](../plans/2026-09-28-skill-trial-result-preview.md)；实现：[2026-09-28-skill-trial-result-preview-implementation-design.md](../implementation/2026-09-28-skill-trial-result-preview-implementation-design.md)。

## 状态

- [x] 服务端为试运行响应提供受控结果预览。
- [x] 详情读取沿用所有权检查并增加 `agent:read` 权限门禁。
- [x] 技能管理页展示状态、输出/错误和工具摘要。
- [x] Java 定向测试：`SkillTrialControllerTest`、`SkillTrialServiceTest` 共 8 项通过。
- [x] 控制台构建测试：`ConsoleResourceTest` 共 14 项通过。
- [x] Node 检查：`node --check` 通过，`skills.test.cjs` 共 5 项通过。
- [x] Playwright 浏览器流程：创建启用测试 Agent、导入 `browser-validation` fixture、运行候选技能；预览显示 `NOT_TRIGGERED` 门禁状态、成功 Run、Fake Runtime 输出及 0 条工具调用。
- [x] 390×844 窄屏检查：`documentElement.clientWidth=375`、`scrollWidth=375`，无横向溢出。
- [x] 发布说明已更新；未涉及数据库迁移。
- [ ] 提交：未提交。

## 验证命令与结果

- `mvn -pl cm-agent-server -am -Dtest=SkillTrialControllerTest,SkillTrialServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：BUILD SUCCESS，8 项通过。
- `mvn -pl cm-agent-console -am install`：BUILD SUCCESS，控制台资源测试 14 项通过。
- `node --check cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/skills.js`：通过。
- `node --test cm-agent-console/src/test/js/skills.test.cjs`：5 项通过。
- 浏览器试运行：Fake Runtime 返回 `fake-runtime: 请根据技能给出一次试运行预览`；候选技能未被实际读取，故发布门禁保持 `NOT_TRIGGERED`，符合预期。
- 浏览器控制台存在已有静态 favicon 401 错误；不影响本次页面脚本/API 流程。

## 遗留与风险

- 浏览器验证未调用真实模型或工具；外部模型、工具副作用和非空工具摘要仍需在受控环境中另行验收。
- 页面详情读权限现在需要 `skill:read` 与 `agent:read` 同时具备；这是为了不绕过 Run 详情既有读取授权边界。
- 工作区已有 `application*.yml`、`.codex/`、`.impeccable/`、`.workbuddy/` 等无关改动，本任务未纳入或覆盖。

## 全量核定记录（2026-11-13）

- 核定结论：待续。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：真实模型/工具及非空工具摘要仍列为待另行验收；Fake NOT_TRIGGERED 只证明门禁。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：上述剩余事项继续由原任务/验收范围跟踪；本次不执行它们，不依据后来相关主题自动宣布原验收通过，也不补造取消或移交。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
