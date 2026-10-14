# Harness 2.0.2 准入问题修复清单

日期：2026-10-10；主题：harness-entry-hardening；修订：R0；模式：mode=default。

基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；本次未提交、未推送、未部署。

## 来源与范围

用户要求按照 [2.0.2 实验提示词](../prompts/2026-10-10-harness-202-compatibility-prompt.md)继续下一步。本包是独立实施包，引用原 [Harness 架构 R1 清单](../checklists/2026-10-10-harness-architecture-checklist.md)与[实验 R0 账本](../progress/2026-10-10-harness-202-compatibility-ledger.md)，不覆盖原十二份文档或历史任务编号。

本轮完成 G1/G2 修复及 G5 隔离配方验证。G2 修改当前 ReAct 的生产权限配方和拒绝映射；G1/G5 在独立 Harness 实验落地。全部 AgentScope 构件保持 2.0.2；没有新增生产 Harness 执行器或执行模式。

## 稳定任务编号与验收

| 编号 | 状态 | 工作与依赖 | 验收及当前证据 |
| --- | --- | --- | --- |
| E0 | 完成 | 当前提示词、历史包、POM/配置、CodeGraph 调用链、脏文件基线；无前置 | 核对 HEAD/JDK21/Maven；正式 POM、原十二份文档与原脏文件哈希保持 |
| E1 | 完成 | 无会话 HIGH 显式 DENY 与拒绝终态；依赖 E0 | 有会话 ASK、开关关闭 ALLOW 保持；无会话无确认/网关调用/检查点；DENIED 记录及固定中文原因，关联 WARN 日志且脱敏 |
| E2 | 完成 | Harness 最终白名单、私有目录、文件系统和总线限定；依赖 E0/E1 | 工具仅当前治理桥接器、身份相同；read_file/wait_async_results 不调用网关；宿主配置/记忆/共享 Skill 注入不进入输入、不改变宿主文件；额外文件/总线操作使断言失败 |
| E3 | 完成 | 当前源码合同与服务端回归；依赖 E1/E2 | clean test 107 项，106 通过/1 外部条件跳过；Harness 15 项通过；服务端配置/Run/审批 API 46 项通过 |
| E4 | 完成 | 六份实施文档、配置/adapter/实验说明、发布说明；依赖 E3 | 历史保留、任务/结果/路径一致；差异检查无新增空白错误；未提交 |

E3 完成只针对本轮约定的本地回归；外部真实停止、真实模型和生产 Harness 端到端未执行，不能替代原 T1 全量准入。总计 153 个不同用例：152 通过、1 跳过。

## 后续门禁

原 T2–T6 保持待做：生产 Harness 工厂/执行器、执行模式和默认 ReAct、生命周期与取消、审批/检查点、固定 Skill 脚本/沙箱/产物、可信工作区上下文及真实模型/双库/浏览器验收。实验中的 Mockito 替身不能用于生产；T2 必须实现对应受控能力并重跑原 T1 需要的完整合同。没有副作用后自动换 ReAct 重跑、第二套历史、Shell/Web/MCP/动态 Skill/子 Agent 自动开放。

## 关联产物

- [清单](../checklists/2026-10-10-harness-entry-hardening-checklist.md)
- [提示词](../prompts/2026-10-10-harness-entry-hardening-prompt.md)
- [设计](../specs/2026-10-10-harness-entry-hardening-design.md)
- [计划](../plans/2026-10-10-harness-entry-hardening.md)
- [实现说明](../implementation/2026-10-10-harness-entry-hardening-implementation-design.md)
- [账本](../progress/2026-10-10-harness-entry-hardening-ledger.md)

