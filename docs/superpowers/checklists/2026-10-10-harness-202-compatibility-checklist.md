# Harness 2.0.2 运行兼容实验清单

日期：2026-10-10；主题：harness-202-compatibility；修订：R0；模式：mode=default。

基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交。

## 需求与范围

来源：[原分析 R1 提示词](../prompts/2026-10-10-harness-architecture-prompt.md)。用户最初授权 2.0.4 运行实验，随后要求全部 AgentScope 升级 2.0.4，最终明确改为统一保持 2.0.2；以最后决定为准。正式 POM 未发生升级，旧分析包保留历史。本包独立记录已授权运行实验，继续原 T1，不扩大到生产接入。

“一致 2.0.2”适用于 io.agentscope 构件，不能把其他厂商依赖也改成此版本。Server MCP core/json 保留 2.0.0；独立 MCP 示例客户端保留 0.17.0。Jackson 2.19.0、SQLite 3.49.1.0 沿用项目 BOM 覆盖。

## 稳定任务

| 编号 | 状态 | 目标 | 依赖 | 验收与证据 |
| --- | --- | --- | --- | --- |
| C0 | 完成 | 核对授权、规范、基线和保护已有修改 | 无 | 正式 POM/源码/配置及原分析六文档 SHA256 不变，HEAD/暂存区未变 |
| C1 | 完成 | AgentScope 同版 2.0.2 依赖核查 | C0 | 十二项目 reactor dependency:tree 成功；Core/两种 Provider/Studio/Redis 2.0.2；实验 Harness 同版 |
| C2 | 完成 | 隔离编译与模型 Stub 运行合同 | C1 | clean test：104 项，103 通过、1 条件跳过；其中 Harness 新增 13 项通过 |
| C3 | 完成 | 记录准入失败与宿主生命周期边界 | C2 | G1/G2 已复现并保留特征测试；不宣称生产准入通过 |
| C4 | 完成 | 实验工程、六份工作包与一致性检查 | C3 | 实验不加入根 reactor，原分析包不覆盖，文档链接/差异检查通过 |

## 准入与未实施项

同版编译及限定 Stub 路径可运行，**生产准入未通过**。实验完成不等于原分析 T1 的全部安全验收通过，更不等于 T2–T6 已实施。

- G1：仅使用 Builder 禁用配方仍留下 `wait_async_results`。实验在模型调用前移除源 Toolkit 白名单外工具，并保留未过滤特征测试；正式执行器尚无此逻辑。
- G2：HIGH 的显式 ASK 规则优先于 `DONT_ASK` 默认决策，无会话请求仍产生 `RequireUserConfirmEvent`。最终绿色测试记录该缺陷特征，不把它视为业务验收通过；此前“无会话不暂停”的断言确实失败。
- G3：`close()` 不删除状态槽；`disableSessionPersistence()` 配方保留显式 Store 的 ASK/恢复。终态删除仍由 CM Agent 宿主负责，实验使用 InMemory Store，不验证数据库加密/TTL。
- G4：Harness 2.0.2 没有带 RuntimeContext 的 interrupt 重载，实验通过 `getDelegate().interrupt(context)` 实现本轮中断。
- G5：默认消息总线/Inbox 中间件仍存在；关闭默认工作区 Skill 也不等于停止共享 skills 目录扫描。当前只使用每例私有空临时目录；未经验证的共享工作区、收件箱注入与保留期不能直接上线。

后续修复 G1/G2 并验收 G5 后再考虑原 T2；业务任务保持待做，本包没有生产执行器/配置/数据库改动。C3 的完成表示实验结果已记录，不表示缺陷已修复。

## 关联产物

- [提示词](../prompts/2026-10-10-harness-202-compatibility-prompt.md)
- [设计](../specs/2026-10-10-harness-202-compatibility-design.md)
- [计划](../plans/2026-10-10-harness-202-compatibility.md)
- [实现说明](../implementation/2026-10-10-harness-202-compatibility-implementation-design.md)
- [账本](../progress/2026-10-10-harness-202-compatibility-ledger.md)
