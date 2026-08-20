# 控制台工具调用载荷展示优化实施计划

## 任务拆分

1. 检查工具调用的服务端字段、脱敏边界、前端渲染函数与现有样式。
2. 重构工具调用卡片，拆分元数据、请求输入、执行输出和错误区域。
3. 增加 JSON 自动格式化与小屏响应式样式。
4. 更新资源测试、设计文档和进度账本，完成验证。

## 涉及文件

- `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`
- `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`
- 本组设计、计划、实现说明与进度账本。

## 实现顺序

数据展示边界 → 卡片 DOM → JSON 格式化 → 样式与响应式 → 测试与文档。

## 验证方式

- 使用 JDK 21 执行 `mvn -pl cm-agent-console test`。
- 执行 `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`。
- 执行 `git diff --check`。

设计依据见 [2026-08-20-console-tool-call-payload-ui-design.md](../specs/2026-08-20-console-tool-call-payload-ui-design.md)。
