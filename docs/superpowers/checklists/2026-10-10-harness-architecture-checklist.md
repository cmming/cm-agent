# Harness 架构引入需求清单

日期：2026-10-10；主题：harness-architecture；修订：R1；修订日期：2026-10-10；模式：mode=default。

需求：判断CM Agent如何采用Harness，R1按用户要求以当前项目实际依赖为准，可生成依赖报告和隔离POM草案，不应用到正式POM/代码。基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交。

## 范围与证据

新增受控 Harness 执行策略，保留认证、租户、RBAC、工具授权、审批、会话、Skill、Python 沙箱和加密产物；先兼容实验，再接入与只读工作区试点。首阶段默认 ReAct，不重写平台、不新增 Agent 模式字段/数据库迁移/管理页。

R0核查保留为历史，R1新增当前effective POM、reactor依赖树、发布POM、实际下载的Harness2.0.2及候选树，见[设计E1–E13与差异表](../specs/2026-10-10-harness-architecture-design.md)。D4来自用户要求，D1–D3仍为自主方案。U1的依赖解析部分已核实，运行兼容与U2/U3仍未验证。

## 稳定任务

| 编号 | 状态 | 目标与涉及文件 | 依赖 | 验收条件 | 验证 |
| --- | --- | --- | --- | --- | --- |
| T0 | 完成 | 核查与本组六份文档 | 无 | 六文件、链接、模式、任务、授权边界一致，原脏文件保护 | CodeGraph/源码/Context7/javap、文档与 Git 检查 |
| T1 | 待做 | Harness2.0.2兼容实验；根/adapter/server/示例POM及合同 | T0、T9；实施授权 | 利用T9解析证据，继续二进制/编译、权限/事件/Store/关闭运行与MCP/Jackson兼容 | Java21源码、编译、本地模型Stub；项目基线变化时复核依赖 |
| T2 | 待做 | Harness 执行器/模式/取消能力；adapter executor/adapter，server RuntimeConfiguration/Properties | T1 | 默认 react、未知模式拒绝、白名单能力、无隐式旁路、自定义 Runtime/嵌入式兼容、排空门禁 | 配置/工具全集负向/生命周期与双路径合同 |
| T3 | 待做 | 审批/停止/状态/错误；executor、ConversationService、ToolApprovalService、StateStore、RunControlRegistry | T2 | 原 Run 恢复/二次授权，历史不重复、终态清理、拒绝/过期/停止、严格失败与脱敏正确 | adapter/MockMvc/竞态/日志合同，JDBC 在 Rocky |
| T4 | 待做 | Skill/沙箱/产物；SkillSession/Bridge、GovernedSkillAccessService、ManagedSkillSandbox、SkillArtifactService | T2、T3 | 固定快照、撤销、预算、防重、TEST、固定 Python 与加密下载边界保持 | Skill 合同、权限/产物、Rocky Docker/重启 |
| T5 | 待做 | 首项只读工作区上下文；拟新增资源快照/适配路径由 T1 复核 | T3、T4 | 可信映射、冻结摘要与预算、知识实际进入输入，无跨租户/任意宿主访问，清理可证 | 合成输入捕获、路径/符号链接/注入/预算与开销合同 |
| T6 | 待做 | 真实验收/回退/说明；根与 adapter README、configuration/operations/release-notes、测试 | T5 | 双路径/Provider、浏览器、Rocky、排空切换与旧二进制回退，生产限制明确 | 真实模型、会话/审批/TEST/下载浏览器、PostgreSQL16/MySQL8.4、演练 |
| T7 | 待做 | 后续候选上下文压缩 | T6；另行目标/授权 | 唯一历史来源、摘要归属、审批安全、质量与成本指标 | 长会话对照/恢复/脱敏；首批范围外 |
| T8 | 待做 | 后续候选记忆/子 Agent/计划/持续工作区 | T6；另行目标/授权 | 各能力单独范围、权限继承、预算、取消、保留期、并发/租约 | 独立工作包与真实合同；首批范围外 |
| T9 | 完成 | R1当前依赖与隔离草案；effective POM/完整树/候选12个POM/比较JSON/2.0.2 API | T0；用户允许生成依赖 | 当前与草案解析通过，Core/Provider/Studio不升级，正式POM与原脏文件保护，六文档R1一致 | Maven dependency:tree/help:effective-pom/help:evaluate/dependency:get、坐标差异/哈希及文档检查 |

T0保留R0分析完成，T9完成R1依赖评估；T1–T8业务未实施。T7/T8不属于首批隐式依赖。T9通过不代表T1运行合同完成。

## 顺序与门禁

T0 → T9 → T1 → T2 → T3 → T4 → T5 → T6。T9不重复代替T0历史验收；版本/白名单/状态失败不得进入生产，T6未通过不默认化，T5才验收首项增益。

首阶段配置切换排空非终态/检查点/等待 TEST，同数据集不混用策略；在线按 Agent 灰度需新增持久化标记并修订范围。副作用后失败不得自动换策略重跑。

R1运行依赖解析，不运行业务测试。旧2.0.0缓存只保留历史，不再作为候选依据；在线资料和解析成功不能替代未来运行验收。差异R1-01–R1-04见设计/账本，原无关脏文件不改。

## 关联产物

- [提示词](../prompts/2026-10-10-harness-architecture-prompt.md)
- [设计](../specs/2026-10-10-harness-architecture-design.md)
- [计划](../plans/2026-10-10-harness-architecture.md)
- [实现说明](../implementation/2026-10-10-harness-architecture-implementation-design.md)
- [账本](../progress/2026-10-10-harness-architecture-ledger.md)
