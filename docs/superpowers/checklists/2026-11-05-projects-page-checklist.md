# Projects 页 2026-11-05 变更清单

> 页面：`/console/v2/projects.html`  
> 日期：2026-11-05  
> 修订：`R1`（2026-11-05）
> 模式：`mode=default`  
> Git 基线：`60d0fd2`

## 需求与范围

合并原 `project-layout-refinement` 与 `local-project-non-loopback-switch`，统一记录 Projects 页的布局调整、非回环安全开关和当前启用配置。

范围：

- 项目管理按需展开，归档项目隐藏管理入口。
- 审批区域移动到任务输入之前。
- 收束会话最大宽度、Agent 选择宽度并调整文件工具按钮换行。
- 新增 `allow-non-loopback`，默认关闭。
- 当前 `local` profile 显式启用本地项目和三个精确来源。
- 局域网 HTTP 访问时，目录选择编号生成不依赖仅安全上下文可用的 `crypto.randomUUID`。

非目标：

- 不改变项目 API、审批逻辑、文件权限或数据库结构。
- 不实现客户端电脑自己的项目目录访问。

## 任务与验收

| 编号 | 状态 | 目标 | 验收 |
|---|---|---|---|
| T0 | 完成 | 评估 Projects 页布局 | 桌面、窄屏和静态布局证据齐全 |
| T1 | 完成 | 调整管理折叠和审批顺序 | 管理入口按需出现，审批先于输入 |
| T2 | 完成 | 收束宽度并验证资源 | Node 与 ConsoleResourceTest 通过 |
| T3 | 完成 | 新增非回环开关 | 默认 false，开启后接受精确来源 |
| T4 | 完成 | 保留请求边界 | 跨源、通配和转发头仍拒绝 |
| T5 | 完成 | 启用当前 local profile | 三个精确来源生效，项目页可从局域网访问 |
| T6 | 完成 | 重启并验证 | Java 21、WARN、健康检查和页面 200 |
| T7 | 完成 | 修复 HTTP 局域网下 randomUUID 不存在 | 使用 getRandomValues 生成 UUID v4，资源版本更新为 1.2.0 |

核心验收：

- A1：归档项目不显示管理入口。
- A2：审批区域位于输入之前，页面无整体横向溢出。
- A3：`allow-non-loopback=false` 时维持原回环限制。
- A4：开启后仍要求 Host、Origin、行动头和 Fetch Metadata。
- A5：当前来源为 `127.0.0.1`、`localhost`、`192.168.0.159`，不含通配。
- A6：生产 profile 仍不能启用本地项目。
- A7：安全上下文优先使用 `randomUUID`；非安全上下文使用 `getRandomValues`。
- A8：浏览器无法生成安全随机数时显示明确错误，不退化为 `Math.random`。

## 关联文档

- [设计](../specs/2026-11-05-projects-page-design.md)
- [计划](../plans/2026-11-05-projects-page.md)
- [提示词](../prompts/2026-11-05-projects-page-prompt.md)
- [实现说明](../implementation/2026-11-05-projects-page-implementation-design.md)
- [账本](../progress/2026-11-05-projects-page-ledger.md)
