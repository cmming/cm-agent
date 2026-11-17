# V2 设计同步到 Sites：清单

日期：2026-11-12；工作模式：mode=default。仓库基线 `f21a7b1da05e32a484275a1280bab4c6a77ee190`；工作树存在既有未提交修改，本次以磁盘上的 V2 页面为准。

## 范围与证据

用户要求将 V2 HTML 页面设计同步到 CM Agent 运营控制台 Sites。页面和资源位于 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/`；站点已有静态预览，因此保留未连接服务的定位。假设：此次为设计同步，不连接真实后端，不改变站点访问范围。移动端实际浏览器效果尚未验证。

| 编号 | 状态 | 目标与涉及位置 | 依赖 | 验收及证据 |
|---|---|---|---|---|
| T0 | 完成 | 核对仓库页面、站点源码和访问范围 | 无 | 读取 V2 HTML、模块 POM、README 和站点配置 |
| T1 | 完成 | 同步 11 个页面与对应样式到 Sites 静态源码 | T0 | 静态页面资源检查 11 页、0 缺失；仅加载预览脚本 |
| T2 | 完成 | 保持未连接状态提示，验证并发布 | T1 | 本地浏览器核对总览和本机客户端；私有部署返回 succeeded；移动端未验收 |
| T3 | 完成 | 维护六份工作包并记录结果 | T0–T2 | 本组六份文档齐全，仓库改动未提交 |

关联：[提示词](../prompts/2026-11-12-sites-v2-design-sync-prompt.md)、[设计](../specs/2026-11-12-sites-v2-design-sync-design.md)、[计划](../plans/2026-11-12-sites-v2-design-sync.md)、[实现](../implementation/2026-11-12-sites-v2-design-sync-implementation-design.md)、[账本](../progress/2026-11-12-sites-v2-design-sync-ledger.md)。
