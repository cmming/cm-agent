# 控制台运行输出 Markdown 渲染实现说明

## 实际实现

`app.js` 新增受限 Markdown 渲染器。它使用 `DocumentFragment`、`createElement`、`textContent` 和文本节点构造输出，支持标题、段落、分隔线、无序/有序列表、引用、围栏代码块、GFM 风格表格、行内代码、粗体、斜体、删除线和链接。

链接通过 `URL` 解析并限制为 `http:`、`https:`、`mailto:` 或页内锚点；其他协议不会生成可点击链接。模型给出的 HTML 没有任何特殊解析路径，会作为普通文本显示。该实现不依赖外部脚本，也不使用 `innerHTML`。

流式输出使用内存中的当前 Markdown 原文缓存。每个 SSE 文本片段到达后重新渲染整个输出容器，确保分片正好落在 Markdown 标记内部时，待标记闭合后仍能正确格式化。历史详情同样复用该渲染器，原始输出的持久化值不受影响。

`styles.css` 新增 Markdown 内容、代码块、引用和表格的局部样式，使渲染结果在运行详情卡片中可读且可横向滚动查看表格。

## 与原方案的差异

无范围差异。没有引入通用第三方 Markdown 库，以保持静态控制台无额外加载链路，并能明确限制 HTML 与链接安全边界。

相关设计见 [2026-08-20-console-run-markdown-design.md](../specs/2026-08-20-console-run-markdown-design.md)，任务清单见 [2026-08-20-console-run-markdown.md](../plans/2026-08-20-console-run-markdown.md)。
