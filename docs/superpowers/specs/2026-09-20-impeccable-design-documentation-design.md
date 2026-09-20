# Impeccable 设计系统归档设计

## 背景与目标

项目已有稳定运行的 v2 多页面控制台，但根目录没有 `DESIGN.md`，后续界面生成缺少可复用的视觉约束。用户执行 `impeccable document`，并确认由代理命名整体意象、沿用当前克制风格。

目标是从实际 CSS、HTML 与浏览器计算样式中归档现有设计系统，使后续代理能够延续当前品牌与组件语言。

## 范围与方案

- 扫描 `styles.css`、`multipage.css` 及总览、工具等 v2 页面结构。
- 通过本地运行页面确认桌面端与 390px 窄屏布局，并采样关键计算样式。
- 创建符合 DESIGN.md 标准结构的根目录 `DESIGN.md`，包含真实颜色、字体、圆角、间距与组件令牌。
- 创建 schemaVersion 2 的 `.impeccable/design.json`，保存阴影、动效、断点、色阶和八个可独立渲染的组件样例。

## 非目标与约束

本次不修改页面、接口、样式表、应用配置或产品事实，不修复扫描中发现的界面问题，也不新增不存在的组件。登录凭据只用于本地浏览器验证，不写入任何产物。

## 验收标准

- `DESIGN.md` 使用规范前置令牌与八个固定章节顺序。
- 令牌值能够追溯到当前 CSS 或浏览器计算样式。
- 设计方向为“受控工作台”，并明确保持克制风格。
- sidecar JSON 可解析，组件类名使用 `ds-` 前缀，包含悬停或焦点状态。
- 桌面与窄屏布局事实均有依据，四份过程文档保持一致。

## 关联

- [计划](../plans/2026-09-20-impeccable-design-documentation.md)
- [实现说明](../implementation/2026-09-20-impeccable-design-documentation-implementation-design.md)
- [进度账本](../progress/2026-09-20-impeccable-design-documentation-ledger.md)
