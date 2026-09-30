# 技能沙箱执行提示词

提交记录（2026-10-01）：用户已授权将本任务 41 个文件一同本地提交，提交说明为「新增技能 Python 容器沙箱与受治理执行」。提交编号通过本文件的 Git 历史查询；未推送、未部署。

```text
仓库：F:/java/cm-agent。需求：让技能模块支持在沙箱中运行，按清单实施并完成验证。
先读 AGENTS.md、docs/superpowers/checklists/2026-09-30-skill-sandbox-checklist.md、specs/2026-09-30-skill-sandbox-design.md、plans/2026-09-30-skill-sandbox.md、implementation/2026-09-30-skill-sandbox-implementation-design.md 和 progress/2026-09-30-skill-sandbox-ledger.md。
按照 T0 至 T4 的依赖与当前状态推进；已完成项核验，不重复创建。首版仅执行包内 Python 3 脚本；模型不能选择代码、命令、镜像、环境、宿主路径或运行策略。默认关闭，继续保留真实 tenant/主体/Agent/Run、固定版本、撤销、业务工具授权与严格审计。
在短工作单元中记录 SANDBOX_PREPARED 与严格 PREPARED 审计，再在事务外执行容器。准备不代表模型读取成功，不放行 TEST 发布门禁；资源预算按固定版本重新计算，同一调用标识不能重放。后置复核和 SUCCEEDED 审计完成后才交付脱敏输出。处理超时、中断、输出超限、清理失败和审计/持久化故障，保持稳定错误码与 errorId。
保护既有 application*.yml、.codex/、.workbuddy/ 与其他工作包。Java 注释与落地文字使用中文。V15 仅维护 status 原生注释，不改历史迁移或新增结构；控制台只维护准备状态中文映射。
本机 Maven 使用 F:/java21。所有 Docker/Testcontainers/JDBC/Flyway 验证使用 ssh rocky 的 maven:3.9.9-eclipse-temurin-21。先核对 HEAD 和逐文件 SHA256，只同步本任务文件；运行领域、治理、API、真实 AgentScope 本地协议合同、真实沙箱隔离/配额/超时/清理、双库迁移与 JDBC 测试。实际命令和最终结果以账本为准；外部阻塞记录确切原因，不宣称未运行项通过。
同步六份文档和状态，按变更、验证、影响、风险、后续顺序简洁交付，提供提示词链接与问答快捷入口。本文不额外授权提交、推送或部署。合理工程细节自主决定，无需逐步确认。
```

文件生成本身不构成执行授权；本会话用户已明确授权实施。后续问答入口仅恢复文档上下文，不自动执行其中命令。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [计划](../plans/2026-09-30-skill-sandbox.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)
