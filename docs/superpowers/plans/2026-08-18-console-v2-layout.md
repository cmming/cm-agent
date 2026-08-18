# 控制台 v2 页面布局优化实施计划

## 目标

依据[设计说明](../specs/2026-08-18-console-v2-layout-design.md)，在不改变功能行为的前提下提升 v2 控制台的页面层级和响应式布局。

## 任务拆分

1. 检查 v2 页面、共享样式和既有资源测试，确认可复用的结构与兼容边界。
2. 在 `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/multipage.css` 新增 v2 专属布局规则。
3. 在 `overview.html` 丰富快捷操作信息结构，保留原有三个链接目标。
4. 更新 `ConsoleResourceTest`，覆盖新布局选择器和快捷入口说明。
5. 运行控制台模块测试，并记录验证结果。

## 涉及文件

- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/overview.html`
- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/assets/multipage.css`
- `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`
- 本任务四份 `docs/superpowers` 文档

## 验证方式

- 运行 `mvn -pl cm-agent-console test`。
- 检查修改差异，确认未变更 API、脚本、v1 页面或用户已有配置文件。
