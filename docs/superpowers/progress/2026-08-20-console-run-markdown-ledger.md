# 控制台运行输出 Markdown 渲染进度账本

## 任务状态

- [x] 检查现有流式输出和脚本安全约束。
- [x] 实现受限 Markdown DOM 渲染与链接协议白名单。
- [x] 接入实时输出、历史详情和 Markdown 样式。
- [x] 完成脚本语法、模块测试和差异检查。

## 实际验证结果

- `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`：通过。
- `mvn -pl cm-agent-console test`：通过，12 个测试全部通过（JDK 21）。
- `git diff --check`：通过，无空白错误；仅输出已有工作区文件的换行符提示。

## 遗留问题

- 不涉及数据库迁移、Docker 或 Testcontainers，无需 Rocky Linux 容器验证。

## 提交信息

未提交。
