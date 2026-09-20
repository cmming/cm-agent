# Impeccable 设计系统归档实现说明

## 实际实现

- `DESIGN.md`：新增 CM Agent v2 视觉系统权威记录。前置元数据保存颜色、排版、圆角、间距和组件令牌；正文按 Overview、Colors、Typography、Layout、Elevation & Depth、Shapes、Components、Do's and Don'ts 的固定顺序描述使用规则。
- `.impeccable/design.json`：新增 schemaVersion 2 sidecar，保存 15 个颜色元数据及色阶、5 个排版角色、4 个阴影、2 个动效规则、4 个断点和 8 个组件样例。
- 创意北极星为“受控工作台”，组件和布局保持用户确认的克制风格。

## 证据与映射

原始令牌来自 `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`；v2 最终覆盖、布局和断点来自 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/multipage.css`；页面结构来自 v2 HTML。浏览器在 1440×1000 总览页及 390×844 工具页确认了最终表现，并采样了面板、按钮、字段、资源项和状态徽标的计算样式。

## 影响与差异

只新增设计文档与 Impeccable sidecar，不改变页面、JavaScript、接口、配置或数据库。`impeccable context` 未自动识别服务端静态页面，因此改用仓库与真实浏览器证据进入扫描模式；这不会改变输出格式。

## 关联

- [设计](../specs/2026-09-20-impeccable-design-documentation-design.md)
- [计划](../plans/2026-09-20-impeccable-design-documentation.md)
- [进度账本](../progress/2026-09-20-impeccable-design-documentation-ledger.md)
