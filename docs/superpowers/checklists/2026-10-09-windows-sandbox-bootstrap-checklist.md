# Windows 沙箱引导参数清单

日期：2026-10-09；修订：R0；工作模式：mode=default。Git 基线：`dc541e7ddc59022411a9ed7b3a9727f421d8c073`。

需求来源：用户提供 docx-stdlib 错误日志并确认 SSH 账号密码。原日志首先为 AgentScope PT30S 超时，协议错误在 `SkillArtifactProtocol:145`，随后为沙箱中断。日志不足以证明 DOCX 内容错误。

本次仅修改 `DockerSkillSandbox.java` 的固定引导参数构造、`PasswordSshWindowsFixtureTest.java` 的真实回归，以及配置说明、发布说明和本工作包。不覆盖用户已有 `SkillArtifactProtocol` 日志、Lombok 构建、DOCX 技能、控制台或环境配置修改，不提交、推送或部署。

| 编号 | 状态 | 目标与验收 | 依赖 |
|---|---|---|---|
| T0 | 完成 | 用 Windows JVM、随机密码和 Rocky 独立 Docker 复现协议头前语法错误 | 无 |
| T1 | 完成 | 仅包裹固定引导代码，保持 stdin、隔离与治理边界 | T0 |
| T2 | 完成 | Windows 密码 SSH 的非空输入、DOCX 回传及现有单元测试通过 | T1 |
| T3 | 完成 | Rocky Maven21 容器验证 Linux 文本及文件链路，文档、差异、夹具清理完整 | T2 |
| T4 | 待做 | 用户部署新 Server 后复验真实 TEST，核实原 30 秒超时是否消失 | T3；现场环境 |

当前复现证据：Windows 文本 stdin 成功；同链路实际 docx-stdlib 1.0.1 文件模式失败，原始输出为 `runner=import` 的 Python SyntaxError，没有 CMA1。临时证据位于忽略目录 `dist/validation/password-docx-20261009`。

验收结果：Windows 9 项测试与实际 1.0.1 ZIP 回传通过；实际文档 3589 字节，SHA-256 与脚本一致。Rocky Maven21 的 18 项测试全部通过；本次临时夹具已删除，具体命令见账本。T4 是现场部署验证，不以夹具成功冒充；尚未复验原现场 30 秒超时。

关联文档：[提示词](../prompts/2026-10-09-windows-sandbox-bootstrap-prompt.md) · [设计](../specs/2026-10-09-windows-sandbox-bootstrap-design.md) · [计划](../plans/2026-10-09-windows-sandbox-bootstrap.md) · [实现说明](../implementation/2026-10-09-windows-sandbox-bootstrap-implementation-design.md) · [账本](../progress/2026-10-09-windows-sandbox-bootstrap-ledger.md)。
