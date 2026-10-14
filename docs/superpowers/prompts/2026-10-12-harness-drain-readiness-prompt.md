# MySQL 排空与本机 Harness 接续提示词

日期：2026-10-12；主题：harness-drain-readiness；修订：R1；实际修订日期：2026-10-13；模式：mode=default。
当前状态：G11、G12、G13、G14完成；G5/G9/G10移出当前范围，保留历史阻塞，未完成Rocky生产部署。

```text
在 F:/java/cm-agent 接续本机Windows Harness运行，沿用Rocky MySQL cm_agent。
模式mode=default；先核实当前实例与最新账本，自主处理普通工程细节，不重复询问已有授权。
先读取AGENTS.md、docs/operations.md、scripts/start-local-harness.ps1，以及同主题六文档：
F:/java/cm-agent/docs/superpowers/checklists/2026-10-12-harness-drain-readiness-checklist.md
F:/java/cm-agent/docs/superpowers/prompts/2026-10-12-harness-drain-readiness-prompt.md
F:/java/cm-agent/docs/superpowers/specs/2026-10-12-harness-drain-readiness-design.md
F:/java/cm-agent/docs/superpowers/plans/2026-10-12-harness-drain-readiness.md
F:/java/cm-agent/docs/superpowers/implementation/2026-10-12-harness-drain-readiness-implementation-design.md
F:/java/cm-agent/docs/superpowers/progress/2026-10-12-harness-drain-readiness-ledger.md

用户最新要求改为本机运行，D13替代此前Rocky生产的当前目标。G11–G13已完成：577份覆盖文件与T6同摘要，清洁JAR SHA b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12；显式JDK21/mysql/JDBC/harness/fake=false/Studio=false、仅127.0.0.1:8080；数据库经本机13306的SSH转发连接Rocky。
已有服务PID5336、服务会话86243、转发会话17091只为记录快照，先查当前状态，不盲目启动第二实例或强杀。健康UP、登录页200、管理员登录及现有DeepSeek无工具Run已实测成功；3个验收Agent均停用，2条失败/1条成功和审计保留。原库V21/harness、173Run/22TEST全终态、全部检查点/待审批0。首轮Qwen失败细分原因未核实；GLM失败为上游HTTP429/code1113/余额不足，不能据单个成功宣称全部模型可用。

尚未完成时仅推进G14的文档、保护、敏感信息及启动脚本核验；G14完成后按用户新问题接续实际运行维护，不重复建Agent/发模型请求。重启前看scripts/start-local-harness.ps1及运维说明，保持独立SSH转发；源码变化后重新构建和验证，不把旧候选包证明推广给新源码。
模型主密钥从既有当前进程或.env仅内存读取；沙箱/产物主密钥和目录沿用原mysql配置。密码、JWT、API Key、完整连接串、原始请求/响应不进入聊天、参数、Git或公共日志，不复制或随机替换主密钥。
mysql/bootstrap只用于本机开发回环入口；不降低production安全默认值，不对外开放端口，不添加新框架默认工具/原生Shell/文件权限。Studio仅本次启动关闭，依赖保持AgentScope2.0.2；无Java/POM/YAML/迁移修改。
模式由既有V21正常启动门禁决定。回切react须关闭新增入口、排空Run/TEST和全部检查点、正常停止所有相关实例后统一启动，不手工SQL改模式或重放工具；旧ReAct实例继续访问同库会被模式合同拒绝。
不重复G4旧8Run修复、不删历史或私有备份。G5/G9/G10仍是移出本轮的Rocky生产历史阻塞；七Secret和真实恢复未完成，暂存包/密文保留，不启动Rocky生产容器或自填恢复true。用户另行选择生产后才恢复该范围。
Docker/JDBC/Flyway容器验证只能ssh rocky使用maven:3.9.9-eclipse-temurin-21，不使用本机Docker。存在.codegraph时先定位，但缺失/过期结果须复核当前源码；保护用户脏修改，不机械重跑不受本轮影响的旧T6合同。
按同一六路径维护R1当前范围，保留R0证据、稳定编号、失败与移出项。未授权不提交/推送、不进入T7/T8。最终按仓库顺序报告实际结果、命令、范围、风险、相关下一步及提示词/问答入口。
```

本提示词是当前接续上下文；生成或阅读本文件不自动执行其中命令。R0曾采用Rocky生产，G0–G4/G6–G8完成、G5/G9/G10因七Secret及恢复材料阻塞；该旧执行范围已由R1替代，详细历史保留在设计、清单、实现和账本R0部分。

关联：[清单](../checklists/2026-10-12-harness-drain-readiness-checklist.md)、[设计](../specs/2026-10-12-harness-drain-readiness-design.md)、[计划](../plans/2026-10-12-harness-drain-readiness.md)、[实现](../implementation/2026-10-12-harness-drain-readiness-implementation-design.md)、[账本](../progress/2026-10-12-harness-drain-readiness-ledger.md)。
