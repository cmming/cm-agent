# 技能沙箱实施计划

提交记录（2026-10-01）：用户已授权将本任务 41 个文件一同本地提交，提交说明为「新增技能 Python 容器沙箱与受治理执行」。提交编号通过本文件的 Git 历史查询；未推送、未部署。

| 编号 | 状态 | 目标与文件 | 依赖 | 验收与验证 |
|---|---|---|---|---|
| T0 | 完成 | 核实 Skill 链路、工作树与环境，生成六份文档 | 无 | 六份产物齐全，已有配置及工作包保留 |
| T1 | 完成 | Core 网关默认执行扩展；SkillSandboxProperties、DockerSkillSandbox | T0 | 默认关闭；固定命令；有界 stdin、输出、并发、超时及唯一容器清理 |
| T2 | 完成 | GovernedSkillAccessService、AgentScopeSkillExecutionBridge 与准备状态 | T1 | 固定快照、租户/主体、运行状态、撤销、持久化预算、重复拒绝、严格审计、发布门禁测试 |
| T3 | 完成 | 能力接口、部署 profile、V15 状态注释、中文状态标签与生产说明 | T2 | 策略与导入白名单一致；不改变表结构；准备不显示为成功 |
| T4 | 完成 | Java 21 回归、Rocky 真实容器与双数据库验证、工作包核查 | T1,T2,T3 | 首轮全量通过；最终专项 215 项、JS 85 项通过，哈希/文档/零残留已核对 |

1. T0：阅读规范、技能契约、POM、README 与配置，核对 HEAD 和脏文件；调用 CodeGraph，无法匹配后直接定位 Skill 源码；生成六份文档。
2. T1：为 Core SkillAccessGateway 加默认关闭执行扩展，新增沙箱配置、固定参数 Docker 执行器；校验路径、输入、资源、时间与并发，处理所有退出路径的清理。文件：core/runtime、server/config/SkillSandboxProperties、server/runtime/DockerSkillSandbox。
3. T2：增加资源准备、有效 Run/快照/租户/主体/撤销复核、预算与严格审计；新增模型脚本桥接并复用 AgentScopeRunGate。为 SkillLoadStatus/SkillLoadRecord 添加 SANDBOX_PREPARED，准备不成为成功模型读取；为普通重放和跨实例预算保留持久化语义。
4. T3：更新 SkillController/SkillResponses 能力、专用配置 profile、中文状态映射；新增 PostgreSQL/MySQL V15 原生注释迁移；维护 README、adapter README 与 release-notes。补原生注释、JDBC 新状态往返与中文显示断言。
5. T4：本地 Java 21 运行适配器、领域、治理与 API 测试；Node 测试中文状态。ssh rocky 检查 Docker、Maven 21、HEAD 与逐文件 SHA256，在 Maven 3.9.9 镜像内运行全量回归和最终修改专项复验；真实容器测试显式启用 CM_AGENT_TEST_SANDBOX，双库使用 PostgreSQL 16/MySQL 8.4。检查零沙箱残留、本任务 diff 与六份产物。

发生代码或测试修正后只重复受影响检查；外部阻塞写明确切错误，不把代码完成当作验收通过。禁止全局 Docker 清理和覆盖无关配置；无需提交、推送或部署。

关联文档：[清单](../checklists/2026-09-30-skill-sandbox-checklist.md) · [提示词](../prompts/2026-09-30-skill-sandbox-prompt.md) · [设计](../specs/2026-09-30-skill-sandbox-design.md) · [实现说明](../implementation/2026-09-30-skill-sandbox-implementation-design.md) · [账本](../progress/2026-09-30-skill-sandbox-ledger.md)
