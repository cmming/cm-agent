# Projects 页 2026-11-05 变更账本

> 页面：`/console/v2/projects.html`  
> 日期：2026-11-05  
> 修订：`R1`（2026-11-05）
> 模式：`mode=default`  
> Git 基线：`60d0fd2`  
> 提交状态：未提交

## 任务状态

| 编号 | 状态 | 证据 |
|---|---|---|
| T0 | 完成 | 1920、1280、390、320 视口与布局扫描 |
| T1 | 完成 | 管理折叠、归档隐藏、审批前移 |
| T2 | 完成 | Node 40/40；ConsoleResourceTest 16/16；静态无整体横向溢出 |
| T3 | 完成 | 安全开关默认 false，测试覆盖开启路径 |
| T4 | 完成 | 精确同源通过；跨源、通配和转发头拒绝 |
| T5 | 完成 | local profile 三个精确来源已配置 |
| T6 | 完成 | PID `12488`，Java 21，健康检查和 Projects 页 HTTP 200 |
| T7 | 完成 | randomUUID fallback 已实现，资源版本 1.2.0，Node 41/41、ConsoleResourceTest 16/16 |

## 实际命令

```powershell
node --test cm-agent-console/src/test/js/local-projects.test.cjs cm-agent-console/src/test/js/project-development.test.cjs
mvn -q -pl cm-agent-console -am -Dtest=ConsoleResourceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl cm-agent-server -am "-Dtest=LocalProjectConfigurationTest,LocalProjectRequestBoundaryTest,LocalProjectControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## 当前安全状态

- `server.address=0.0.0.0`
- `local-projects.enabled=true`
- `allow-non-loopback=true`
- 精确来源：127.0.0.1、localhost、192.168.0.159

## 遗留

- 真实认证后的远端项目完整操作未验收。
- 当前为 HTTP，必须限制来源并升级 HTTPS。
- 修复后尚未重启 8080 在真实局域网浏览器复测；当前服务处于停止状态。

## 关联文档

- [清单](../checklists/2026-11-05-projects-page-checklist.md)
- [设计](../specs/2026-11-05-projects-page-design.md)
- [计划](../plans/2026-11-05-projects-page.md)
- [提示词](../prompts/2026-11-05-projects-page-prompt.md)
- [实现说明](../implementation/2026-11-05-projects-page-implementation-design.md)
