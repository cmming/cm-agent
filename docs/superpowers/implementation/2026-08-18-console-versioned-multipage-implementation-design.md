# 控制台版本化多页面实现说明

## 最终实现

原始 `META-INF/resources/index.html` 未修改，服务端通过 `ConsoleController#legacyV1` 将其稳定暴露为 `/console/v1/`。根路径和无文件名的 v2 路径由 `ConsoleController#latest` 使用 302 跳转到 `/console/v2/login.html`，以后调整默认版本时不需要覆盖历史 HTML。

v2 新增六个完整 HTML 文档，每个业务页面只包含自身业务区域，侧边导航使用真实链接。公共视觉继续复用 `/assets/styles.css`，`multipage.css` 只补充版本标识、链接态和多页面差异，避免复制旧版样式。

## 共享脚本调用链

`app.js` 在启动时读取 `body[data-console-version]` 和 `body[data-page]`：

1. v1 没有版本属性，执行原有内存令牌、显示/隐藏页面区域和按钮导航逻辑。
2. v2 登录页通过现有 `/api/auth/login` 与 `/api/auth/me` 验证身份；登录响应在保持 JSON 兼容的同时签发 HttpOnly 会话 Cookie，并将 JWT 仅保留在当前文档内存。
3. `loadMultiPage` 获取目标独立 HTML，使用安全 DOM API 替换主体并更新浏览器历史；这使嵌入式浏览器不需要跨文档传递前端状态。业务页直接加载或刷新时仍调用 `/api/auth/me`，由浏览器自动携带 Cookie；未认证或 401 会调用退出端点并带受控 `returnTo` 回到登录页。
4. `loadCurrentPage` 按页面加载最小数据集合：审计页只读审计，运行页读 Agent 与运行，Tool 页读 Agent、Tool 和 LOCAL 示例。
5. `bindPageControls` 仅为当前存在的元素注册监听器，页面导航选择器严格限定为 `button[data-page]` 和 `button[data-navigate]`，不会命中 `body[data-page]`；渲染函数对可选容器提前返回。

## 会话边界

原跨页实现依赖 `sessionStorage`，在实际内置浏览器的跨文档跳转中会丢失。改用 Cookie 后仍发现登录按钮点击会冒泡到宽泛的 `[data-page]` 监听器：该选择器同时命中登录页 `body[data-page="login"]`，因此未认证概览请求会抢先执行并与登录请求竞争。最终修复将选择器限定为真实按钮，并在当前文档内加载目标独立 HTML；已删除 `console/v2/assets/session.js`，共享 `app.js` 不访问任何浏览器存储。

`returnTo` 必须命中 `multiPagePaths` 的精确值，否则回到总览。令牌不会进入查询参数、片段或可读取的页面存储，只在当前 JavaScript 执行上下文中短暂存在；用户名和密码不会写入任何浏览器存储。从 v2 点击 v1 入口时会先调用退出端点清除会话。

## 服务端与安全

`ConsoleSessionCookie` 统一生成和删除 Cookie：路径为 `/api`，启用 `HttpOnly`、`SameSite=Strict`，并根据请求协议决定 `Secure`。Cookie 不设置持久化期限，内部值仍是由 `JwtService` 签名且最长八小时有效的 JWT。

`JwtAuthenticationFilter` 保留 Bearer 兼容性，并只在请求没有显式 `Authorization` 头时回退到控制台 Cookie；无效 Bearer 不会被有效 Cookie 覆盖。前端 API 客户端显式使用 `credentials: "same-origin"`，避免浏览器实现差异导致同源 Cookie 未随请求发送。`SecurityConfig` 只额外公开 `POST /api/auth/logout`，使损坏会话也能删除。业务 API 仍执行同一 JWT 校验、PermissionEvaluator、多租户过滤和审计流程。

## 测试覆盖

- `ConsoleResourceTest` 验证 v1 资源继续存在、v2 六页彼此独立、页面只加载公共脚本、不存在浏览器令牌存储，并回归锁定按钮级导航选择器。
- Node 测试固定 v2 调用 `/api/auth/logout`、业务页通过 `/api/auth/me` 恢复会话，以及脚本和页面不引用 `sessionStorage`、`localStorage` 或旧会话模块；原有 Tool 表单、并发门控和错误格式化测试继续运行。
- `AuthControllerTest` 覆盖 Cookie 跨页认证、`HttpOnly`/`SameSite`/Path、HTTPS `Secure`、退出过期、无效登录不签发，以及显式 Bearer 优先级。
- `ConsoleSmokeTest` 覆盖根路径 302、v1 HTML 和 v2 静态页面公开访问。该测试依赖 Testcontainers，按项目规则应在 Rocky Linux 容器环境执行。
- 本地浏览器验收覆盖真实 `localhost:8080` 的 v2 登录、Agent、Tool、运行、审计、总览、硬刷新、退出和未登录保护。

## 与原方案差异

最终实现没有为每个页面复制一套 JavaScript，而是保留共享业务脚本并增加页面感知初始化。这样可以让 Tool 的复杂参数树、并发门控和错误处理继续只有一处实现，同时 HTML 已真正拆分为独立文档。

初版将标准 `sessionStorage` 语义作为运行前提，但内置浏览器实测证明跨文档跳转后会话丢失。后续 Cookie 方案解决了刷新认证，但真实 8080 复测进一步发现宽泛事件选择器造成的登录竞争。最终方案同时采用 HttpOnly Cookie、当前文档内存令牌、独立 HTML 动态加载和按钮级事件绑定；查询参数版本 `v=2.0.7` 用于让浏览器获取修复后的静态脚本。

## 数据与兼容性影响

- 无数据库、Flyway、既有 REST 字段或配置键变化；新增 `POST /api/auth/logout`。
- v1 固定路径新增，原始单页资源保留。
- 根路径从直接返回 v1 HTML 改为跳转 v2 登录页，依赖根页面 200 响应的外部探针需要改用 `/actuator/health` 或固定版本路径。

## 关联文档

- [设计说明](../specs/2026-08-18-console-versioned-multipage-design.md)
- [实施计划](../plans/2026-08-18-console-versioned-multipage.md)
- [进度账本](../progress/2026-08-18-console-versioned-multipage-ledger.md)
