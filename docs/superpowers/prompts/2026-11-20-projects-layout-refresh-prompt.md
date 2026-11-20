# 本地项目页布局优化执行提示词

日期：2026-11-20；主题：`projects-layout-refresh`；修订：R0；模式：`mode=default`。

```text
仓库：F:/java/cm-agent。按照 AGENTS.md 和清单 docs/superpowers/checklists/2026-11-20-projects-layout-refresh-checklist.md 的 T0–T4，优化 /console/v2/projects.html 的页面布局。

先读取当前工作树、PRODUCT.md、DESIGN.md、目标页面、local-projects.css、相关脚本和测试。保留所有既有未提交修改，不回退无关文件。沿用“受控工作台”的 Operate 视觉世界，只调整信息层级、空间关系、响应式结构和必要的缓存版本；不得改变接口、权限、租户、审计、审批或本地文件访问语义，不得替换业务事实文案。

模式为 mode=default；根据现有证据自主决定并记录假设。按依赖顺序推进：先重组页面结构，再收敛本地项目页样式，随后补齐移动端和缓存版本，最后执行 Node、Maven、布局检测和真实登录页面的桌面/中间/窄屏截图检查。视觉检查可使用当前远端页面注入最终静态资源进行，但必须明确这不等于远端部署完成。

实现说明只记录实际代码和文档改动及与设计的差异；任务状态、命令结果、截图证据、限制和提交发布状态只写账本。六份文档保持相互链接。未获额外指令不提交、推送、部署或重启服务。最终按 AGENTS.md 规定输出。
```

提示词是任务输入，保存本文件不授予额外执行权限。

[清单](../checklists/2026-11-20-projects-layout-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-layout-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-layout-refresh-design.md) · [计划](../plans/2026-11-20-projects-layout-refresh.md) · [实现说明](../implementation/2026-11-20-projects-layout-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-layout-refresh-ledger.md)
