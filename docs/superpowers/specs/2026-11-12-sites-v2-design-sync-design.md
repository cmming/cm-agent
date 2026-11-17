# V2 设计同步到 Sites：设计

日期：2026-11-12；mode=default。

## 背景与目标

将当前 V2 页面外观同步到现有 CM Agent 运营控制台 Sites，便于查看页面布局。当前仓库 V2 有 11 个 HTML 入口，工作树存在未提交页面修改；以当前磁盘内容作为本次快照。

## 范围与约束

同步页面结构、样式和图标，保留页面间导航以及静态预览提示。站点不连接真实 CM Agent，不将静态数据或系统状态表述为实时数据；禁止输入凭据或触发业务请求。保留原有仅所有者访问范围，不修改服务端、权限或数据库。

## 方案与验收

T0 核对站点、源码和基线；T1 将 V2 HTML 和样式资源映射到站点静态目录，替换运行时脚本为预览拦截脚本；T2 检查 11 个入口的资源和浏览器代表页面后发布；T3 记录六份工作包。登录页提供进入静态预览的链接。移动端和真实后端不在本次已验证范围。

关联：[清单](../checklists/2026-11-12-sites-v2-design-sync-checklist.md)、[提示词](../prompts/2026-11-12-sites-v2-design-sync-prompt.md)、[计划](../plans/2026-11-12-sites-v2-design-sync.md)、[实现](../implementation/2026-11-12-sites-v2-design-sync-implementation-design.md)、[账本](../progress/2026-11-12-sites-v2-design-sync-ledger.md)。
