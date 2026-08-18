# 控制台版本化多页面实施计划

## 目标

在不删除原始 `index.html` 的前提下，引入 v1 稳定兼容路径和 v2 六个独立页面，并保持现有控制台功能与安全边界。

## 任务拆分

1. 固定版本 URL
   - 修改 `ConsoleController`，将无版本入口导向 v2。
   - 为原始 `index.html` 增加 `/console/v1/` 兼容映射。
   - 更新 `SecurityConfig`，仅公开控制台静态页面，业务 API 规则不变。
2. 创建 v2 页面
   - 新增登录、总览、Agent、Tool、运行、审计六个 HTML。
   - 新增多页面补充样式，页面本身不保存跨页令牌。
   - 使用真实链接建立页面间导航和 v1 回退入口。
3. 适配共享脚本
   - 按 `data-page` 选择初始化和数据加载范围。
   - 对可选 DOM 进行显式存在性判断。
   - 为登录、退出、未认证跳转和受控 `returnTo` 增加 v2 分支。
   - 将页面控件选择器限定为 `button[data-page]`，避免登录页的 `body[data-page]` 被误绑定。
   - 使用当前文档 HTML 替换和 History API 保持独立页面 URL，兼容跨文档状态受限的浏览器。
4. 修复跨文档登录会话
   - 登录成功时签发作用于 `/api` 的 HttpOnly 会话 Cookie。
   - JWT 过滤器在没有显式认证头时读取并验证 Cookie，显式 Bearer 始终优先。
   - 新增公开退出端点统一删除 Cookie，移除 `sessionStorage` 会话脚本。
5. 自动化验证
   - 扩展 `ConsoleResourceTest`，固定页面独立性和存储边界。
   - 扩展 Node 与 Java 测试，验证前端不保存令牌、Cookie 安全属性、跨页认证、退出删除和认证来源优先级。
   - 更新 `ConsoleSmokeTest`，覆盖默认跳转、v1 与 v2 静态资源。
   - 在真实 `localhost:8080` 上覆盖登录、五个业务页面、硬刷新、退出和未登录保护。
6. 文档与交付
   - 更新 README 和发布说明。
   - 生成同主题的设计、计划、实现和进度四份中文文档。

## 实现顺序

先建立版本入口和独立 HTML，再适配共享脚本；随后补测试与文档，最后执行脚本测试、Maven 编译、浏览器验收和差异检查。

## 验证方式

- `node --check` 检查共享脚本语法。
- `node --test cm-agent-console/src/test/js/console-core.test.cjs`。
- `mvn -pl cm-agent-console -am "-Dtest=ConsoleResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`。
- `mvn -pl cm-agent-server -am "-DskipTests" package`。
- 在 test profile 启动本地服务，检查默认入口、v2 登录页和 v1 登录/总览。
- `git diff --check` 与 `git status --short` 复核任务边界。

## 涉及文件

- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/**`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`
- `cm-agent-console/src/test/js/console-core.test.cjs`
- `cm-agent-server/src/main/java/com/cmagent/server/web/ConsoleController.java`
- `cm-agent-server/src/main/java/com/cmagent/server/web/AuthController.java`
- `cm-agent-server/src/main/java/com/cmagent/server/security/ConsoleSessionCookie.java`
- `cm-agent-server/src/main/java/com/cmagent/server/security/JwtAuthenticationFilter.java`
- `cm-agent-server/src/main/java/com/cmagent/server/security/SecurityConfig.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/AuthControllerTest.java`
- `cm-agent-server/src/test/java/com/cmagent/server/web/ConsoleSmokeTest.java`
- `README.md`、`docs/release-notes.md` 与本主题四份文档

## 关联文档

- [设计说明](../specs/2026-08-18-console-versioned-multipage-design.md)
- [实现说明](../implementation/2026-08-18-console-versioned-multipage-implementation-design.md)
- [进度账本](../progress/2026-08-18-console-versioned-multipage-ledger.md)
