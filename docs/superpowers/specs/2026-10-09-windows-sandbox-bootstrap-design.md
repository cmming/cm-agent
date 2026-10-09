# Windows 沙箱引导参数设计

日期：2026-10-09；修订：R0；工作模式：mode=default。Git 基线：`dc541e7ddc59022411a9ed7b3a9727f421d8c073`。

背景：前序 DOCX 技能 [工作包](../checklists/2026-10-09-docx-stdlib-skill-checklist.md) 的本地 Rocky 产物回传通过，但 Windows SSH 密码链路暴露新的命令参数兼容问题。本次作为平台独立修复，不修改 DOCX 输入或产物格式。

事实：直接将完整多行固定 Python 代码传入 Docker -c 时，Windows 实际收到 `runner=import`，而源码为 `runner="import ..."`；Python 在协议头前退出1。文本模式没有这一 runner 字面量，复现中通过。

方案 D1（自主采用）：Base64 编码服务端固定引导代码，外层只使用 `import base64;exec(base64.b64decode('...'))`；编码采用 UTF-8，两种执行模式统一处理。Python 参数 -I/-B/-u、资源 stdin 和全部隔离参数保留。此编码仅解决参数传递，不提供加密或新增安全授权。

约束：密码只在内存和 chpasswd stdin 中出现；测试夹具绑定 Rocky 回环地址，用已有 ssh rocky 隧道进入，拥有独立 daemon，不挂载宿主 Docker socket。临时夹具需 dind 入口初始化 cgroup，错误的夹具启动不算业务复现。

验收：修复前原始字节确认 SyntaxError；修复后真实 Windows DOCX 帧/结构/长度、非空 stdin、密码轮换及失效认证均通过；同一 ZIP 摘要匹配；Linux 现有隔离、超时与产物回归通过；夹具删除。

限制：尚未使用用户真实端点，原日志 PT30S 时间线不能仅由本次短时 SyntaxError 完全解释。T4 需现场 TEST，保留独立超时问题的排查可能性。

非目标：调整工具/沙箱超时、修改文件名校验或异常日志、关闭授权、数据库迁移、读取真实凭据。

关联文档：[清单](../checklists/2026-10-09-windows-sandbox-bootstrap-checklist.md) · [提示词](../prompts/2026-10-09-windows-sandbox-bootstrap-prompt.md) · [计划](../plans/2026-10-09-windows-sandbox-bootstrap.md) · [实现说明](../implementation/2026-10-09-windows-sandbox-bootstrap-implementation-design.md) · [账本](../progress/2026-10-09-windows-sandbox-bootstrap-ledger.md)。
