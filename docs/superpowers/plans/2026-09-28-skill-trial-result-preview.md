# Skill 试运行结果预览实施计划

对应设计：[2026-09-28-skill-trial-result-preview-design.md](../specs/2026-09-28-skill-trial-result-preview-design.md)。

## 任务拆分

1. 扩展 `SkillResponses`：新增预览与工具摘要 DTO，保证 record 组件中文 JavaDoc、不可变集合和兼容旧构造器。
2. 扩展 `SkillTrialService` 与 `SkillTrialController`：经租户/资源所有权校验读取 Run 详情；试运行创建、审批恢复及详情响应携带预览。
3. 更新 `console/v2/assets/skills.js` 与 `multipage.css`：展示状态、输出、错误和工具摘要，使用纯文本 DOM 渲染并处理空结果。
4. 补充 Service、Controller 与 Node 测试，验证授权边界和预览返回契约。
5. 执行 Java 与 Node 回归，通过隔离 test profile 完成浏览器试运行和窄屏检查。
6. 更新 `docs/release-notes.md`，填写实现说明与进度账本；检查差异和工作区，保留不相关修改，不提交。

## 涉及文件

- `cm-agent-server/src/main/java/com/cmagent/server/runtime/SkillTrialService.java`
- `cm-agent-server/src/main/java/com/cmagent/server/web/SkillResponses.java`
- `cm-agent-server/src/main/java/com/cmagent/server/web/SkillTrialController.java`
- 对应 Service/Controller 测试
- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/skills.js`
- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/multipage.css`
- `cm-agent-console/src/test/js/skills.test.cjs`
- `docs/release-notes.md` 与本组四份任务文档

## 验证方式

- JDK 21 下运行 `mvn -pl cm-agent-server -am -Dtest=SkillTrialControllerTest,SkillTrialServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- 构建并安装控制台模块后，在隔离 `test` profile 中经真实浏览器导入测试技能、创建测试 Agent 并执行 Fake Runtime 试运行。
- 将浏览器视口调整为 390×844，检查结果预览可见且文档无横向溢出。
- 运行 Node 语法/单元测试以及本次文件范围内的 `git diff --check`。
