# 控制台运行输出 Markdown 渲染实施计划

## 任务拆分

1. 检查流式输出、历史详情、资源测试和已有脚本安全约束。
2. 以 DOM API 实现受限 Markdown 块级与行内渲染。
3. 将流式输出和历史输出容器替换为 Markdown 容器，并补充局部样式。
4. 更新资源测试、设计文档和进度账本，执行语法与模块验证。

## 涉及文件

- `cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`
- `cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css`
- `cm-agent-console/src/test/java/com/cmagent/console/ConsoleResourceTest.java`
- 本组设计、计划、实现说明与进度账本。

## 实现顺序

安全渲染器 → 流式与历史详情接入 → Markdown 样式 → 资源测试 → 验证与文档。

## 验证方式

- 使用 JDK 21 执行 `mvn -pl cm-agent-console test`。
- 执行 `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`。
- 执行 `git diff --check`，确认没有空白错误。

设计依据见 [2026-08-20-console-run-markdown-design.md](../specs/2026-08-20-console-run-markdown-design.md)。
