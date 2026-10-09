# Windows 沙箱引导参数提示词

日期：2026-10-09；修订：R0；工作模式：mode=default。Git 基线：`dc541e7ddc59022411a9ed7b3a9727f421d8c073`。

```text
仓库 F:/java/cm-agent，mode=default。修复 Windows Java 经 SSH 账号密码调用 Linux Docker 时，含双引号的固定文件引导代码被命令参数传递破坏的问题。
先读取 AGENTS.md 和以下工作包：
docs/superpowers/checklists/2026-10-09-windows-sandbox-bootstrap-checklist.md
docs/superpowers/prompts/2026-10-09-windows-sandbox-bootstrap-prompt.md
docs/superpowers/specs/2026-10-09-windows-sandbox-bootstrap-design.md
docs/superpowers/plans/2026-10-09-windows-sandbox-bootstrap.md
docs/superpowers/implementation/2026-10-09-windows-sandbox-bootstrap-implementation-design.md
docs/superpowers/progress/2026-10-09-windows-sandbox-bootstrap-ledger.md
依据 T0→T1→T2→T3 推进。用户原日志显示 PT30S 工具超时和 CMA1 协议头失败；独立夹具已复现 runner=import 的 Python 语法错误，不能将这份复现直接宣称为现场所有超时原因。
仅将服务端固定引导代码编码为 Base64 并用不含双引号的固定 -c 表达式执行；脚本、资源和 stdin 继续使用原有输入协议。保持 Python -I/-B/-u、无网络、非 root、预算、清理、授权与审计，不接受包或模型覆盖引导代码。
维护 Windows 密码 SSH 真实 DOCX 回归，包括非空中文 stdin、合法文件结构、字节长度和轮换后连接；使用 Rocky 临时独立 daemon、随机测试密码和可信临时主机公钥，不读取生产凭据。
本机 JVM 客户端回归连接的容器必须位于 Rocky；Linux 回归在 ssh rocky 的 maven:3.9.9-eclipse-temurin-21 容器中执行，并确认 Git 基线和待验证源文件摘要一致。保护现有修改，在独立副本构建，记录实际命令、结果、失败及未执行验收。
更新六份工作包、docs/configuration.md、docs/release-notes.md，清理仅本次创建的夹具。T4 待用户部署后复验，不自动部署、不索要或保存生产密码；没有提交或推送授权。
```

提示词是当前范围的维护输入；进入问答不自动执行实施或部署。

关联文档：[清单](../checklists/2026-10-09-windows-sandbox-bootstrap-checklist.md) · [设计](../specs/2026-10-09-windows-sandbox-bootstrap-design.md) · [计划](../plans/2026-10-09-windows-sandbox-bootstrap.md) · [实现说明](../implementation/2026-10-09-windows-sandbox-bootstrap-implementation-design.md) · [账本](../progress/2026-10-09-windows-sandbox-bootstrap-ledger.md)。
