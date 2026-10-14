# T6 当前执行与问答提示词

日期：2026-10-12；主题：harness-t6；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署生产；隔离验收实例已清理。

本轮V0–V5已完成，当前主要用于复核与问答。原用户授权覆盖T6验证和必要修复，不自动授权生产部署、默认化或T7/T8。

~~~text
在F:/java/cm-agent按AGENTS.md读取本轮六文档，mode=default，自主处理普通工程选择并记录证据：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-12-harness-t6-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-12-harness-t6-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-12-harness-t6-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-12-harness-t6.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-12-harness-t6-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-12-harness-t6-ledger.md
T5 W5及T6 V0–V5已完成，先核对最新HEAD/脏修改/账本。不重跑已通过且无变化合同；用户只进入问答时不执行实施命令，不自动推进T7/T8、提交、推送或生产部署。
复核源码定位先CodeGraph，旧索引缺口再读当前文件，不重建索引；先读POM/README/配置。默认react、AgentScope2.0.2/MCP不改。两真实协议是DashScope原生和其OpenAI兼容接口，实际qwen-plus；不能泛化到任意上游。
只有用户明确要求重验且变更相关时，显式CM_AGENT_TEST_LIVE_PROVIDER=true运行真实8项，凭据从DASHSCOPE_API_KEY内存注入；禁止打印/保存密钥/JWT、原始模型请求和业务正文。浏览器仅使用合成测试数据。审批completed.run来自服务端，拒绝/停止/失败/成功或新审批按实际状态刷新提示，保留刷新后的原主体/会话核对与一次写入；chat.html引用app.js?v=2.0.32。
所有Docker/Testcontainers/JDBC/Flyway仅ssh rocky，maven:3.9.9-eclipse-temurin-21、postgres16-alpine/mysql8.4，独立工作区/临时数据库。先核对SSH/Docker/JDK21、HEAD/覆盖哈希；系统时钟偏差如实记录，不改SSH/主机密钥或时钟，不用本机Docker替代。不得全局清理。
普通package是薄JAR，先install模块依赖，再在Server同一生命周期package spring-boot:repackage；重复打包静态资源可用-Dmaven.jar.forceCreation=true。实际二进制合同显式CM_AGENT_TEST_BINARY_ROLLBACK=true和CM_AGENT_TEST_CURRENT_JAR/CM_AGENT_TEST_OLD_JAR路径，使用独占临时数据。
部署/回退仍先停止新Run/TEST→核对所有非终态/审批/检查点→完成或显式取消并等待退出→备份/版本检查→停所有实例→目标模式/旧包→新Run。仅已验证旧基线6752bf8且无检查点；不得恢复未验证旧ASK格式、删迁移或自动重放未知副作用。V21前旧包没有模式门禁，不可混用旧实例承诺隔离。本轮仅备份生成/摘要，未证明生产备份还原。
保护原POM/YAML/root index/.gitignore/.codex/.workbuddy及历史工作包，必要Java注释/JavaDoc用中文并按触发清单落地。同步本轮六文档与生产说明，记录实测/历史/未测和清理。变更后只跑相关合同；API/Java字节码无变化不重复所有后端验收。
最终按变更、实际命令与结果、影响、风险、直接下一步交付；提示词和快捷问答入口不额外授予修改或部署权限。
~~~

关联：[清单](../checklists/2026-10-12-harness-t6-checklist.md)、[设计](../specs/2026-10-12-harness-t6-design.md)、[计划](../plans/2026-10-12-harness-t6.md)、[实现](../implementation/2026-10-12-harness-t6-implementation-design.md)、[账本](../progress/2026-10-12-harness-t6-ledger.md)。

