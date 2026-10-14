# Harness 2.0.2 运行兼容实验实现说明

日期：2026-10-10；主题：harness-202-compatibility；修订：R0；模式：mode=default。

基线：`6752bf88c173dfd8fd31c58339328da9ac2fe825`；未提交。

## 实际交付

来源：[原分析 R1 提示词](../prompts/2026-10-10-harness-architecture-prompt.md)。用户最初授权 2.0.4 运行实验，随后要求全部 AgentScope 升级 2.0.4，最终明确改为统一保持 2.0.2；以最后决定为准。正式 POM 未发生升级，旧分析包保留历史。本包独立记录已授权运行实验，继续原 T1，不扩大到生产接入。

“一致 2.0.2”适用于 io.agentscope 构件，不能把其他厂商依赖也改成此版本。Server MCP core/json 保留 2.0.0；独立 MCP 示例客户端保留 0.17.0。Jackson 2.19.0、SQLite 3.49.1.0 沿用项目 BOM 覆盖。

已生成独立实验 POM、README 和 Harness202ContractTest，正式生产运行仍为原 ReAct。依赖直接继承 root agentscope.version2.0.2，Core/Provider/Harness 同版；十二项目树证明 Studio/Redis 也保持 2.0.2。

13 项 Harness 测试覆盖：最终工具全集/Toolkit 复制/配置继承；真实流式工具调用；未知文件工具拒绝；ASK 关闭重建/ConfirmResult/宿主删除；审批拒绝；无会话 DONT_ASK 缺陷特征；原 Builder 异步等待工具特征；Skill 固定快照读取；批准后二次授权撤销；租户/Run 状态隔离；严格基础设施失败；取消；Provider 超时。

Harness 调用经过当前桥接器，不以 ReAct 测试代替 Harness；另复用原 91 项适配层测试，其中 1 项外部停止测试按环境条件跳过。当前 clean test 共 104 项，103 通过、1 跳过。

## 与最初方案的差异

2.0.4 候选只下载/检查过源码；初稿 testCompile 因实验代码签名使用错误而失败，未取得 2.0.4 运行结果，不据此判断框架不兼容。用户变更版本后将本次未跟踪草稿移为 2.0.2 工程并按实际 API 修正；正式 POM 始终保持原值。

首轮 2.0.2 10 项出现 3 个失败：两个是默认 wait_async_results 的白名单差异，另一个是显式 ASK 在 DONT_ASK 下暂停。最终实验加入白名单过滤并新增未过滤特征；权限问题保留为缺陷特征。绿色 suite 不覆盖业务准入失败事实。

同版编译及限定 Stub 路径可运行，**生产准入未通过**。实验完成不等于原分析 T1 的全部安全验收通过，更不等于 T2–T6 已实施。

- G1：仅使用 Builder 禁用配方仍留下 `wait_async_results`。实验在模型调用前移除源 Toolkit 白名单外工具，并保留未过滤特征测试；正式执行器尚无此逻辑。
- G2：HIGH 的显式 ASK 规则优先于 `DONT_ASK` 默认决策，无会话请求仍产生 `RequireUserConfirmEvent`。最终绿色测试记录该缺陷特征，不把它视为业务验收通过；此前“无会话不暂停”的断言确实失败。
- G3：`close()` 不删除状态槽；`disableSessionPersistence()` 配方保留显式 Store 的 ASK/恢复。终态删除仍由 CM Agent 宿主负责，实验使用 InMemory Store，不验证数据库加密/TTL。
- G4：Harness 2.0.2 没有带 RuntimeContext 的 interrupt 重载，实验通过 `getDelegate().interrupt(context)` 实现本轮中断。
- G5：默认消息总线/Inbox 中间件仍存在；关闭默认工作区 Skill 也不等于停止共享 skills 目录扫描。当前只使用每例私有空临时目录；未经验证的共享工作区、收件箱注入与保留期不能直接上线。

本次环境：Microsoft OpenJDK 21.0.11、Maven 3.9.4；实际 Maven 仓库 `F:/mnt/f/java/maven-repository`。模型仅为 127.0.0.1 合成 SSE 服务，凭据为无效测试占位；没有访问用户模型配置。

未执行真实模型、DashScope 网络调用、Studio 发送、MCP 联网、浏览器、服务端 API、SSH/Rocky、Docker、Testcontainers、JDBC/Flyway 或 Python 沙箱。当前实验不改变这些层，相关验证属于后续 T2–T6。

没有发布行为变化，release-notes 未改。原分析六文档不覆盖；未提交。

## 关联产物

- [清单](../checklists/2026-10-10-harness-202-compatibility-checklist.md)
- [提示词](../prompts/2026-10-10-harness-202-compatibility-prompt.md)
- [设计](../specs/2026-10-10-harness-202-compatibility-design.md)
- [计划](../plans/2026-10-10-harness-202-compatibility.md)
- [账本](../progress/2026-10-10-harness-202-compatibility-ledger.md)
