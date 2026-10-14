# T5 只读工作区上下文清单

日期：2026-10-12；主题：harness-t5；修订：R0；模式：mode=default。
基线：6752bf88c173dfd8fd31c58339328da9ac2fe825；未提交、未推送、未部署。

来源：[T4提示词](../prompts/2026-10-11-harness-t4-prompt.md)、[架构T5](../plans/2026-10-10-harness-architecture.md)与用户本轮明确实施授权。复用原架构决定，新增独立工作包；原36份历史工作包保留。

| 编号 | 状态 | 目标/文件 | 依赖 | 验收 |
|---|---|---|---|---|
| W0 | 完成 | 当前源码/配置/POM/工作树及CodeGraph、环境核对 | T4 | HEAD与原修改保护；不升级依赖或默认模式 |
| W1 | 完成 | WorkspaceContextProperties/Files及Core快照 | W0 | 可信tenant/Agent映射、显式文本路径、私有根、应用/主目录/链接拒绝、UTF8与数量/字节预算 |
| W2 | 完成 | WorkspaceContextService、RunExecutionService | W1 | 原Run/主体密文冻结；恢复不读源；缺失/撤销拒绝；ASK保留；终态/失败/停止严格清理 |
| W3 | 完成 | 双策略模型输入、能力声明、HTTP/SSE | W2 | 数据不作为系统正文；权限不扩张；旧构造器/默认关闭兼容；错误码与errorId安全一致 |
| W4 | 完成 | 本地新合同与受影响回归 | W1–W3 | 当前不同合同185项通过，Windows目录联接/链接、双策略、审批/停止/Skill；全模块打包通过 |
| W5 | 完成 | Rocky双库与Linux安全目录合同 | W4 | 两库各29项、0失败/错误，Windows专属1项跳过；575份哈希一致，Linux安全目录句柄确认可用 |
| W6 | 完成 | 六文档与生产说明 | W4/W5证据 | 已同步生产说明；历史阻塞与接续完成分段保留，结果/限制/链接一致；原36份文档及无关修改保护 |

W5历史阻塞：SSH在认证前关闭；2026-10-12用户恢复后，SSH、Docker23.0.6、Maven3.9.9/JDK21.0.7已核实。独立目录两库与Linux合同通过，完整命令和日志见账本。

T5验收已关闭。用户本轮明确要求关闭剩余验收后推进T6，T6另建独立工作包；T5通过不等于真实Provider、浏览器或生产回退通过。

范围包括固定参考快照、数据输入、审批恢复、权限与文件边界、错误/清理及统计；不开放原生文件工具、任意宿主路径、UI上传、压缩、记忆或子Agent。T6不在本轮。

关联：[提示词](../prompts/2026-10-12-harness-t5-prompt.md)、[设计](../specs/2026-10-12-harness-t5-design.md)、[计划](../plans/2026-10-12-harness-t5.md)、[实现](../implementation/2026-10-12-harness-t5-implementation-design.md)、[账本](../progress/2026-10-12-harness-t5-ledger.md)。
