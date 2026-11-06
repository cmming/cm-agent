# Projects 页 2026-11-05 变更计划

> 页面：`/console/v2/projects.html`  
> 日期：2026-11-05  
> 修订：`R1`（2026-11-05）
> 模式：`mode=default`

## 实施顺序

| 编号 | 操作 | 验证 |
|---|---|---|
| T0 | 检查现有布局与资源 | 桌面和移动端基线 |
| T1 | 调整管理折叠和审批顺序 | 交互断言 |
| T2 | 收束会话/Agent 宽度并处理按钮换行 | Node、ConsoleResourceTest、视口检查 |
| T3 | 增加 `allow-non-loopback` 属性 | 默认关闭测试 |
| T4 | 接入部署校验和请求边界 | 非回环、跨源、通配和转发头测试 |
| T5 | 配置 local profile 三个精确来源 | 启动日志和配置审核 |
| T6 | 使用 JDK 21 重启 | 健康检查和 Projects 页面 HTTP 200 |
| T7 | 增加非安全上下文 UUID fallback | getRandomValues 生成 UUID v4，更新 1.2.0 资源版本 |

## 完成标准

- 页面布局和交互符合验收。
- 安全开关默认值不变，开启路径受控。
- 当前局域网入口可访问且未认证 API 仍返回 401。
- 局域网 HTTP 页面不再因 `randomUUID` 缺失抛出 TypeError。

## 关联文档

- [清单](../checklists/2026-11-05-projects-page-checklist.md)
- [设计](../specs/2026-11-05-projects-page-design.md)
- [提示词](../prompts/2026-11-05-projects-page-prompt.md)
- [实现说明](../implementation/2026-11-05-projects-page-implementation-design.md)
- [账本](../progress/2026-11-05-projects-page-ledger.md)
