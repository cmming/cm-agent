# 技能版本升级与控制台优化执行提示词

在 F:/java/cm-agent 先读取本工作包清单、账本、设计、计划、实现说明和 AGENTS.md。默认自主处理工程细节；本轮需求已授权实施，但此文件本身不提供额外操作授权。

## R0 历史执行范围

按 T1～T6 核对原修订状态，保留完成证据和用户无关修改，不重复实现。核心范围：在所选技能详情接入 POST /api/skills/{id}/versions，携带详情快照的 expectedCandidateVersionId / expectedPublishedVersionId，禁止使用 legacy expectedVersionId；只形成候选，不自动 TEST、发布或覆盖正式版本。保留同名创建冲突，在错误提示中引导升级。维护写权限、请求代际、重复提交锁、失败保留文件与错误编号。

优化真实中文 skills.html：导入在列表栏，升级在详情，版本号使用 versionNo；候选与正式版本分开显示，历史状态中文，兼容沙箱端点页签及登录跨页挂载。CSS 仅限定技能页，全部 v2 入口同步资源版本。

验证 Node 测试、JDK 21 控制台 Maven 测试和 SkillControllerTest；该类默认能力断言要求通过命令行显式设置 cm-agent.skills.sandbox.enabled=false，避免用户本地配置影响测试。浏览器使用独立内存夹具，不写用户服务数据；检查桌面、390px、1024px、键盘焦点及表单校验。当前 Chrome 扩展没有文件网址访问权限，原生选文件链路受阻，应记录限制，不擅自扩大权限。Docker/JDBC/Flyway 如后续需要，只能 ssh rocky 指定容器环境执行。

本轮使用 codex/ 分支；不提交、不推送、不合并、不部署、不保存真实凭据。同步 T1～T6 状态、实际命令和外部阻塞，最后报告变更与生效方法。

## R1 当前执行范围

先核对 T1～T6 的 R0 交付与原生上传阻塞，不重复已完成项。本次采用 R1 的 T7～T9：统一全部 v2 入口的沙箱 CSS/JS 与共享脚本缓存；在有效导航替换 body 前和退出时释放技能/沙箱实例，返回重新挂载；离开沙箱页清空材料并拒绝迟到读取、保存与旧事件影响新页面。

以 node --test cm-agent-console/src/test/js/*.test.cjs、脚本语法检查、JDK 21 的 mvn -q -pl cm-agent-console -am test 验证；真实 Chrome 在 localhost:8080 只读检查概览进入、重复进入、刷新、后退、详情与沙箱页签事件及 390px。R1 无后端变化，不需重跑 SkillControllerTest 或 Docker/JDBC/Flyway；如范围扩大涉及容器，仍按 ssh rocky 规则执行。

保持默认自主模式、codex/ 分支、未提交/未推送/未合并/未部署。不写用户业务数据、不更改用户运行服务，不保存真实凭据。同步原六文档 R1 状态，保留 R0 证据。此提示词仅用于复核当前范围，不额外授权上传、发布或端点变更。

## 关联工作包

- [清单](../checklists/2026-10-08-skills-version-console-checklist.md)
- [提示词](../prompts/2026-10-08-skills-version-console-prompt.md)
- [设计](../specs/2026-10-08-skills-version-console-design.md)
- [计划](../plans/2026-10-08-skills-version-console.md)
- [实现](../implementation/2026-10-08-skills-version-console-implementation-design.md)
- [账本](../progress/2026-10-08-skills-version-console-ledger.md)
