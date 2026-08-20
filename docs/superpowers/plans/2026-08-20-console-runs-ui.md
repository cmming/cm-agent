# 控制台运行页界面优化实施计划

## 任务拆分

1. 检查运行页结构、流式渲染函数、共享样式和资源测试。
2. 调整 `runs.html` 的工作台引导、输入区、历史区和空状态结构，保留既有 DOM 标识。
3. 为运行页补充作用域样式，并改进流式和历史详情的内容分区。
4. 更新控制台资源测试，执行语法、模块测试和差异检查。

## 涉及文件

- `cm-agent-console/src/main/resources/META-INF/resources/console/v2/runs.html`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`
- 本组设计、计划、实现说明与进度账本。

## 实现顺序

页面结构 → 局部样式 → 动态详情分区 → 资源测试 → 验证与文档。

## 验证方式

- 使用 JDK 21 执行 `mvn -pl cm-agent-console test`。
- 执行 `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`。
- 执行 `git diff --check`，确认没有空白错误。

设计依据见 [2026-08-20-console-runs-ui-design.md](../specs/2026-08-20-console-runs-ui-design.md)。
