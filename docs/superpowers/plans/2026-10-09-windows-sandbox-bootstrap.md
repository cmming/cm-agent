# Windows 沙箱引导参数计划

日期：2026-10-09；修订：R0；工作模式：mode=default。Git 基线：`dc541e7ddc59022411a9ed7b3a9727f421d8c073`。

T0：确认 Windows CLI、JDK21、Rocky Git 与 Maven21；创建带项目标签的临时 dind/sshd，导入预拉取 Python 镜像。用公开 1.0.1 ZIP 顺序验证文本与文件模式，保存受控测试输出，定位协议前 SyntaxError。

T1：在 DockerSkillSandbox.command 中编码固定引导，注释说明已复现的 Windows 引号问题与可信来源；不改技能、stdin、原协议或超时分支。

T2：扩展 PasswordSshWindowsFixtureTest，在轮换前真实执行固定 DOCX 脚本并读取非空 stdin，校验文件长度与结构；保留轮换、原连接继续使用和旧密码拒绝。实际 ZIP 另走同一客户端验摘要。

T3：同步配置和发布说明；将待验证源文件复制到独立构建副本并核实摘要，Linux Docker 回归在 Rocky Maven21 执行。验证结束删除本次标签/名称吻合的夹具与匿名卷，不操作其他服务。检查六份链接和差异。

T4：用户更新 Server 并重启，再对同一技能版本执行 TEST；若仍超时，继续以该 errorId 定位准备、连接、容器与清理时延。本任务不授权自动部署。

关联文档：[清单](../checklists/2026-10-09-windows-sandbox-bootstrap-checklist.md) · [提示词](../prompts/2026-10-09-windows-sandbox-bootstrap-prompt.md) · [设计](../specs/2026-10-09-windows-sandbox-bootstrap-design.md) · [实现说明](../implementation/2026-10-09-windows-sandbox-bootstrap-implementation-design.md) · [账本](../progress/2026-10-09-windows-sandbox-bootstrap-ledger.md)。
