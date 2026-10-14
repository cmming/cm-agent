# Harness 2.0.2 运行兼容实验提示词

日期：2026-10-10；主题：harness-202-compatibility；修订：R0；模式：mode=default。

基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交。

本包记录已执行的隔离实验；提示词文件本身不授权后续业务修复或生产接入。

~~~text
仓库 F:/java/cm-agent。工作模式 mode=default，普通取舍依据当前证据自主记录，用户新指令优先。
读取 AGENTS.md 与本包六份文档，尤其清单/账本；来源为 2026-10-10-harness-architecture 的 R1 分析包，保留其历史。
当前最新版本决定：io.agentscope 构件统一保持 2.0.2。正式 POM 无升级；Harness 只在 experiments/harness-compatibility-2.0.2/pom.xml 同版实验。不得将 MCP/其他厂商版本改为2.0.2；Server MCP2.0.0 与独立示例客户端0.17.0分别保持。
C0-C4 已完成隔离实验/证据/文档：clean test 104项、103通过、1外部条件跳过；Harness13项通过。绿色缺陷特征不代表准入通过：G1 Builder仍注册wait_async_results，实验白名单过滤不在正式代码；G2显式HIGH/ASK优先于DONT_ASK，无会话仍暂停。原T1全量安全验收未通过，T2-T6待做。
如用户授权重复实验，先用CodeGraph定位并核对当前HEAD/脏文件/POM/source，再以JDK21运行本实验clean test与dependency:tree；不得沿用旧通过代替新基线。模型Stub仅回环地址，保持无效占位凭据。使用私有空临时目录；原Run审批状态与长期历史边界保持，终态删除由宿主负责，严格失败不得吞掉。
新授权只涉及缺陷分析时只分析；用户明确要求修复或接入时再扩展实施范围/工作包并按原提示词T1→T2→T3顺序验收。不能自动开启Shell/Web/MCP/动态Skill/子Agent/第二套历史；不能副作用后自动改ReAct重跑。G5消息总线/共享Skill扫描风险要验收，不把空目录测试视为宿主路径安全证明。
Docker/JDBC/Flyway/Testcontainers只ssh rocky，核对提交/JDK21/Docker和项目镜像；当前无需容器。真实模型、API、浏览器、Studio/MCP、沙箱未在本实验验证，记录确切缺口，不声称生产可用。
保护原脏文件与原分析六路径；同步本组六文档的任务/结果/差异/链接，报告/日志/source缓存放target不提交。没有生产行为变化不改release-notes并写明原因。交付按摘要、验证、影响、风险、后续顺序；未提交明确标注。不自动提交/推送/部署。
本包完整路径：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-10-harness-202-compatibility-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-10-harness-202-compatibility-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-10-harness-202-compatibility-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-10-harness-202-compatibility.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-10-harness-202-compatibility-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-10-harness-202-compatibility-ledger.md
~~~

## 关联产物

- [清单](../checklists/2026-10-10-harness-202-compatibility-checklist.md)
- [设计](../specs/2026-10-10-harness-202-compatibility-design.md)
- [计划](../plans/2026-10-10-harness-202-compatibility.md)
- [实现说明](../implementation/2026-10-10-harness-202-compatibility-implementation-design.md)
- [账本](../progress/2026-10-10-harness-202-compatibility-ledger.md)
