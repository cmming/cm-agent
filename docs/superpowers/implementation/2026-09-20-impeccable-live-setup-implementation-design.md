# Impeccable Live 本地配置实现说明

## 实际实现

新增 `.impeccable/live/config.json`，用 v2 构建资源目录的 HTML glob 作为临时注入目标，注入位置为 `</body>`，语法为 HTML，并标记 CSP 已检查。

## 调用链与边界

浏览器访问本地 Spring Boot 的 `127.0.0.1:8080` 页面；Live 辅助服务只负责加载本地选择器和预览候选。构建目录可能在重新构建时被覆盖，因此接受候选时必须遵循工具的生成文件回退流程，最终源码仍应位于 `cm-agent-console/src/main/resources`。

## 与方案差异

待 Live 实际启动、退出或接受候选后更新本文件和进度账本。

## 关联

- [设计](../specs/2026-09-20-impeccable-live-setup-design.md)
- [计划](../plans/2026-09-20-impeccable-live-setup.md)
- [进度账本](../progress/2026-09-20-impeccable-live-setup-ledger.md)
