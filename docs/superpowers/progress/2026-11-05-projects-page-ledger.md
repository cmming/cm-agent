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

## 全量核定记录（2026-11-13）

- 核定结论：待续。本记录只核定工作包责任及归档分类，前文任务、命令结果和提交状态保留形成时含义。
- 责任范围与依据：真实认证远端项目完整操作与修复后局域网浏览器复测未验收。
- 证据限制：依据原文明确的工作范围、状态表、验收和遗留记录核定；本轮没有重跑业务测试、恢复旧服务、访问原数据库或重新证明历史外部环境。历史通过只适用于当时版本和环境。
- 后继与剩余事项：上述剩余事项继续由原任务/验收范围跟踪；本次不执行它们，不依据后来相关主题自动宣布原验收通过，也不补造取消或移交。
- 复核入口：[docs 全量整理账本](2026-11-13-docs-full-review-ledger.md#逐组核定快照)。本次整理不授予提交、推送、部署或外部副作用权限；同一需求恢复时保留本记录并追加恢复及修订。
