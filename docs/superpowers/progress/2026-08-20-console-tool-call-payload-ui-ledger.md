# 控制台工具调用载荷展示优化进度账本

## 任务状态

- [x] 确认工具调用字段均为服务端已脱敏摘要。
- [x] 完成工具调用元数据、输入输出和错误卡片布局。
- [x] 实现 JSON 自动格式化与小屏单列布局。
- [x] 完成脚本语法、模块测试和差异检查。

## 实际验证结果

- `node --check cm-agent-console/src/main/resources/META-INF/resources/assets/app.js`：通过。
- `mvn -pl cm-agent-console test`：通过，12 个测试全部通过（JDK 21）。
- `git diff --check`：通过，无空白错误；仅输出已有工作区文件的换行符提示。

## 遗留问题

- 不涉及数据库迁移、Docker 或 Testcontainers，无需 Rocky Linux 容器验证。

## 提交信息

未提交。
