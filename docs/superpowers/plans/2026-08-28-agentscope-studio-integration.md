# AgentScope Studio 本地调试集成实施计划

## 任务拆分

1. 核对当前 AgentScope 2.0.2 的 Studio 扩展 API、全局 Hook 行为和现有运行时装配。
2. 在 server 模块添加 Studio 依赖、属性、条件化初始化及严格 profile 安全保护。
3. 为配置绑定、无效地址和生产拒绝补充测试。
4. 更新 README、配置说明、发布说明和本主题四份过程文档。
5. 使用 JDK 21 编译、测试和差异检查验证。

## 涉及文件

- `cm-agent-server/pom.xml`
- `cm-agent-server/src/main/java/com/cmagent/server/config/AgentScopeStudio*.java`
- `cm-agent-server/src/main/java/com/cmagent/server/security/ProfileSafetyValidator.java`
- `cm-agent-server/src/test/java/com/cmagent/server/config/*Test.java`
- `cm-agent-server/src/main/resources/application-local.yml`
- `cm-agent-server/src/main/resources/application-production.yml`
- `README.md`、`docs/configuration.md`、`docs/release-notes.md`

## 验证方式

1. `java -version` 与 `mvn -v`
2. `mvn -pl cm-agent-server -am test`
3. `git diff --check`
4. `git status --short`

## 关联文档

- [设计说明](../specs/2026-08-28-agentscope-studio-integration-design.md)
- [实现说明](../implementation/2026-08-28-agentscope-studio-integration-implementation-design.md)
- [进度账本](../progress/2026-08-28-agentscope-studio-integration-ledger.md)
