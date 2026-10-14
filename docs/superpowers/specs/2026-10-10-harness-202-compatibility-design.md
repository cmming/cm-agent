# Harness 2.0.2 运行兼容实验设计

日期：2026-10-10；主题：harness-202-compatibility；修订：R0；模式：mode=default。

基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交。

## 背景与目标

来源：[原分析 R1 提示词](../prompts/2026-10-10-harness-architecture-prompt.md)。用户最初授权 2.0.4 运行实验，随后要求全部 AgentScope 升级 2.0.4，最终明确改为统一保持 2.0.2；以最后决定为准。正式 POM 未发生升级，旧分析包保留历史。本包独立记录已授权运行实验，继续原 T1，不扩大到生产接入。

“一致 2.0.2”适用于 io.agentscope 构件，不能把其他厂商依赖也改成此版本。Server MCP core/json 保留 2.0.0；独立 MCP 示例客户端保留 0.17.0。Jackson 2.19.0、SQLite 3.49.1.0 沿用项目 BOM 覆盖。

目标：实际编译当前 api/core/adapter 工作区源码，以 Harness 2.0.2 的事件流验证同版框架、桥接、审批恢复和资源边界；不通过安装的旧 SNAPSHOT 或旧报告冒充当前运行。

## 方案与调用链

独立实验 POM 继承根版本管理，通过 build-helper 添加三个模块的 main source 与当前 adapter test source，不加入根 modules。新测试位于 com.cmagent.agentscope 包，以包内桥接器/门控复用真实实现。

可信 AgentRunRequest → CM ModelFactory/工具桥接器 → 基础 ReAct 配置 → Harness.Builder.fromAgent → 禁用默认能力 → 构建后白名单过滤 → Harness.streamEvents(msg, context) → 回环 Provider → 当前 Tool/Skill 网关 → 事件边界严格失败复查。tenant:principal 与 runId 分别作为 userId/sessionId；ASK 后保留 Store，关闭/重建再 ConfirmResult；终态由宿主删除。

使用私有空临时工作区，关闭文件/Shell、记忆/Hook、动态/默认 Skill、子 Agent、tools.json、工作区/@路径上下文、压缩/结果落盘、trace、元工具/任务列表与计划。2.0.2 不使用 2.0.4 的 disableWebTools/disableTranscript API，实际工具全集断言防止误套新版文档。

## 验收结果和取舍

同版编译及限定 Stub 路径可运行，**生产准入未通过**。实验完成不等于原分析 T1 的全部安全验收通过，更不等于 T2–T6 已实施。

- G1：仅使用 Builder 禁用配方仍留下 `wait_async_results`。实验在模型调用前移除源 Toolkit 白名单外工具，并保留未过滤特征测试；正式执行器尚无此逻辑。
- G2：HIGH 的显式 ASK 规则优先于 `DONT_ASK` 默认决策，无会话请求仍产生 `RequireUserConfirmEvent`。最终绿色测试记录该缺陷特征，不把它视为业务验收通过；此前“无会话不暂停”的断言确实失败。
- G3：`close()` 不删除状态槽；`disableSessionPersistence()` 配方保留显式 Store 的 ASK/恢复。终态删除仍由 CM Agent 宿主负责，实验使用 InMemory Store，不验证数据库加密/TTL。
- G4：Harness 2.0.2 没有带 RuntimeContext 的 interrupt 重载，实验通过 `getDelegate().interrupt(context)` 实现本轮中断。
- G5：默认消息总线/Inbox 中间件仍存在；关闭默认工作区 Skill 也不等于停止共享 skills 目录扫描。当前只使用每例私有空临时目录；未经验证的共享工作区、收件箱注入与保留期不能直接上线。

自主假设：选择可重复本地 Stub，不引入外部凭据；使用 InMemory Store 仅验证框架恢复，保留服务端仓储的后续验收。Toolkit 容器复制，桥接器实例相同且共享当前 Run gate；不能把已构建的桥接器跨 Run 复用。

## 范围边界

本次环境：Microsoft OpenJDK 21.0.11、Maven 3.9.4；实际 Maven 仓库 `F:/mnt/f/java/maven-repository`。模型仅为 127.0.0.1 合成 SSE 服务，凭据为无效测试占位；没有访问用户模型配置。

未执行真实模型、DashScope 网络调用、Studio 发送、MCP 联网、浏览器、服务端 API、SSH/Rocky、Docker、Testcontainers、JDBC/Flyway 或 Python 沙箱。当前实验不改变这些层，相关验证属于后续 T2–T6。

原分析的推荐仍是渐进受控接入；本次不修改 T2–T6 的业务代码。只读知识上下文收益没有在本次实验实现。

## 关联产物

- [清单](../checklists/2026-10-10-harness-202-compatibility-checklist.md)
- [提示词](../prompts/2026-10-10-harness-202-compatibility-prompt.md)
- [计划](../plans/2026-10-10-harness-202-compatibility.md)
- [实现说明](../implementation/2026-10-10-harness-202-compatibility-implementation-design.md)
- [账本](../progress/2026-10-10-harness-202-compatibility-ledger.md)
