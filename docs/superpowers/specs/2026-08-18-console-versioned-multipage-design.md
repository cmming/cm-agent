# 控制台版本化多页面改造设计

## 背景

现有控制台由 `index.html` 承载登录、总览、Agent、Tool、运行和审计全部区域。单页实现便于早期交付，但页面职责和 URL 无法独立演进；直接覆盖旧页面又会使已有入口失去稳定兼容路径。

## 目标

- 保留原始单页 HTML，不删除、不改写其页面结构。
- 通过显式版本号提供稳定的 v1、v2 访问路径。
- v2 将登录、总览、Agent、Tool、运行和审计拆为独立 HTML 页面。
- 保持现有 API、权限、多租户、审计、错误提示和安全文本渲染行为。
- 支持 v2 在多个独立页面之间延续登录，并避免把 JWT 写入浏览器存储或 URL。

## 范围

- `cm-agent-console` 中的静态 HTML、共享脚本和样式补充。
- `cm-agent-server` 中的默认入口、v1 兼容入口、浏览器会话 Cookie、退出端点和认证过滤规则。
- 控制台资源测试、Node 脚本测试和服务端冒烟测试。
- README、发布说明和本任务过程文档。

## 非目标

- 不修改既有 REST 响应字段、数据库 Schema、Flyway 迁移或领域模型；只新增无响应体的会话退出端点。
- 不引入前端构建框架、模板引擎或新的第三方依赖。
- 不改变 v1 的页面内存令牌模型。
- 不新增多轮会话、流式运行、HITL 或手动取消能力。

## 方案

### 版本入口

- `/`、`/console` 和无文件名的 `/console/v2/` 重定向到 `/console/v2/login.html`。
- `/console/v1/` 和 `/console/v1/index.html` 继续返回原始 `META-INF/resources/index.html`。
- v2 静态资源位于 `META-INF/resources/console/v2`，页面文件名直接表达职责。

### 页面拆分

v2 包含以下独立页面：

- `login.html`：认证入口和 v1 回退链接。
- `overview.html`：能力数量与最近运行。
- `agents.html`：Agent 列表、详情、创建和解除 Tool 关联。
- `tools.html`：Tool 注册、编辑、删除、授权、MCP 发布、LOCAL 示例和调试。
- `runs.html`：Agent 执行、运行历史与工具调用详情。
- `audit.html`：审计日志游标分页。

共享 `app.js` 根据 `body[data-page]` 只绑定当前页面存在的控件并只加载当前页面所需数据，避免拆页后访问缺失 DOM。页面链接在当前文档内获取并替换为目标独立 HTML 的主体，同时使用 History API 保持真实 URL；浏览器直接请求或刷新各 URL 时仍返回完整独立文档。v1 未声明版本属性，继续走原有单页初始化分支。

### 会话与安全

登录接口在保留既有 JSON 令牌响应的同时，签发仅作用于 `/api` 的浏览器会话 Cookie。Cookie 使用 `HttpOnly` 和 `SameSite=Strict`，HTTPS 请求自动增加 `Secure`；不设置持久化有效期，JWT 自身仍保持既有八小时上限。前端不使用 `sessionStorage`、`localStorage` 或 URL 保存 JWT；登录后的令牌只在当前文档内存中用于页面切换期间的兼容认证，刷新时重新依赖 HttpOnly Cookie。

`JwtAuthenticationFilter` 在没有显式认证头时才读取控制台 Cookie；只要存在 `Authorization` 头，就禁止回退到 Cookie，避免无效 Bearer 被旧浏览器会话静默掩盖。`POST /api/auth/logout` 对未认证请求也可用，以便过期或损坏的 Cookie 仍能被删除。退出、认证失效和切换到 v1 都会调用该端点。登录后的 `returnTo` 只接受代码内声明的五个 v2 页面路径，拒绝开放重定向。

## 约束

- v1 与 v2 继续使用纯文本 DOM API 渲染动态数据，不使用 `innerHTML`。
- 页面级绑定只允许匹配真实按钮，禁止使用会同时命中 `body[data-page]` 的宽泛选择器。
- 所有版本页面必须通过既有 Spring Security 公开静态入口，但业务 API 仍要求经过签名和有效期校验的 JWT；Cookie 只是浏览器传输载体。
- 后续发布 v3 时不得覆盖或删除 v1、v2 的稳定页面；默认版本只通过根入口重定向调整。

## 验收标准

- 根路径跳转到 v2 登录页。
- `/console/v1/` 返回原始单页并可正常登录。
- 六个 v2 HTML 均可独立请求，业务页面不包含其他页面的业务区域。
- 未登录访问 v2 业务页面会返回登录页，登录成功后页面跳转和刷新可继续使用会话。
- v2 前端不持久化 JWT、用户名或密码，不使用浏览器存储，`returnTo` 不能跳转到任意地址。
- Node 测试、控制台资源测试、服务端编译和差异检查通过。

## 关联文档

- [实施计划](../plans/2026-08-18-console-versioned-multipage.md)
- [实现说明](../implementation/2026-08-18-console-versioned-multipage-implementation-design.md)
- [进度账本](../progress/2026-08-18-console-versioned-multipage-ledger.md)
