# Impeccable Live 本地配置设计

## 背景与目标

用户请求启动 `impeccable live`，为本地运行的 CM Agent v2 控制台提供浏览器内元素选择和设计候选预览。

## 方案

本地 Spring Boot 服务实际提供 `cm-agent-console/target/classes/META-INF/resources/console/v2/` 下的构建资源，因此 Live 注入目标使用该目录下的全部 v2 HTML 页面。接受候选时，生成目录不作为真实源码；工具的生成文件保护与回退流程负责将最终改动引导至 `src/main/resources`。

## 约束与验收

- 仅适用于本机 `127.0.0.1` 调试服务，不用于生产页面。
- `detect-csp` 返回无 CSP，配置记录 `cspChecked: true`，不修改服务端安全策略。
- Live 配置使用 `</body>` HTML 注入点，覆盖七个 v2 业务页面与登录页。
- 停止 Live 时必须移除临时注入脚本；配置文件保留供后续本地会话复用。

## 关联

- [计划](../plans/2026-09-20-impeccable-live-setup.md)
- [实现说明](../implementation/2026-09-20-impeccable-live-setup-implementation-design.md)
- [进度账本](../progress/2026-09-20-impeccable-live-setup-ledger.md)
