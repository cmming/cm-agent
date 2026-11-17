# 控制台版本化多页面进度账本

## 任务状态

- [x] 建立默认 v2 与固定 v1 路由。
- [x] 新增 v2 登录、总览、Agent、Tool、运行、审计六个独立页面。
- [x] 适配共享脚本的页面级初始化和可选 DOM 绑定。
- [x] 复现 v2 登录成功后因跨文档会话丢失而回跳登录页的问题。
- [x] 使用 HttpOnly 会话 Cookie 替换前端会话存储，并补充退出端点。
- [x] 定位并修复登录页 `body[data-page]` 被误绑定为导航按钮所造成的并发回跳。
- [x] 增加当前文档内的独立 HTML 加载和静态脚本版本号 `v=2.0.7`。
- [x] 补充资源、Node 与服务端冒烟测试。
- [x] 更新 README、发布说明和四份任务文档。
- [x] 完成脚本语法、Node、控制台资源、服务端编译和浏览器检查。

## 实际验证结果

- `node --check`：`console-core.js`、`app.js` 通过。
- `node --test cm-agent-console/src/test/js/console-core.test.cjs`：38/38 通过，包含 v2 Cookie 会话调用与前端无令牌存储约束。
- `AuthControllerTest` 12/12、`ConsoleResourceTest` 11/11、`ConsoleControllerRoutingTest` 2/2：JDK 21 下通过；Cookie 安全属性、跨页认证、退出过期和 Bearer 优先级均有回归覆盖。测试通过命令行关闭真实运行时并启用 fake runtime，没有修改工作区现有配置。
- 服务端 `-DskipTests install`：JDK 21 下构建并安装成功。
- 浏览器：先在独立 `18081` test profile 服务上复现并定位竞态，再在真实 `localhost:8080` 上使用当前本地配置完成最终验证。v2 登录成功进入总览，随后依次进入 Agent、Tool、运行、审计和总览五个独立 HTML；硬刷新后仍保持登录。点击退出返回带受控 `returnTo` 的登录页，退出后直接访问 Agent 页面也会返回登录页。
- `git diff --check`：通过，仅有工作区既有换行符提示。

## 未执行或受限验证

- `ConsoleSmokeTest` 使用 PostgreSQL Testcontainers，本次未执行：改动不涉及数据库，且当前变更未提交，无法满足远程工作区提交必须一致的仓库规则。

## 遗留问题

- 根路径现在返回 302；如存在只接受 200 的旧探针，应切换到健康检查或 `/console/v1/`。

## 提交信息

提交说明：`修复控制台 v2 多页面登录与版本化导航`；提交哈希和 PR 地址以 Git 历史及本次发布结果为准。

## 关联文档

- [设计说明](../specs/2026-08-18-console-versioned-multipage-design.md)
- [实施计划](../plans/2026-08-18-console-versioned-multipage.md)
- [实现说明](../implementation/2026-08-18-console-versioned-multipage-implementation-design.md)

## 全量核定记录（2026-11-13）

- 核定结论：已归档。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：多页导航、Cookie 恢复与真实页面刷新/退出验证已记录完成。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：无未关闭的本包事项移交；原记录中的独立后续需求、生产使用条件及范围外限制继续保留，不作为其已实现或已验收的证明。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
