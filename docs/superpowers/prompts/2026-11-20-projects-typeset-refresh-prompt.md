# 本地项目页字阶与会话态收敛执行提示词

日期：2026-11-20；主题：`projects-typeset-refresh`；修订：R2；模式：`mode=default`。

```text
仓库：F:/java/cm-agent。按照 AGENTS.md 和清单 docs/superpowers/checklists/2026-11-20-projects-typeset-refresh-checklist.md 的 T0–T6，优化 /console/v2/projects.html 的字阶、真实会话态，修复文件面板的 DOM 嵌套，并将项目会话移动到左侧项目列表下方。

先读取当前工作树、PRODUCT.md、DESIGN.md、目标页面、local-projects.css、project-development.js、相关测试及上一轮布局工作包。保留已有未提交修改，不覆盖无关内容。沿用“受控工作台”的 Operate 视觉世界，只调整字体角色、信息顺序、消息排布和输入区密度；不得改变接口、权限、审批、审计、运行状态机或文件访问语义。

模式为 mode=default；根据当前证据自主决策并记录假设。建立固定产品字阶，并进入真实项目会话检查最近会话、状态、审批、消息、输入区、运行操作和窄屏表单。会话应体现“会话头 → 运行/审批状态 → 消息记录 → 输入区 → 运行操作”，消息与输入区需要清晰分离；输入区授权说明在桌面紧凑并列、窄屏单列。

按依赖顺序实施并扩展现有测试，执行 Node、JDK 21 Maven、Impeccable type/layout 检测和 1440px/1024px/390px 登录页面截图检查。实现说明只记录实际改动与设计差异；状态、命令结果、截图证据和限制只写账本。未获额外指令不提交、推送、部署或重启服务。最终按 AGENTS.md 输出。

R1 复核已部署页面时，必须同时检查消息文本是否含 `U+FFFD`，以及文件面板的 DOM 父节点。字符本身正常而文件区出现在详情面板外时，按 HTML 容器闭合错误修复，不得猜测为编码问题。

R2 中左侧导航顺序必须为项目列表、当前项目会话列表；新建会话入口默认收起，右栏只承载当前会话内容或文件内容。点击会话要自动切回会话视图，并清楚标记当前会话。
```

提示词是任务输入，保存本文件不授予额外执行权限。

[清单](../checklists/2026-11-20-projects-typeset-refresh-checklist.md) · [提示词](../prompts/2026-11-20-projects-typeset-refresh-prompt.md) · [设计](../specs/2026-11-20-projects-typeset-refresh-design.md) · [计划](../plans/2026-11-20-projects-typeset-refresh.md) · [实现说明](../implementation/2026-11-20-projects-typeset-refresh-implementation-design.md) · [账本](../progress/2026-11-20-projects-typeset-refresh-ledger.md)
