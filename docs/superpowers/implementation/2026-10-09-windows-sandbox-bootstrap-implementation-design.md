# Windows 沙箱引导参数实现说明

日期：2026-10-09；修订：R0；工作模式：mode=default。Git 基线：`dc541e7ddc59022411a9ed7b3a9727f421d8c073`。

本次仅修改 `DockerSkillSandbox.java` 的固定引导参数构造、`PasswordSshWindowsFixtureTest.java` 的真实回归，以及配置说明、发布说明和本工作包。不覆盖用户已有 `SkillArtifactProtocol` 日志、Lombok 构建、DOCX 技能、控制台或环境配置修改，不提交、推送或部署。

已落地：DockerSkillSandbox 对服务端固定的文本/产物引导程序统一 UTF-8/Base64 包裹，再用不含双引号的 Python表达式执行。未更改被执行的引导逻辑、脚本 JSON 输入、产物帧或执行限制。

已扩展 Windows 回归，在 HTTP 探测与密码轮换之外验证实际 Docker CLI→Python→DOCX，显式包含非空中文 stdin 和合法文件结构。只在启用临时夹具环境变量的 Windows JVM执行。

配置文档说明更新 Server 生效、不需更换技能ZIP；发布说明记录 Windows 参数兼容修复。

已验证：Windows 密码 SSH 文件回归、非空 stdin、密码轮换、旧密码拒绝与现有单元测试共 9 项全部通过；实际 docx-stdlib 1.0.1 ZIP 回传 3589 字节、格式和 SHA-256 校验一致。Rocky Maven21 的 Linux 单元/容器回归 18 项全部通过。编码前同一链路报 runner=import SyntaxError；编码后收到正确 CMA1、退出0。

待验证源码与远端测试副本的 DockerSkillSandbox.java SHA-256 一致：961e5b65d00ff888eb7204a51fa84371e227684bc588e50b919f82a2bcdf3782。只清理了本次临时夹具，测试凭据未存储。现场 Server 尚未更新，T4 待用户 TEST；原 PT30S 日志的完整时延原因不能以本次通过替代调查。

与设计无差异；没有修改用户已有错误日志调用，不能用本次修复宣称现场所有超时已消失。

关联文档：[清单](../checklists/2026-10-09-windows-sandbox-bootstrap-checklist.md) · [提示词](../prompts/2026-10-09-windows-sandbox-bootstrap-prompt.md) · [设计](../specs/2026-10-09-windows-sandbox-bootstrap-design.md) · [计划](../plans/2026-10-09-windows-sandbox-bootstrap.md) · [账本](../progress/2026-10-09-windows-sandbox-bootstrap-ledger.md)。
