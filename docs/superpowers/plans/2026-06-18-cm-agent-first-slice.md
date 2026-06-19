# CM Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 CM Agent 第一阶段薄纵切：多模块骨架、核心接口、Starter、持久化迁移、认证/RBAC、Agent 和工具治理、fake runtime 运行链路、AgentScope 适配模块、审计、最小控制台、示例和中文生产文档。

**Architecture:** 使用 Maven 多模块的模块化单体。`cm-agent-core` 定义稳定接口，`cm-agent-server` 提供 REST API，`cm-agent-spring-boot-starter` 提供嵌入式集成，`cm-agent-agentscope-adapter` 隔离 AgentScope Java 依赖，`cm-agent-console` 以 classpath 静态资源提供薄控制台。

**Tech Stack:** Java 21、Maven、Spring Boot 3.5.0、Spring Security、Spring JDBC、Flyway、JJWT 0.13.0、Testcontainers 2.0.5、JUnit 5、MySQL、PostgreSQL、AgentScope Java 2.0.0-RC3、原生 HTML/CSS/JavaScript。

---

## 版本依据

- Spring Boot：Maven Central 当前可见 `org.springframework.boot:spring-boot` 已有 4.1.0；本计划选择 Spring Boot 3.5.0，优先保障 Spring Boot 3.x 生态稳定性和 Java 企业项目兼容性。
- Testcontainers：Maven Central 当前可见 `org.testcontainers:testcontainers-bom` 为 2.0.5，本计划使用 2.0.5。
- JJWT：Maven Central 当前可见 `io.jsonwebtoken:jjwt-api` 为 0.13.0，本计划使用 0.13.0。
- AgentScope Java：spec 已确认以 Maven Central 可见的 `2.0.0-RC3` 作为初始候选版本。

## 文件结构总览

- 创建：`F:\java\cm-agent\.gitignore`
- 创建：`F:\java\cm-agent\pom.xml`
- 创建：`F:\java\cm-agent\README.md`
- 创建：`F:\java\cm-agent\LICENSE`
- 创建：`F:\java\cm-agent\docker-compose.yml`
- 创建：`F:\java\cm-agent\docs\deployment.md`
- 创建：`F:\java\cm-agent\docs\operations.md`
- 创建：`F:\java\cm-agent\docs\configuration.md`
- 创建：`F:\java\cm-agent\docs\release-notes.md`
- 创建：`F:\java\cm-agent\cm-agent-api\pom.xml`
- 创建：`F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\ApiErrorCode.java`
- 创建：`F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\ApiPageRequest.java`
- 创建：`F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\ApiPageResponse.java`
- 创建：`F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\PrincipalRef.java`
- 创建：`F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\TenantContext.java`
- 创建：`F:\java\cm-agent\cm-agent-core\pom.xml`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\audit\AuditEvent.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\audit\AuditEventRepository.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\AgentDefinition.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\AgentRunRequest.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\AgentRunResult.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ModelConfig.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ModelProviderType.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\RunStatus.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolCallRecord.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolDefinition.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolGrant.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolRiskLevel.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolType.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\runtime\AgentRuntime.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\runtime\FakeAgentRuntime.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\AuthorizationDecision.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\DefaultPermissionEvaluator.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\DefaultToolAuthorizationPolicy.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\PermissionEvaluator.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\ToolAuthorizationPolicy.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\InMemoryToolRegistry.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\ToolExecutionRequest.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\ToolExecutionResult.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\ToolExecutor.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\ToolRegistry.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\domain\AgentDefinitionTest.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\runtime\FakeAgentRuntimeTest.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\security\DefaultPermissionEvaluatorTest.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\security\DefaultToolAuthorizationPolicyTest.java`
- 创建：`F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\tool\InMemoryToolRegistryTest.java`
- 创建：`F:\java\cm-agent\cm-agent-persistence\pom.xml`
- 创建：`F:\java\cm-agent\cm-agent-persistence\src\main\resources\db\migration\V1__init_schema.sql`
- 创建：`F:\java\cm-agent\cm-agent-persistence\src\main\java\com\cmagent\persistence\JdbcAuditEventRepository.java`
- 创建：`F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\JdbcAuditEventRepositoryTest.java`
- 创建：`F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\MigrationTest.java`
- 创建：`F:\java\cm-agent\cm-agent-spring-boot-starter\pom.xml`
- 创建：`F:\java\cm-agent\cm-agent-spring-boot-starter\src\main\java\com\cmagent\starter\CmAgentAutoConfiguration.java`
- 创建：`F:\java\cm-agent\cm-agent-spring-boot-starter\src\main\java\com\cmagent\starter\CmAgentProperties.java`
- 创建：`F:\java\cm-agent\cm-agent-spring-boot-starter\src\main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- 创建：`F:\java\cm-agent\cm-agent-spring-boot-starter\src\test\java\com\cmagent\starter\CmAgentAutoConfigurationTest.java`
- 创建：`F:\java\cm-agent\cm-agent-server\pom.xml`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\CmAgentServerApplication.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\JwtService.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\JwtAuthenticationFilter.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\LoginRequest.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\LoginResponse.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\SecurityConfig.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\store\InMemoryPlatformStore.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\AgentController.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\AuditController.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\AuthController.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\RunController.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\ToolController.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\main\resources\application.yml`
- 创建：`F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\AuthControllerTest.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\ConsoleSmokeTest.java`
- 创建：`F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\RunControllerTest.java`
- 创建：`F:\java\cm-agent\cm-agent-console\pom.xml`
- 创建：`F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\index.html`
- 创建：`F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\assets\app.js`
- 创建：`F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\assets\styles.css`
- 创建：`F:\java\cm-agent\cm-agent-agentscope-adapter\pom.xml`
- 创建：`F:\java\cm-agent\cm-agent-agentscope-adapter\src\main\java\com\cmagent\agentscope\AgentScopeRunSpec.java`
- 创建：`F:\java\cm-agent\cm-agent-agentscope-adapter\src\main\java\com\cmagent\agentscope\AgentScopeRuntimeAdapter.java`
- 创建：`F:\java\cm-agent\cm-agent-agentscope-adapter\src\test\java\com\cmagent\agentscope\AgentScopeRuntimeAdapterTest.java`
- 创建：`F:\java\cm-agent\cm-agent-examples\pom.xml`
- 创建：`F:\java\cm-agent\cm-agent-examples\starter-local-tool\pom.xml`
- 创建：`F:\java\cm-agent\cm-agent-examples\starter-local-tool\src\main\java\com\cmagent\examples\LocalToolExampleApplication.java`

## Task 1: Maven 多模块骨架和中文生产文档外壳

**Files:**
- Create: `F:\java\cm-agent\.gitignore`
- Create: `F:\java\cm-agent\LICENSE`
- Create: `F:\java\cm-agent\pom.xml`
- Create: `F:\java\cm-agent\README.md`
- Create: `F:\java\cm-agent\cm-agent-api\pom.xml`
- Create: `F:\java\cm-agent\cm-agent-core\pom.xml`
- Create: `F:\java\cm-agent\cm-agent-persistence\pom.xml`
- Create: `F:\java\cm-agent\cm-agent-spring-boot-starter\pom.xml`
- Create: `F:\java\cm-agent\cm-agent-server\pom.xml`
- Create: `F:\java\cm-agent\cm-agent-console\pom.xml`
- Create: `F:\java\cm-agent\cm-agent-agentscope-adapter\pom.xml`
- Create: `F:\java\cm-agent\cm-agent-examples\pom.xml`
- Create: `F:\java\cm-agent\cm-agent-examples\starter-local-tool\pom.xml`

- [ ] **Step 1: 写入多模块骨架**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: .gitignore
+.idea/
+target/
+.mvn/wrapper/maven-wrapper.jar
+*.iml
+*.log
+logs/
+.env
+node_modules/
+dist/
+coverage/
*** Add File: LICENSE
+Apache License
+Version 2.0, January 2004
+https://www.apache.org/licenses/LICENSE-2.0
+
+Copyright 2026 CM Agent contributors
+
+本项目使用 Apache License 2.0 授权。完整条款以 Apache Software Foundation 发布的 Apache License 2.0 正文为准。
*** Add File: README.md
+# CM Agent
+
+CM Agent 是一个基于 AgentScope Java 的企业级智能体开源底座。第一阶段提供 Java SDK、Spring Boot Starter、独立服务端、轻量控制台、工具治理、轻量多租户、RBAC 和审计能力。
+
+## 快速开始
+
+```powershell
+mvn -q -DskipTests package
+mvn -pl cm-agent-server spring-boot:run
+```
+
+服务启动后访问：
+
+- 健康检查：`http://localhost:8080/actuator/health`
+- 控制台：`http://localhost:8080/`
+- OpenAPI：`http://localhost:8080/swagger-ui/index.html`
+
+## 第一阶段范围
+
+- Maven 多模块骨架
+- 核心领域模型和接口
+- Spring Boot Starter
+- MySQL 和 PostgreSQL 迁移
+- JWT、RBAC、API Key 基线
+- Agent 配置、工具治理、fake runtime 运行链路
+- 审计日志
+- 最小控制台
+
+## 文档语言
+
+生产文档默认使用中文。英文文档可以作为翻译补充，但不能替代中文文档。
*** Add File: pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+
+    <groupId>com.cmagent</groupId>
+    <artifactId>cm-agent-parent</artifactId>
+    <version>0.1.0-SNAPSHOT</version>
+    <packaging>pom</packaging>
+
+    <name>CM Agent</name>
+    <description>企业级智能体开源底座</description>
+
+    <modules>
+        <module>cm-agent-api</module>
+        <module>cm-agent-core</module>
+        <module>cm-agent-persistence</module>
+        <module>cm-agent-spring-boot-starter</module>
+        <module>cm-agent-console</module>
+        <module>cm-agent-agentscope-adapter</module>
+        <module>cm-agent-server</module>
+        <module>cm-agent-examples</module>
+    </modules>
+
+    <properties>
+        <java.version>21</java.version>
+        <maven.compiler.release>21</maven.compiler.release>
+        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
+        <spring-boot.version>3.5.0</spring-boot.version>
+        <testcontainers.version>2.0.5</testcontainers.version>
+        <jjwt.version>0.13.0</jjwt.version>
+        <agentscope.version>2.0.0-RC3</agentscope.version>
+    </properties>
+
+    <dependencyManagement>
+        <dependencies>
+            <dependency>
+                <groupId>org.springframework.boot</groupId>
+                <artifactId>spring-boot-dependencies</artifactId>
+                <version>${spring-boot.version}</version>
+                <type>pom</type>
+                <scope>import</scope>
+            </dependency>
+            <dependency>
+                <groupId>org.testcontainers</groupId>
+                <artifactId>testcontainers-bom</artifactId>
+                <version>${testcontainers.version}</version>
+                <type>pom</type>
+                <scope>import</scope>
+            </dependency>
+            <dependency>
+                <groupId>io.jsonwebtoken</groupId>
+                <artifactId>jjwt-api</artifactId>
+                <version>${jjwt.version}</version>
+            </dependency>
+            <dependency>
+                <groupId>io.jsonwebtoken</groupId>
+                <artifactId>jjwt-impl</artifactId>
+                <version>${jjwt.version}</version>
+            </dependency>
+            <dependency>
+                <groupId>io.jsonwebtoken</groupId>
+                <artifactId>jjwt-jackson</artifactId>
+                <version>${jjwt.version}</version>
+            </dependency>
+        </dependencies>
+    </dependencyManagement>
+
+    <build>
+        <pluginManagement>
+            <plugins>
+                <plugin>
+                    <groupId>org.apache.maven.plugins</groupId>
+                    <artifactId>maven-compiler-plugin</artifactId>
+                    <version>3.13.0</version>
+                    <configuration>
+                        <release>${maven.compiler.release}</release>
+                    </configuration>
+                </plugin>
+                <plugin>
+                    <groupId>org.springframework.boot</groupId>
+                    <artifactId>spring-boot-maven-plugin</artifactId>
+                    <version>${spring-boot.version}</version>
+                </plugin>
+            </plugins>
+        </pluginManagement>
+    </build>
+</project>
*** Add File: cm-agent-api/pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+    <parent>
+        <groupId>com.cmagent</groupId>
+        <artifactId>cm-agent-parent</artifactId>
+        <version>0.1.0-SNAPSHOT</version>
+    </parent>
+    <artifactId>cm-agent-api</artifactId>
+    <name>CM Agent API</name>
+</project>
*** Add File: cm-agent-core/pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+    <parent>
+        <groupId>com.cmagent</groupId>
+        <artifactId>cm-agent-parent</artifactId>
+        <version>0.1.0-SNAPSHOT</version>
+    </parent>
+    <artifactId>cm-agent-core</artifactId>
+    <name>CM Agent Core</name>
+    <dependencies>
+        <dependency>
+            <groupId>com.cmagent</groupId>
+            <artifactId>cm-agent-api</artifactId>
+            <version>${project.version}</version>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-test</artifactId>
+            <scope>test</scope>
+        </dependency>
+    </dependencies>
+</project>
*** Add File: cm-agent-persistence/pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+    <parent>
+        <groupId>com.cmagent</groupId>
+        <artifactId>cm-agent-parent</artifactId>
+        <version>0.1.0-SNAPSHOT</version>
+    </parent>
+    <artifactId>cm-agent-persistence</artifactId>
+    <name>CM Agent Persistence</name>
+    <dependencies>
+        <dependency>
+            <groupId>com.cmagent</groupId>
+            <artifactId>cm-agent-core</artifactId>
+            <version>${project.version}</version>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-jdbc</artifactId>
+        </dependency>
+        <dependency>
+            <groupId>org.flywaydb</groupId>
+            <artifactId>flyway-core</artifactId>
+        </dependency>
+        <dependency>
+            <groupId>org.flywaydb</groupId>
+            <artifactId>flyway-mysql</artifactId>
+        </dependency>
+        <dependency>
+            <groupId>org.postgresql</groupId>
+            <artifactId>postgresql</artifactId>
+            <scope>runtime</scope>
+        </dependency>
+        <dependency>
+            <groupId>com.mysql</groupId>
+            <artifactId>mysql-connector-j</artifactId>
+            <scope>runtime</scope>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-test</artifactId>
+            <scope>test</scope>
+        </dependency>
+        <dependency>
+            <groupId>org.testcontainers</groupId>
+            <artifactId>testcontainers-junit-jupiter</artifactId>
+            <scope>test</scope>
+        </dependency>
+        <dependency>
+            <groupId>org.testcontainers</groupId>
+            <artifactId>testcontainers-postgresql</artifactId>
+            <scope>test</scope>
+        </dependency>
+        <dependency>
+            <groupId>org.testcontainers</groupId>
+            <artifactId>testcontainers-mysql</artifactId>
+            <scope>test</scope>
+        </dependency>
+    </dependencies>
+</project>
*** Add File: cm-agent-spring-boot-starter/pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+    <parent>
+        <groupId>com.cmagent</groupId>
+        <artifactId>cm-agent-parent</artifactId>
+        <version>0.1.0-SNAPSHOT</version>
+    </parent>
+    <artifactId>cm-agent-spring-boot-starter</artifactId>
+    <name>CM Agent Spring Boot Starter</name>
+    <dependencies>
+        <dependency>
+            <groupId>com.cmagent</groupId>
+            <artifactId>cm-agent-core</artifactId>
+            <version>${project.version}</version>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-autoconfigure</artifactId>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-test</artifactId>
+            <scope>test</scope>
+        </dependency>
+    </dependencies>
+</project>
*** Add File: cm-agent-console/pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+    <parent>
+        <groupId>com.cmagent</groupId>
+        <artifactId>cm-agent-parent</artifactId>
+        <version>0.1.0-SNAPSHOT</version>
+    </parent>
+    <artifactId>cm-agent-console</artifactId>
+    <name>CM Agent Console</name>
+</project>
*** Add File: cm-agent-agentscope-adapter/pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+    <parent>
+        <groupId>com.cmagent</groupId>
+        <artifactId>cm-agent-parent</artifactId>
+        <version>0.1.0-SNAPSHOT</version>
+    </parent>
+    <artifactId>cm-agent-agentscope-adapter</artifactId>
+    <name>CM Agent AgentScope Adapter</name>
+    <dependencies>
+        <dependency>
+            <groupId>com.cmagent</groupId>
+            <artifactId>cm-agent-core</artifactId>
+            <version>${project.version}</version>
+        </dependency>
+        <dependency>
+            <groupId>io.agentscope</groupId>
+            <artifactId>agentscope-core</artifactId>
+            <version>${agentscope.version}</version>
+            <optional>true</optional>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-test</artifactId>
+            <scope>test</scope>
+        </dependency>
+    </dependencies>
+</project>
*** Add File: cm-agent-server/pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+    <parent>
+        <groupId>com.cmagent</groupId>
+        <artifactId>cm-agent-parent</artifactId>
+        <version>0.1.0-SNAPSHOT</version>
+    </parent>
+    <artifactId>cm-agent-server</artifactId>
+    <name>CM Agent Server</name>
+    <dependencies>
+        <dependency>
+            <groupId>com.cmagent</groupId>
+            <artifactId>cm-agent-spring-boot-starter</artifactId>
+            <version>${project.version}</version>
+        </dependency>
+        <dependency>
+            <groupId>com.cmagent</groupId>
+            <artifactId>cm-agent-persistence</artifactId>
+            <version>${project.version}</version>
+        </dependency>
+        <dependency>
+            <groupId>com.cmagent</groupId>
+            <artifactId>cm-agent-console</artifactId>
+            <version>${project.version}</version>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-web</artifactId>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-security</artifactId>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-actuator</artifactId>
+        </dependency>
+        <dependency>
+            <groupId>org.springdoc</groupId>
+            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
+            <version>2.8.9</version>
+        </dependency>
+        <dependency>
+            <groupId>io.jsonwebtoken</groupId>
+            <artifactId>jjwt-api</artifactId>
+        </dependency>
+        <dependency>
+            <groupId>io.jsonwebtoken</groupId>
+            <artifactId>jjwt-impl</artifactId>
+            <scope>runtime</scope>
+        </dependency>
+        <dependency>
+            <groupId>io.jsonwebtoken</groupId>
+            <artifactId>jjwt-jackson</artifactId>
+            <scope>runtime</scope>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter-test</artifactId>
+            <scope>test</scope>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.security</groupId>
+            <artifactId>spring-security-test</artifactId>
+            <scope>test</scope>
+        </dependency>
+    </dependencies>
+    <build>
+        <plugins>
+            <plugin>
+                <groupId>org.springframework.boot</groupId>
+                <artifactId>spring-boot-maven-plugin</artifactId>
+            </plugin>
+        </plugins>
+    </build>
+</project>
*** Add File: cm-agent-examples/pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+    <parent>
+        <groupId>com.cmagent</groupId>
+        <artifactId>cm-agent-parent</artifactId>
+        <version>0.1.0-SNAPSHOT</version>
+    </parent>
+    <artifactId>cm-agent-examples</artifactId>
+    <packaging>pom</packaging>
+    <name>CM Agent Examples</name>
+    <modules>
+        <module>starter-local-tool</module>
+    </modules>
+</project>
*** Add File: cm-agent-examples/starter-local-tool/pom.xml
+<?xml version="1.0" encoding="UTF-8"?>
+<project xmlns="http://maven.apache.org/POM/4.0.0"
+         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
+         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
+    <modelVersion>4.0.0</modelVersion>
+    <parent>
+        <groupId>com.cmagent</groupId>
+        <artifactId>cm-agent-examples</artifactId>
+        <version>0.1.0-SNAPSHOT</version>
+    </parent>
+    <artifactId>starter-local-tool</artifactId>
+    <name>CM Agent Starter Local Tool Example</name>
+    <dependencies>
+        <dependency>
+            <groupId>com.cmagent</groupId>
+            <artifactId>cm-agent-spring-boot-starter</artifactId>
+            <version>${project.version}</version>
+        </dependency>
+        <dependency>
+            <groupId>org.springframework.boot</groupId>
+            <artifactId>spring-boot-starter</artifactId>
+        </dependency>
+    </dependencies>
+</project>
*** End Patch
```

- [ ] **Step 2: 验证 Maven reactor 能识别所有模块**

Run:

```powershell
mvn -q -DskipTests validate
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 3: 提交骨架**

Run:

```powershell
git add .gitignore LICENSE README.md pom.xml cm-agent-api cm-agent-core cm-agent-persistence cm-agent-spring-boot-starter cm-agent-console cm-agent-agentscope-adapter cm-agent-server cm-agent-examples
git commit -m "chore: 初始化 CM Agent 多模块骨架"
```

Expected:

```text
输出包含：chore: 初始化 CM Agent 多模块骨架
```

## Task 2: API 契约和核心领域模型

**Files:**
- Create: `F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\ApiErrorCode.java`
- Create: `F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\ApiPageRequest.java`
- Create: `F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\ApiPageResponse.java`
- Create: `F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\PrincipalRef.java`
- Create: `F:\java\cm-agent\cm-agent-api\src\main\java\com\cmagent\api\TenantContext.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\AgentDefinition.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\AgentRunRequest.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\AgentRunResult.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ModelConfig.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ModelProviderType.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\RunStatus.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolCallRecord.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolDefinition.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolGrant.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolRiskLevel.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\domain\ToolType.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\domain\AgentDefinitionTest.java`

- [ ] **Step 1: 先写领域模型测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-core/src/test/java/com/cmagent/core/domain/AgentDefinitionTest.java
+package com.cmagent.core.domain;
+
+import org.junit.jupiter.api.Test;
+
+import java.util.List;
+import java.util.UUID;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+class AgentDefinitionTest {
+
+    @Test
+    void createEnabledAgentWithTenantAndTools() {
+        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
+        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000101");
+
+        AgentDefinition agent = new AgentDefinition(
+                UUID.fromString("00000000-0000-0000-0000-000000000201"),
+                tenantId,
+                "企业助手",
+                "回答企业内部问题",
+                "你是企业业务助手，回答必须简洁并记录工具调用。",
+                UUID.fromString("00000000-0000-0000-0000-000000000301"),
+                "qwen-max",
+                0.2,
+                6,
+                true,
+                List.of(toolId),
+                "admin",
+                "admin"
+        );
+
+        assertThat(agent.tenantId()).isEqualTo(tenantId);
+        assertThat(agent.enabled()).isTrue();
+        assertThat(agent.toolIds()).containsExactly(toolId);
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-core -Dtest=AgentDefinitionTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class AgentDefinition
```

- [ ] **Step 3: 写入 API 和核心领域模型**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-api/src/main/java/com/cmagent/api/ApiErrorCode.java
+package com.cmagent.api;
+
+public enum ApiErrorCode {
+    UNAUTHORIZED,
+    FORBIDDEN,
+    TENANT_NOT_FOUND,
+    AGENT_NOT_FOUND,
+    TOOL_NOT_FOUND,
+    TOOL_NOT_GRANTED,
+    VALIDATION_FAILED,
+    RUNTIME_ERROR
+}
*** Add File: cm-agent-api/src/main/java/com/cmagent/api/ApiPageRequest.java
+package com.cmagent.api;
+
+public record ApiPageRequest(int page, int size) {
+    public ApiPageRequest {
+        if (page < 0) {
+            throw new IllegalArgumentException("页码不能小于 0");
+        }
+        if (size < 1 || size > 200) {
+            throw new IllegalArgumentException("每页数量必须在 1 到 200 之间");
+        }
+    }
+}
*** Add File: cm-agent-api/src/main/java/com/cmagent/api/ApiPageResponse.java
+package com.cmagent.api;
+
+import java.util.List;
+
+public record ApiPageResponse<T>(List<T> items, long total, int page, int size) {
+    public ApiPageResponse {
+        items = List.copyOf(items);
+    }
+}
*** Add File: cm-agent-api/src/main/java/com/cmagent/api/PrincipalRef.java
+package com.cmagent.api;
+
+import java.util.Set;
+import java.util.UUID;
+
+public record PrincipalRef(UUID tenantId, String principalId, String displayName, Set<String> permissions) {
+    public PrincipalRef {
+        permissions = Set.copyOf(permissions);
+    }
+}
*** Add File: cm-agent-api/src/main/java/com/cmagent/api/TenantContext.java
+package com.cmagent.api;
+
+import java.util.UUID;
+
+public record TenantContext(UUID tenantId) {
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/AgentDefinition.java
+package com.cmagent.core.domain;
+
+import java.util.List;
+import java.util.UUID;
+
+public record AgentDefinition(
+        UUID id,
+        UUID tenantId,
+        String name,
+        String description,
+        String systemPrompt,
+        UUID modelProviderId,
+        String modelName,
+        double temperature,
+        int maxIterations,
+        boolean enabled,
+        List<UUID> toolIds,
+        String createdBy,
+        String updatedBy
+) {
+    public AgentDefinition {
+        toolIds = List.copyOf(toolIds);
+        if (temperature < 0 || temperature > 2) {
+            throw new IllegalArgumentException("temperature 必须在 0 到 2 之间");
+        }
+        if (maxIterations < 1 || maxIterations > 30) {
+            throw new IllegalArgumentException("maxIterations 必须在 1 到 30 之间");
+        }
+    }
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunRequest.java
+package com.cmagent.core.domain;
+
+import com.cmagent.api.PrincipalRef;
+
+import java.util.List;
+import java.util.UUID;
+
+public record AgentRunRequest(
+        UUID tenantId,
+        UUID agentId,
+        PrincipalRef principal,
+        String input,
+        List<ToolDefinition> tools
+) {
+    public AgentRunRequest {
+        tools = List.copyOf(tools);
+    }
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/AgentRunResult.java
+package com.cmagent.core.domain;
+
+import java.time.Instant;
+import java.util.List;
+import java.util.UUID;
+
+public record AgentRunResult(
+        UUID runId,
+        RunStatus status,
+        String output,
+        List<ToolCallRecord> toolCalls,
+        Instant startedAt,
+        Instant finishedAt,
+        String errorMessage
+) {
+    public AgentRunResult {
+        toolCalls = List.copyOf(toolCalls);
+    }
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/RunStatus.java
+package com.cmagent.core.domain;
+
+public enum RunStatus {
+    RUNNING,
+    SUCCEEDED,
+    FAILED,
+    DENIED
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/ToolCallRecord.java
+package com.cmagent.core.domain;
+
+import java.time.Duration;
+import java.util.UUID;
+
+public record ToolCallRecord(
+        UUID toolId,
+        String toolName,
+        String inputSummary,
+        String outputSummary,
+        RunStatus status,
+        Duration duration,
+        boolean authorized,
+        String errorMessage
+) {
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/ToolDefinition.java
+package com.cmagent.core.domain;
+
+import java.util.UUID;
+
+public record ToolDefinition(
+        UUID id,
+        UUID tenantId,
+        String name,
+        String description,
+        ToolType type,
+        String inputSchema,
+        ToolRiskLevel riskLevel,
+        boolean enabled,
+        String endpoint,
+        String createdBy,
+        String updatedBy
+) {
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/ToolRiskLevel.java
+package com.cmagent.core.domain;
+
+public enum ToolRiskLevel {
+    LOW,
+    MEDIUM,
+    HIGH
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/ToolType.java
+package com.cmagent.core.domain;
+
+public enum ToolType {
+    LOCAL,
+    MCP,
+    A2A
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/ToolGrant.java
+package com.cmagent.core.domain;
+
+import java.util.UUID;
+
+public record ToolGrant(UUID tenantId, UUID toolId, UUID agentId, String roleCode, boolean granted) {
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/ModelConfig.java
+package com.cmagent.core.domain;
+
+import java.util.UUID;
+
+public record ModelConfig(
+        UUID id,
+        UUID tenantId,
+        ModelProviderType providerType,
+        String displayName,
+        String baseUrl,
+        String modelName,
+        boolean enabled
+) {
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/domain/ModelProviderType.java
+package com.cmagent.core.domain;
+
+public enum ModelProviderType {
+    DASHSCOPE_NATIVE,
+    OPENAI_COMPATIBLE
+}
*** End Patch
```

- [ ] **Step 4: 运行领域模型测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-core -Dtest=AgentDefinitionTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交 API 和领域模型**

Run:

```powershell
git add cm-agent-api cm-agent-core
git commit -m "feat: 添加 API 契约和核心领域模型"
```

Expected:

```text
输出包含：feat: 添加 API 契约和核心领域模型
```

## Task 3: RBAC 和工具授权策略

**Files:**
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\AuthorizationDecision.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\PermissionEvaluator.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\DefaultPermissionEvaluator.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\ToolAuthorizationPolicy.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\security\DefaultToolAuthorizationPolicy.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\security\DefaultPermissionEvaluatorTest.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\security\DefaultToolAuthorizationPolicyTest.java`

- [ ] **Step 1: 写 RBAC 和工具授权失败测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-core/src/test/java/com/cmagent/core/security/DefaultPermissionEvaluatorTest.java
+package com.cmagent.core.security;
+
+import com.cmagent.api.PrincipalRef;
+import org.junit.jupiter.api.Test;
+
+import java.util.Set;
+import java.util.UUID;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+class DefaultPermissionEvaluatorTest {
+
+    @Test
+    void allowWhenPrincipalHasPermission() {
+        PrincipalRef principal = new PrincipalRef(
+                UUID.fromString("00000000-0000-0000-0000-000000000001"),
+                "admin",
+                "管理员",
+                Set.of("agent:run")
+        );
+
+        AuthorizationDecision decision = new DefaultPermissionEvaluator().check(principal, "agent:run");
+
+        assertThat(decision.allowed()).isTrue();
+        assertThat(decision.reason()).isEqualTo("允许访问");
+    }
+
+    @Test
+    void denyWhenPrincipalMissesPermission() {
+        PrincipalRef principal = new PrincipalRef(
+                UUID.fromString("00000000-0000-0000-0000-000000000001"),
+                "viewer",
+                "只读用户",
+                Set.of("agent:read")
+        );
+
+        AuthorizationDecision decision = new DefaultPermissionEvaluator().check(principal, "agent:run");
+
+        assertThat(decision.allowed()).isFalse();
+        assertThat(decision.reason()).isEqualTo("缺少权限 agent:run");
+    }
+}
*** Add File: cm-agent-core/src/test/java/com/cmagent/core/security/DefaultToolAuthorizationPolicyTest.java
+package com.cmagent.core.security;
+
+import com.cmagent.api.PrincipalRef;
+import com.cmagent.core.domain.ToolDefinition;
+import com.cmagent.core.domain.ToolGrant;
+import com.cmagent.core.domain.ToolRiskLevel;
+import com.cmagent.core.domain.ToolType;
+import org.junit.jupiter.api.Test;
+
+import java.util.List;
+import java.util.Set;
+import java.util.UUID;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+class DefaultToolAuthorizationPolicyTest {
+
+    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
+    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
+    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
+
+    @Test
+    void allowEnabledGrantedToolInSameTenant() {
+        ToolDefinition tool = new ToolDefinition(
+                TOOL_ID, TENANT_ID, "calendar.query", "查询日程",
+                ToolType.LOCAL, "{\"type\":\"object\"}", ToolRiskLevel.LOW,
+                true, "", "admin", "admin"
+        );
+        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, "", true);
+        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("agent:run"));
+
+        AuthorizationDecision decision = new DefaultToolAuthorizationPolicy()
+                .check(principal, AGENT_ID, tool, List.of(grant));
+
+        assertThat(decision.allowed()).isTrue();
+    }
+
+    @Test
+    void denyWhenToolGrantIsMissing() {
+        ToolDefinition tool = new ToolDefinition(
+                TOOL_ID, TENANT_ID, "calendar.query", "查询日程",
+                ToolType.LOCAL, "{\"type\":\"object\"}", ToolRiskLevel.LOW,
+                true, "", "admin", "admin"
+        );
+        PrincipalRef principal = new PrincipalRef(TENANT_ID, "admin", "管理员", Set.of("agent:run"));
+
+        AuthorizationDecision decision = new DefaultToolAuthorizationPolicy()
+                .check(principal, AGENT_ID, tool, List.of());
+
+        assertThat(decision.allowed()).isFalse();
+        assertThat(decision.reason()).isEqualTo("Agent 未获得工具授权 calendar.query");
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-core -Dtest=DefaultPermissionEvaluatorTest,DefaultToolAuthorizationPolicyTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class DefaultPermissionEvaluator
```

- [ ] **Step 3: 实现 RBAC 和工具授权策略**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/security/AuthorizationDecision.java
+package com.cmagent.core.security;
+
+public record AuthorizationDecision(boolean allowed, String reason) {
+    public static AuthorizationDecision allow() {
+        return new AuthorizationDecision(true, "允许访问");
+    }
+
+    public static AuthorizationDecision deny(String reason) {
+        return new AuthorizationDecision(false, reason);
+    }
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/security/PermissionEvaluator.java
+package com.cmagent.core.security;
+
+import com.cmagent.api.PrincipalRef;
+
+public interface PermissionEvaluator {
+    AuthorizationDecision check(PrincipalRef principal, String permission);
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/security/DefaultPermissionEvaluator.java
+package com.cmagent.core.security;
+
+import com.cmagent.api.PrincipalRef;
+
+public class DefaultPermissionEvaluator implements PermissionEvaluator {
+    @Override
+    public AuthorizationDecision check(PrincipalRef principal, String permission) {
+        if (principal.permissions().contains(permission)) {
+            return AuthorizationDecision.allow();
+        }
+        return AuthorizationDecision.deny("缺少权限 " + permission);
+    }
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/security/ToolAuthorizationPolicy.java
+package com.cmagent.core.security;
+
+import com.cmagent.api.PrincipalRef;
+import com.cmagent.core.domain.ToolDefinition;
+import com.cmagent.core.domain.ToolGrant;
+
+import java.util.List;
+import java.util.UUID;
+
+public interface ToolAuthorizationPolicy {
+    AuthorizationDecision check(PrincipalRef principal, UUID agentId, ToolDefinition tool, List<ToolGrant> grants);
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/security/DefaultToolAuthorizationPolicy.java
+package com.cmagent.core.security;
+
+import com.cmagent.api.PrincipalRef;
+import com.cmagent.core.domain.ToolDefinition;
+import com.cmagent.core.domain.ToolGrant;
+
+import java.util.List;
+import java.util.UUID;
+
+public class DefaultToolAuthorizationPolicy implements ToolAuthorizationPolicy {
+    @Override
+    public AuthorizationDecision check(PrincipalRef principal, UUID agentId, ToolDefinition tool, List<ToolGrant> grants) {
+        if (!principal.tenantId().equals(tool.tenantId())) {
+            return AuthorizationDecision.deny("工具不属于当前租户");
+        }
+        if (!tool.enabled()) {
+            return AuthorizationDecision.deny("工具已禁用 " + tool.name());
+        }
+        boolean granted = grants.stream().anyMatch(grant ->
+                grant.granted()
+                        && grant.tenantId().equals(principal.tenantId())
+                        && grant.toolId().equals(tool.id())
+                        && grant.agentId().equals(agentId)
+        );
+        if (!granted) {
+            return AuthorizationDecision.deny("Agent 未获得工具授权 " + tool.name());
+        }
+        return AuthorizationDecision.allow();
+    }
+}
*** End Patch
```

- [ ] **Step 4: 运行授权测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-core -Dtest=DefaultPermissionEvaluatorTest,DefaultToolAuthorizationPolicyTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交权限策略**

Run:

```powershell
git add cm-agent-core
git commit -m "feat: 添加 RBAC 和工具授权策略"
```

Expected:

```text
输出包含：feat: 添加 RBAC 和工具授权策略
```

## Task 4: 工具注册表和 fake runtime

**Files:**
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\runtime\AgentRuntime.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\runtime\FakeAgentRuntime.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\InMemoryToolRegistry.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\ToolExecutionRequest.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\ToolExecutionResult.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\ToolExecutor.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\tool\ToolRegistry.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\runtime\FakeAgentRuntimeTest.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\test\java\com\cmagent\core\tool\InMemoryToolRegistryTest.java`

- [ ] **Step 1: 写工具注册和 fake runtime 测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-core/src/test/java/com/cmagent/core/tool/InMemoryToolRegistryTest.java
+package com.cmagent.core.tool;
+
+import com.cmagent.core.domain.ToolDefinition;
+import com.cmagent.core.domain.ToolRiskLevel;
+import com.cmagent.core.domain.ToolType;
+import org.junit.jupiter.api.Test;
+
+import java.util.UUID;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+class InMemoryToolRegistryTest {
+
+    @Test
+    void registerAndExecuteLocalTool() {
+        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
+        UUID toolId = UUID.fromString("00000000-0000-0000-0000-000000000101");
+        ToolDefinition definition = new ToolDefinition(
+                toolId, tenantId, "echo", "回显输入",
+                ToolType.LOCAL, "{\"type\":\"object\"}", ToolRiskLevel.LOW,
+                true, "", "admin", "admin"
+        );
+        InMemoryToolRegistry registry = new InMemoryToolRegistry();
+
+        registry.register(definition, request -> new ToolExecutionResult("收到：" + request.inputJson(), true));
+
+        assertThat(registry.find(toolId)).contains(definition);
+        assertThat(registry.execute(new ToolExecutionRequest(toolId, "{\"text\":\"你好\"}")).outputSummary())
+                .isEqualTo("收到：{\"text\":\"你好\"}");
+    }
+}
*** Add File: cm-agent-core/src/test/java/com/cmagent/core/runtime/FakeAgentRuntimeTest.java
+package com.cmagent.core.runtime;
+
+import com.cmagent.api.PrincipalRef;
+import com.cmagent.core.domain.AgentRunRequest;
+import com.cmagent.core.domain.RunStatus;
+import org.junit.jupiter.api.Test;
+
+import java.util.List;
+import java.util.Set;
+import java.util.UUID;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+class FakeAgentRuntimeTest {
+
+    @Test
+    void echoRunInputForDeterministicTests() {
+        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
+        AgentRunRequest request = new AgentRunRequest(
+                tenantId,
+                UUID.fromString("00000000-0000-0000-0000-000000000201"),
+                new PrincipalRef(tenantId, "admin", "管理员", Set.of("agent:run")),
+                "请查询今天日程",
+                List.of()
+        );
+
+        var result = new FakeAgentRuntime().run(request);
+
+        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
+        assertThat(result.output()).isEqualTo("fake-runtime: 请查询今天日程");
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-core -Dtest=InMemoryToolRegistryTest,FakeAgentRuntimeTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class InMemoryToolRegistry
```

- [ ] **Step 3: 实现工具注册表和 fake runtime**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/runtime/AgentRuntime.java
+package com.cmagent.core.runtime;
+
+import com.cmagent.core.domain.AgentRunRequest;
+import com.cmagent.core.domain.AgentRunResult;
+
+public interface AgentRuntime {
+    AgentRunResult run(AgentRunRequest request);
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/runtime/FakeAgentRuntime.java
+package com.cmagent.core.runtime;
+
+import com.cmagent.core.domain.AgentRunRequest;
+import com.cmagent.core.domain.AgentRunResult;
+import com.cmagent.core.domain.RunStatus;
+
+import java.time.Instant;
+import java.util.List;
+import java.util.UUID;
+
+public class FakeAgentRuntime implements AgentRuntime {
+    @Override
+    public AgentRunResult run(AgentRunRequest request) {
+        Instant now = Instant.now();
+        return new AgentRunResult(
+                UUID.randomUUID(),
+                RunStatus.SUCCEEDED,
+                "fake-runtime: " + request.input(),
+                List.of(),
+                now,
+                now,
+                ""
+        );
+    }
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/tool/ToolExecutionRequest.java
+package com.cmagent.core.tool;
+
+import java.util.UUID;
+
+public record ToolExecutionRequest(UUID toolId, String inputJson) {
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/tool/ToolExecutionResult.java
+package com.cmagent.core.tool;
+
+public record ToolExecutionResult(String outputSummary, boolean success) {
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/tool/ToolExecutor.java
+package com.cmagent.core.tool;
+
+@FunctionalInterface
+public interface ToolExecutor {
+    ToolExecutionResult execute(ToolExecutionRequest request);
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/tool/ToolRegistry.java
+package com.cmagent.core.tool;
+
+import com.cmagent.core.domain.ToolDefinition;
+
+import java.util.Optional;
+import java.util.UUID;
+
+public interface ToolRegistry {
+    void register(ToolDefinition definition, ToolExecutor executor);
+
+    Optional<ToolDefinition> find(UUID toolId);
+
+    ToolExecutionResult execute(ToolExecutionRequest request);
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/tool/InMemoryToolRegistry.java
+package com.cmagent.core.tool;
+
+import com.cmagent.core.domain.ToolDefinition;
+
+import java.util.Map;
+import java.util.Optional;
+import java.util.UUID;
+import java.util.concurrent.ConcurrentHashMap;
+
+public class InMemoryToolRegistry implements ToolRegistry {
+    private final Map<UUID, ToolDefinition> definitions = new ConcurrentHashMap<>();
+    private final Map<UUID, ToolExecutor> executors = new ConcurrentHashMap<>();
+
+    @Override
+    public void register(ToolDefinition definition, ToolExecutor executor) {
+        definitions.put(definition.id(), definition);
+        executors.put(definition.id(), executor);
+    }
+
+    @Override
+    public Optional<ToolDefinition> find(UUID toolId) {
+        return Optional.ofNullable(definitions.get(toolId));
+    }
+
+    @Override
+    public ToolExecutionResult execute(ToolExecutionRequest request) {
+        ToolExecutor executor = executors.get(request.toolId());
+        if (executor == null) {
+            return new ToolExecutionResult("工具未注册 " + request.toolId(), false);
+        }
+        return executor.execute(request);
+    }
+}
*** End Patch
```

- [ ] **Step 4: 运行工具和 runtime 测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-core -Dtest=InMemoryToolRegistryTest,FakeAgentRuntimeTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交工具注册表和 fake runtime**

Run:

```powershell
git add cm-agent-core
git commit -m "feat: 添加工具注册表和 fake runtime"
```

Expected:

```text
输出包含：feat: 添加工具注册表和 fake runtime
```

## Task 5: Flyway schema 和双数据库迁移测试

**Files:**
- Create: `F:\java\cm-agent\cm-agent-persistence\src\main\resources\db\migration\V1__init_schema.sql`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\MigrationTest.java`

- [ ] **Step 1: 写迁移测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-persistence/src/test/java/com/cmagent/persistence/MigrationTest.java
+package com.cmagent.persistence;
+
+import org.flywaydb.core.Flyway;
+import org.junit.jupiter.api.Test;
+import org.testcontainers.junit.jupiter.Container;
+import org.testcontainers.junit.jupiter.Testcontainers;
+import org.testcontainers.mysql.MySQLContainer;
+import org.testcontainers.postgresql.PostgreSQLContainer;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+@Testcontainers
+class MigrationTest {
+
+    @Container
+    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
+
+    @Container
+    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
+
+    @Test
+    void migratePostgreSQL() {
+        int migrations = Flyway.configure()
+                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
+                .locations("classpath:db/migration")
+                .load()
+                .migrate()
+                .migrationsExecuted;
+
+        assertThat(migrations).isEqualTo(1);
+    }
+
+    @Test
+    void migrateMySQL() {
+        int migrations = Flyway.configure()
+                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
+                .locations("classpath:db/migration")
+                .load()
+                .migrate()
+                .migrationsExecuted;
+
+        assertThat(migrations).isEqualTo(1);
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行迁移测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=MigrationTest test
```

Expected:

```text
FlywayException
Unable to resolve location classpath:db/migration
```

- [ ] **Step 3: 写入可移植 schema**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-persistence/src/main/resources/db/migration/V1__init_schema.sql
+CREATE TABLE tenants (
+    id CHAR(36) PRIMARY KEY,
+    code VARCHAR(80) NOT NULL UNIQUE,
+    name VARCHAR(160) NOT NULL,
+    enabled BOOLEAN NOT NULL,
+    created_at TIMESTAMP NOT NULL
+);
+
+CREATE TABLE users (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    username VARCHAR(120) NOT NULL,
+    password_hash VARCHAR(255) NOT NULL,
+    display_name VARCHAR(120) NOT NULL,
+    enabled BOOLEAN NOT NULL,
+    created_at TIMESTAMP NOT NULL,
+    UNIQUE (tenant_id, username)
+);
+
+CREATE TABLE roles (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    code VARCHAR(120) NOT NULL,
+    name VARCHAR(120) NOT NULL,
+    UNIQUE (tenant_id, code)
+);
+
+CREATE TABLE permissions (
+    code VARCHAR(120) PRIMARY KEY,
+    description VARCHAR(255) NOT NULL
+);
+
+CREATE TABLE user_roles (
+    user_id CHAR(36) NOT NULL,
+    role_id CHAR(36) NOT NULL,
+    PRIMARY KEY (user_id, role_id)
+);
+
+CREATE TABLE role_permissions (
+    role_id CHAR(36) NOT NULL,
+    permission_code VARCHAR(120) NOT NULL,
+    PRIMARY KEY (role_id, permission_code)
+);
+
+CREATE TABLE api_keys (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    name VARCHAR(160) NOT NULL,
+    key_hash VARCHAR(255) NOT NULL,
+    permissions_json TEXT NOT NULL,
+    enabled BOOLEAN NOT NULL,
+    created_at TIMESTAMP NOT NULL,
+    rotated_at TIMESTAMP
+);
+
+CREATE TABLE model_configs (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    provider_type VARCHAR(40) NOT NULL,
+    display_name VARCHAR(160) NOT NULL,
+    base_url VARCHAR(500) NOT NULL,
+    model_name VARCHAR(160) NOT NULL,
+    encrypted_api_key TEXT NOT NULL,
+    enabled BOOLEAN NOT NULL,
+    created_at TIMESTAMP NOT NULL
+);
+
+CREATE TABLE agent_definitions (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    name VARCHAR(160) NOT NULL,
+    description VARCHAR(500) NOT NULL,
+    system_prompt TEXT NOT NULL,
+    model_provider_id CHAR(36) NOT NULL,
+    model_name VARCHAR(160) NOT NULL,
+    temperature DOUBLE PRECISION NOT NULL,
+    max_iterations INTEGER NOT NULL,
+    enabled BOOLEAN NOT NULL,
+    tool_ids_json TEXT NOT NULL,
+    created_by VARCHAR(120) NOT NULL,
+    updated_by VARCHAR(120) NOT NULL,
+    created_at TIMESTAMP NOT NULL,
+    updated_at TIMESTAMP NOT NULL
+);
+
+CREATE TABLE tool_definitions (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    name VARCHAR(160) NOT NULL,
+    description VARCHAR(500) NOT NULL,
+    type VARCHAR(30) NOT NULL,
+    input_schema TEXT NOT NULL,
+    risk_level VARCHAR(30) NOT NULL,
+    enabled BOOLEAN NOT NULL,
+    endpoint VARCHAR(500) NOT NULL,
+    created_by VARCHAR(120) NOT NULL,
+    updated_by VARCHAR(120) NOT NULL,
+    created_at TIMESTAMP NOT NULL,
+    updated_at TIMESTAMP NOT NULL
+);
+
+CREATE TABLE tool_grants (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    tool_id CHAR(36) NOT NULL,
+    agent_id CHAR(36) NOT NULL,
+    role_code VARCHAR(120) NOT NULL,
+    granted BOOLEAN NOT NULL,
+    created_at TIMESTAMP NOT NULL
+);
+
+CREATE TABLE conversations (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    agent_id CHAR(36) NOT NULL,
+    title VARCHAR(200) NOT NULL,
+    created_by VARCHAR(120) NOT NULL,
+    created_at TIMESTAMP NOT NULL
+);
+
+CREATE TABLE messages (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    conversation_id CHAR(36) NOT NULL,
+    role VARCHAR(30) NOT NULL,
+    content TEXT NOT NULL,
+    created_at TIMESTAMP NOT NULL
+);
+
+CREATE TABLE runs (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    agent_id CHAR(36) NOT NULL,
+    principal_id VARCHAR(120) NOT NULL,
+    status VARCHAR(30) NOT NULL,
+    input_text TEXT NOT NULL,
+    output_text TEXT NOT NULL,
+    error_message TEXT NOT NULL,
+    started_at TIMESTAMP NOT NULL,
+    finished_at TIMESTAMP
+);
+
+CREATE TABLE tool_calls (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    run_id CHAR(36) NOT NULL,
+    tool_id CHAR(36) NOT NULL,
+    tool_name VARCHAR(160) NOT NULL,
+    input_summary TEXT NOT NULL,
+    output_summary TEXT NOT NULL,
+    status VARCHAR(30) NOT NULL,
+    authorized BOOLEAN NOT NULL,
+    duration_ms BIGINT NOT NULL,
+    error_message TEXT NOT NULL,
+    created_at TIMESTAMP NOT NULL
+);
+
+CREATE TABLE audit_events (
+    id CHAR(36) PRIMARY KEY,
+    tenant_id CHAR(36) NOT NULL,
+    principal_id VARCHAR(120) NOT NULL,
+    event_type VARCHAR(120) NOT NULL,
+    resource_type VARCHAR(120) NOT NULL,
+    resource_id VARCHAR(120) NOT NULL,
+    status VARCHAR(30) NOT NULL,
+    message TEXT NOT NULL,
+    created_at TIMESTAMP NOT NULL
+);
+
+CREATE INDEX idx_agent_definitions_tenant ON agent_definitions (tenant_id);
+CREATE INDEX idx_tool_definitions_tenant ON tool_definitions (tenant_id);
+CREATE INDEX idx_tool_grants_tenant_agent ON tool_grants (tenant_id, agent_id);
+CREATE INDEX idx_runs_tenant_agent ON runs (tenant_id, agent_id);
+CREATE INDEX idx_audit_events_tenant_time ON audit_events (tenant_id, created_at);
*** End Patch
```

- [ ] **Step 4: 运行迁移测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=MigrationTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交迁移**

Run:

```powershell
git add cm-agent-persistence
git commit -m "feat: 添加双数据库 Flyway 迁移"
```

Expected:

```text
输出包含：feat: 添加双数据库 Flyway 迁移
```

## Task 6: 审计 repository 和租户隔离查询

**Files:**
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\audit\AuditEvent.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\audit\AuditEventRepository.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\main\java\com\cmagent\persistence\JdbcAuditEventRepository.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\JdbcAuditEventRepositoryTest.java`
- Modify: `F:\java\cm-agent\cm-agent-persistence\pom.xml`

- [ ] **Step 1: 写 repository 测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcAuditEventRepositoryTest.java
+package com.cmagent.persistence;
+
+import com.cmagent.core.audit.AuditEvent;
+import org.flywaydb.core.Flyway;
+import org.junit.jupiter.api.BeforeEach;
+import org.junit.jupiter.api.Test;
+import org.springframework.jdbc.core.simple.JdbcClient;
+import org.testcontainers.junit.jupiter.Container;
+import org.testcontainers.junit.jupiter.Testcontainers;
+import org.testcontainers.postgresql.PostgreSQLContainer;
+
+import javax.sql.DataSource;
+import java.time.Instant;
+import java.util.UUID;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+@Testcontainers
+class JdbcAuditEventRepositoryTest {
+
+    @Container
+    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
+
+    private JdbcAuditEventRepository repository;
+
+    @BeforeEach
+    void setUp() {
+        DataSource dataSource = org.springframework.boot.jdbc.DataSourceBuilder.create()
+                .url(postgres.getJdbcUrl())
+                .username(postgres.getUsername())
+                .password(postgres.getPassword())
+                .build();
+        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load().clean();
+        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
+        repository = new JdbcAuditEventRepository(JdbcClient.create(dataSource));
+    }
+
+    @Test
+    void appendAndListByTenant() {
+        UUID tenantA = UUID.fromString("00000000-0000-0000-0000-000000000001");
+        UUID tenantB = UUID.fromString("00000000-0000-0000-0000-000000000002");
+        repository.append(new AuditEvent(UUID.randomUUID(), tenantA, "admin", "AGENT_RUN", "AGENT", "agent-a", "SUCCESS", "运行成功", Instant.parse("2026-06-18T01:00:00Z")));
+        repository.append(new AuditEvent(UUID.randomUUID(), tenantB, "admin", "AGENT_RUN", "AGENT", "agent-b", "SUCCESS", "运行成功", Instant.parse("2026-06-18T02:00:00Z")));
+
+        var events = repository.listByTenant(tenantA, 20);
+
+        assertThat(events).hasSize(1);
+        assertThat(events.getFirst().tenantId()).isEqualTo(tenantA);
+        assertThat(events.getFirst().resourceId()).isEqualTo("agent-a");
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=JdbcAuditEventRepositoryTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class JdbcAuditEventRepository
```

- [ ] **Step 3: 实现审计模型和 repository**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/audit/AuditEvent.java
+package com.cmagent.core.audit;
+
+import java.time.Instant;
+import java.util.UUID;
+
+public record AuditEvent(
+        UUID id,
+        UUID tenantId,
+        String principalId,
+        String eventType,
+        String resourceType,
+        String resourceId,
+        String status,
+        String message,
+        Instant createdAt
+) {
+}
*** Add File: cm-agent-core/src/main/java/com/cmagent/core/audit/AuditEventRepository.java
+package com.cmagent.core.audit;
+
+import java.util.List;
+import java.util.UUID;
+
+public interface AuditEventRepository {
+    void append(AuditEvent event);
+
+    List<AuditEvent> listByTenant(UUID tenantId, int limit);
+}
*** Add File: cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcAuditEventRepository.java
+package com.cmagent.persistence;
+
+import com.cmagent.core.audit.AuditEvent;
+import com.cmagent.core.audit.AuditEventRepository;
+import org.springframework.jdbc.core.simple.JdbcClient;
+
+import java.sql.Timestamp;
+import java.util.List;
+import java.util.UUID;
+
+public class JdbcAuditEventRepository implements AuditEventRepository {
+    private final JdbcClient jdbcClient;
+
+    public JdbcAuditEventRepository(JdbcClient jdbcClient) {
+        this.jdbcClient = jdbcClient;
+    }
+
+    @Override
+    public void append(AuditEvent event) {
+        jdbcClient.sql("""
+                        INSERT INTO audit_events
+                        (id, tenant_id, principal_id, event_type, resource_type, resource_id, status, message, created_at)
+                        VALUES (:id, :tenantId, :principalId, :eventType, :resourceType, :resourceId, :status, :message, :createdAt)
+                        """)
+                .param("id", event.id().toString())
+                .param("tenantId", event.tenantId().toString())
+                .param("principalId", event.principalId())
+                .param("eventType", event.eventType())
+                .param("resourceType", event.resourceType())
+                .param("resourceId", event.resourceId())
+                .param("status", event.status())
+                .param("message", event.message())
+                .param("createdAt", Timestamp.from(event.createdAt()))
+                .update();
+    }
+
+    @Override
+    public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
+        return jdbcClient.sql("""
+                        SELECT id, tenant_id, principal_id, event_type, resource_type, resource_id, status, message, created_at
+                        FROM audit_events
+                        WHERE tenant_id = :tenantId
+                        ORDER BY created_at DESC
+                        LIMIT :limit
+                        """)
+                .param("tenantId", tenantId.toString())
+                .param("limit", limit)
+                .query((rs, rowNum) -> new AuditEvent(
+                        UUID.fromString(rs.getString("id")),
+                        UUID.fromString(rs.getString("tenant_id")),
+                        rs.getString("principal_id"),
+                        rs.getString("event_type"),
+                        rs.getString("resource_type"),
+                        rs.getString("resource_id"),
+                        rs.getString("status"),
+                        rs.getString("message"),
+                        rs.getTimestamp("created_at").toInstant()
+                ))
+                .list();
+    }
+}
*** End Patch
```

- [ ] **Step 4: 运行 repository 测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=JdbcAuditEventRepositoryTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交 repository**

Run:

```powershell
git add cm-agent-core cm-agent-persistence
git commit -m "feat: 添加审计 repository 和租户隔离查询"
```

Expected:

```text
输出包含：feat: 添加审计 repository 和租户隔离查询
```

## Task 7: Spring Boot Starter 自动装配

**Files:**
- Create: `F:\java\cm-agent\cm-agent-spring-boot-starter\src\main\java\com\cmagent\starter\CmAgentProperties.java`
- Create: `F:\java\cm-agent\cm-agent-spring-boot-starter\src\main\java\com\cmagent\starter\CmAgentAutoConfiguration.java`
- Create: `F:\java\cm-agent\cm-agent-spring-boot-starter\src\main\resources\META-INF\spring\org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `F:\java\cm-agent\cm-agent-spring-boot-starter\src\test\java\com\cmagent\starter\CmAgentAutoConfigurationTest.java`

- [ ] **Step 1: 写自动装配测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-spring-boot-starter/src/test/java/com/cmagent/starter/CmAgentAutoConfigurationTest.java
+package com.cmagent.starter;
+
+import com.cmagent.core.runtime.AgentRuntime;
+import com.cmagent.core.security.PermissionEvaluator;
+import com.cmagent.core.security.ToolAuthorizationPolicy;
+import com.cmagent.core.tool.ToolRegistry;
+import org.junit.jupiter.api.Test;
+import org.springframework.boot.autoconfigure.AutoConfigurations;
+import org.springframework.boot.test.context.runner.ApplicationContextRunner;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+class CmAgentAutoConfigurationTest {
+
+    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
+            .withConfiguration(AutoConfigurations.of(CmAgentAutoConfiguration.class));
+
+    @Test
+    void provideDefaultCoreBeans() {
+        contextRunner.run(context -> {
+            assertThat(context).hasSingleBean(CmAgentProperties.class);
+            assertThat(context).hasSingleBean(AgentRuntime.class);
+            assertThat(context).hasSingleBean(PermissionEvaluator.class);
+            assertThat(context).hasSingleBean(ToolAuthorizationPolicy.class);
+            assertThat(context).hasSingleBean(ToolRegistry.class);
+        });
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-spring-boot-starter -Dtest=CmAgentAutoConfigurationTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class CmAgentAutoConfiguration
```

- [ ] **Step 3: 实现自动装配**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-spring-boot-starter/src/main/java/com/cmagent/starter/CmAgentProperties.java
+package com.cmagent.starter;
+
+import org.springframework.boot.context.properties.ConfigurationProperties;
+
+@ConfigurationProperties(prefix = "cm-agent")
+public class CmAgentProperties {
+    private boolean fakeRuntimeEnabled = true;
+    private String defaultTenantCode = "default";
+
+    public boolean isFakeRuntimeEnabled() {
+        return fakeRuntimeEnabled;
+    }
+
+    public void setFakeRuntimeEnabled(boolean fakeRuntimeEnabled) {
+        this.fakeRuntimeEnabled = fakeRuntimeEnabled;
+    }
+
+    public String getDefaultTenantCode() {
+        return defaultTenantCode;
+    }
+
+    public void setDefaultTenantCode(String defaultTenantCode) {
+        this.defaultTenantCode = defaultTenantCode;
+    }
+}
*** Add File: cm-agent-spring-boot-starter/src/main/java/com/cmagent/starter/CmAgentAutoConfiguration.java
+package com.cmagent.starter;
+
+import com.cmagent.core.runtime.AgentRuntime;
+import com.cmagent.core.runtime.FakeAgentRuntime;
+import com.cmagent.core.security.DefaultPermissionEvaluator;
+import com.cmagent.core.security.DefaultToolAuthorizationPolicy;
+import com.cmagent.core.security.PermissionEvaluator;
+import com.cmagent.core.security.ToolAuthorizationPolicy;
+import com.cmagent.core.tool.InMemoryToolRegistry;
+import com.cmagent.core.tool.ToolRegistry;
+import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
+import org.springframework.boot.context.properties.EnableConfigurationProperties;
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
+
+@Configuration(proxyBeanMethods = false)
+@EnableConfigurationProperties(CmAgentProperties.class)
+public class CmAgentAutoConfiguration {
+
+    @Bean
+    @ConditionalOnMissingBean
+    AgentRuntime agentRuntime() {
+        return new FakeAgentRuntime();
+    }
+
+    @Bean
+    @ConditionalOnMissingBean
+    PermissionEvaluator permissionEvaluator() {
+        return new DefaultPermissionEvaluator();
+    }
+
+    @Bean
+    @ConditionalOnMissingBean
+    ToolAuthorizationPolicy toolAuthorizationPolicy() {
+        return new DefaultToolAuthorizationPolicy();
+    }
+
+    @Bean
+    @ConditionalOnMissingBean
+    ToolRegistry toolRegistry() {
+        return new InMemoryToolRegistry();
+    }
+}
*** Add File: cm-agent-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
+com.cmagent.starter.CmAgentAutoConfiguration
*** End Patch
```

- [ ] **Step 4: 运行自动装配测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-spring-boot-starter -Dtest=CmAgentAutoConfigurationTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交 Starter**

Run:

```powershell
git add cm-agent-spring-boot-starter
git commit -m "feat: 添加 Spring Boot Starter 自动装配"
```

Expected:

```text
输出包含：feat: 添加 Spring Boot Starter 自动装配
```

## Task 8: 服务端启动、JWT 登录和 RBAC 过滤

**Files:**
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\CmAgentServerApplication.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\JwtService.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\JwtAuthenticationFilter.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\LoginRequest.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\LoginResponse.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\security\SecurityConfig.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\AuthController.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\resources\application.yml`
- Create: `F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\AuthControllerTest.java`

- [ ] **Step 1: 写登录接口测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-server/src/test/java/com/cmagent/server/web/AuthControllerTest.java
+package com.cmagent.server.web;
+
+import com.cmagent.server.CmAgentServerApplication;
+import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
+import org.springframework.boot.test.context.SpringBootTest;
+import org.springframework.http.MediaType;
+import org.springframework.test.web.servlet.MockMvc;
+
+import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
+import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
+import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
+import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
+
+@SpringBootTest(classes = CmAgentServerApplication.class)
+@AutoConfigureMockMvc
+class AuthControllerTest {
+
+    @Autowired
+    MockMvc mockMvc;
+
+    @Test
+    void loginAndReadCurrentUser() throws Exception {
+        String loginBody = "{\"username\":\"admin\",\"password\":\"admin123456\"}";
+
+        String token = mockMvc.perform(post("/api/auth/login")
+                        .contentType(MediaType.APPLICATION_JSON)
+                        .content(loginBody))
+                .andExpect(status().isOk())
+                .andExpect(jsonPath("$.displayName").value("系统管理员"))
+                .andReturn()
+                .getResponse()
+                .getContentAsString()
+                .replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
+
+        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
+                .andExpect(status().isOk())
+                .andExpect(jsonPath("$.principalId").value("admin"))
+                .andExpect(jsonPath("$.permissions[0]").value("agent:run"));
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=AuthControllerTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class CmAgentServerApplication
```

- [ ] **Step 3: 实现服务端、JWT 和认证接口**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/CmAgentServerApplication.java
+package com.cmagent.server;
+
+import org.springframework.boot.SpringApplication;
+import org.springframework.boot.autoconfigure.SpringBootApplication;
+
+@SpringBootApplication(scanBasePackages = "com.cmagent")
+public class CmAgentServerApplication {
+    public static void main(String[] args) {
+        SpringApplication.run(CmAgentServerApplication.class, args);
+    }
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/security/LoginRequest.java
+package com.cmagent.server.security;
+
+public record LoginRequest(String username, String password) {
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/security/LoginResponse.java
+package com.cmagent.server.security;
+
+import java.util.Set;
+import java.util.UUID;
+
+public record LoginResponse(UUID tenantId, String principalId, String displayName, Set<String> permissions, String accessToken) {
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/security/JwtService.java
+package com.cmagent.server.security;
+
+import io.jsonwebtoken.Jwts;
+import io.jsonwebtoken.security.Keys;
+import org.springframework.stereotype.Service;
+
+import javax.crypto.SecretKey;
+import java.nio.charset.StandardCharsets;
+import java.time.Instant;
+import java.util.Date;
+import java.util.Set;
+import java.util.UUID;
+
+@Service
+public class JwtService {
+    private final SecretKey key = Keys.hmacShaKeyFor("cm-agent-local-development-secret-key-2026".getBytes(StandardCharsets.UTF_8));
+
+    public String createToken(UUID tenantId, String principalId, String displayName, Set<String> permissions) {
+        return Jwts.builder()
+                .subject(principalId)
+                .claim("tenantId", tenantId.toString())
+                .claim("displayName", displayName)
+                .claim("permissions", String.join(",", permissions))
+                .issuedAt(Date.from(Instant.now()))
+                .expiration(Date.from(Instant.now().plusSeconds(3600)))
+                .signWith(key)
+                .compact();
+    }
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/security/JwtAuthenticationFilter.java
+package com.cmagent.server.security;
+
+import jakarta.servlet.FilterChain;
+import jakarta.servlet.ServletException;
+import jakarta.servlet.http.HttpServletRequest;
+import jakarta.servlet.http.HttpServletResponse;
+import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
+import org.springframework.security.core.authority.SimpleGrantedAuthority;
+import org.springframework.security.core.context.SecurityContextHolder;
+import org.springframework.stereotype.Component;
+import org.springframework.web.filter.OncePerRequestFilter;
+
+import java.io.IOException;
+import java.util.List;
+
+@Component
+public class JwtAuthenticationFilter extends OncePerRequestFilter {
+    @Override
+    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
+            throws ServletException, IOException {
+        String authorization = request.getHeader("Authorization");
+        if (authorization != null && authorization.startsWith("Bearer ")) {
+            var authorities = List.of(
+                    new SimpleGrantedAuthority("agent:run"),
+                    new SimpleGrantedAuthority("agent:read"),
+                    new SimpleGrantedAuthority("agent:write"),
+                    new SimpleGrantedAuthority("tool:read"),
+                    new SimpleGrantedAuthority("tool:grant"),
+                    new SimpleGrantedAuthority("audit:read"),
+                    new SimpleGrantedAuthority("apikey:write")
+            );
+            var authentication = new UsernamePasswordAuthenticationToken("admin", authorization.substring(7), authorities);
+            SecurityContextHolder.getContext().setAuthentication(authentication);
+        }
+        filterChain.doFilter(request, response);
+    }
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/web/AuthController.java
+package com.cmagent.server.web;
+
+import com.cmagent.api.PrincipalRef;
+import com.cmagent.server.security.JwtService;
+import com.cmagent.server.security.LoginRequest;
+import com.cmagent.server.security.LoginResponse;
+import org.springframework.http.HttpStatus;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.PostMapping;
+import org.springframework.web.bind.annotation.RequestBody;
+import org.springframework.web.bind.annotation.RequestHeader;
+import org.springframework.web.bind.annotation.RequestMapping;
+import org.springframework.web.bind.annotation.ResponseStatus;
+import org.springframework.web.bind.annotation.RestController;
+
+import java.util.Set;
+import java.util.UUID;
+
+@RestController
+@RequestMapping("/api/auth")
+public class AuthController {
+    private static final UUID DEFAULT_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
+    private static final Set<String> ADMIN_PERMISSIONS = Set.of("agent:run", "agent:read", "agent:write", "tool:read", "tool:grant", "audit:read", "apikey:write");
+
+    private final JwtService jwtService;
+
+    public AuthController(JwtService jwtService) {
+        this.jwtService = jwtService;
+    }
+
+    @PostMapping("/login")
+    public LoginResponse login(@RequestBody LoginRequest request) {
+        if (!"admin".equals(request.username()) || !"admin123456".equals(request.password())) {
+            throw new InvalidLoginException();
+        }
+        String token = jwtService.createToken(DEFAULT_TENANT_ID, "admin", "系统管理员", ADMIN_PERMISSIONS);
+        return new LoginResponse(DEFAULT_TENANT_ID, "admin", "系统管理员", ADMIN_PERMISSIONS, token);
+    }
+
+    @GetMapping("/me")
+    public PrincipalRef me(@RequestHeader("Authorization") String authorization) {
+        if (authorization == null || !authorization.startsWith("Bearer ")) {
+            throw new InvalidLoginException();
+        }
+        return new PrincipalRef(DEFAULT_TENANT_ID, "admin", "系统管理员", ADMIN_PERMISSIONS);
+    }
+
+    @ResponseStatus(HttpStatus.UNAUTHORIZED)
+    static class InvalidLoginException extends RuntimeException {
+    }
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/security/SecurityConfig.java
+package com.cmagent.server.security;
+
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
+import org.springframework.security.config.annotation.web.builders.HttpSecurity;
+import org.springframework.security.config.http.SessionCreationPolicy;
+import org.springframework.security.web.SecurityFilterChain;
+import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
+
+@Configuration
+public class SecurityConfig {
+    private final JwtAuthenticationFilter jwtAuthenticationFilter;
+
+    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
+        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
+    }
+
+    @Bean
+    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
+        return http
+                .csrf(csrf -> csrf.disable())
+                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
+                .authorizeHttpRequests(auth -> auth
+                        .requestMatchers("/", "/assets/**", "/api/auth/**", "/actuator/health", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
+                        .anyRequest().authenticated()
+                )
+                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
+                .build();
+    }
+}
*** Add File: cm-agent-server/src/main/resources/application.yml
+server:
+  port: 8080
+
+spring:
+  application:
+    name: cm-agent-server
+
+management:
+  endpoints:
+    web:
+      exposure:
+        include: health,info
+
+cm-agent:
+  fake-runtime-enabled: true
+  default-tenant-code: default
*** End Patch
```

- [ ] **Step 4: 运行认证测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=AuthControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交服务端认证基线**

Run:

```powershell
git add cm-agent-server
git commit -m "feat: 添加服务端认证基线"
```

Expected:

```text
输出包含：feat: 添加服务端认证基线
```

## Task 9: Agent、工具、run 和审计 REST API

**Files:**
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\AgentController.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\ToolController.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\RunController.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\AuditController.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\store\InMemoryPlatformStore.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\RunControllerTest.java`

- [ ] **Step 1: 写 run 链路测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerTest.java
+package com.cmagent.server.web;
+
+import com.cmagent.server.CmAgentServerApplication;
+import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
+import org.springframework.boot.test.context.SpringBootTest;
+import org.springframework.http.MediaType;
+import org.springframework.test.web.servlet.MockMvc;
+
+import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
+import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
+import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
+import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
+
+@SpringBootTest(classes = CmAgentServerApplication.class)
+@AutoConfigureMockMvc
+class RunControllerTest {
+
+    @Autowired
+    MockMvc mockMvc;
+
+    @Test
+    void createAgentGrantToolRunAndAudit() throws Exception {
+        String token = login();
+
+        String agentId = mockMvc.perform(post("/api/agents")
+                        .header("Authorization", "Bearer " + token)
+                        .contentType(MediaType.APPLICATION_JSON)
+                        .content("{\"name\":\"企业助手\",\"systemPrompt\":\"你是企业助手\",\"modelName\":\"qwen-max\"}"))
+                .andExpect(status().isOk())
+                .andExpect(jsonPath("$.name").value("企业助手"))
+                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
+
+        String toolId = mockMvc.perform(post("/api/tools")
+                        .header("Authorization", "Bearer " + token)
+                        .contentType(MediaType.APPLICATION_JSON)
+                        .content("{\"name\":\"echo\",\"description\":\"回显输入\",\"type\":\"LOCAL\",\"riskLevel\":\"LOW\"}"))
+                .andExpect(status().isOk())
+                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
+
+        mockMvc.perform(post("/api/tools/" + toolId + "/grants")
+                        .header("Authorization", "Bearer " + token)
+                        .contentType(MediaType.APPLICATION_JSON)
+                        .content("{\"agentId\":\"" + agentId + "\"}"))
+                .andExpect(status().isOk())
+                .andExpect(jsonPath("$.granted").value(true));
+
+        mockMvc.perform(post("/api/agents/" + agentId + "/runs")
+                        .header("Authorization", "Bearer " + token)
+                        .contentType(MediaType.APPLICATION_JSON)
+                        .content("{\"input\":\"你好\"}"))
+                .andExpect(status().isOk())
+                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
+                .andExpect(jsonPath("$.output").value("fake-runtime: 你好"));
+
+        mockMvc.perform(get("/api/audit-events").header("Authorization", "Bearer " + token))
+                .andExpect(status().isOk())
+                .andExpect(jsonPath("$[0].eventType").value("AGENT_RUN"));
+    }
+
+    private String login() throws Exception {
+        return mockMvc.perform(post("/api/auth/login")
+                        .contentType(MediaType.APPLICATION_JSON)
+                        .content("{\"username\":\"admin\",\"password\":\"admin123456\"}"))
+                .andReturn().getResponse().getContentAsString().replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=RunControllerTest test
```

Expected:

```text
Status expected:<200> but was:<404>
```

- [ ] **Step 3: 实现内存平台存储和 REST API**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java
+package com.cmagent.server.store;
+
+import com.cmagent.core.audit.AuditEvent;
+import com.cmagent.core.domain.AgentDefinition;
+import com.cmagent.core.domain.ToolDefinition;
+import com.cmagent.core.domain.ToolGrant;
+import org.springframework.stereotype.Component;
+
+import java.util.ArrayList;
+import java.util.LinkedHashMap;
+import java.util.List;
+import java.util.Map;
+import java.util.UUID;
+
+@Component
+public class InMemoryPlatformStore {
+    private final Map<UUID, AgentDefinition> agents = new LinkedHashMap<>();
+    private final Map<UUID, ToolDefinition> tools = new LinkedHashMap<>();
+    private final List<ToolGrant> grants = new ArrayList<>();
+    private final List<AuditEvent> auditEvents = new ArrayList<>();
+
+    public Map<UUID, AgentDefinition> agents() {
+        return agents;
+    }
+
+    public Map<UUID, ToolDefinition> tools() {
+        return tools;
+    }
+
+    public List<ToolGrant> grants() {
+        return grants;
+    }
+
+    public List<AuditEvent> auditEvents() {
+        return auditEvents;
+    }
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/web/AgentController.java
+package com.cmagent.server.web;
+
+import com.cmagent.core.domain.AgentDefinition;
+import com.cmagent.server.store.InMemoryPlatformStore;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.PathVariable;
+import org.springframework.web.bind.annotation.PostMapping;
+import org.springframework.web.bind.annotation.RequestBody;
+import org.springframework.web.bind.annotation.RequestMapping;
+import org.springframework.web.bind.annotation.RestController;
+
+import java.util.List;
+import java.util.UUID;
+
+@RestController
+@RequestMapping("/api/agents")
+public class AgentController {
+    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
+    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
+    private final InMemoryPlatformStore store;
+
+    public AgentController(InMemoryPlatformStore store) {
+        this.store = store;
+    }
+
+    @GetMapping
+    public List<AgentDefinition> list() {
+        return List.copyOf(store.agents().values());
+    }
+
+    @GetMapping("/{id}")
+    public AgentDefinition get(@PathVariable UUID id) {
+        return store.agents().get(id);
+    }
+
+    @PostMapping
+    public AgentDefinition create(@RequestBody CreateAgentRequest request) {
+        AgentDefinition agent = new AgentDefinition(
+                UUID.randomUUID(), TENANT_ID, request.name(), "",
+                request.systemPrompt(), MODEL_PROVIDER_ID, request.modelName(),
+                0.2, 6, true, List.of(), "admin", "admin"
+        );
+        store.agents().put(agent.id(), agent);
+        return agent;
+    }
+
+    public record CreateAgentRequest(String name, String systemPrompt, String modelName) {
+    }
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/web/ToolController.java
+package com.cmagent.server.web;
+
+import com.cmagent.core.domain.ToolDefinition;
+import com.cmagent.core.domain.ToolGrant;
+import com.cmagent.core.domain.ToolRiskLevel;
+import com.cmagent.core.domain.ToolType;
+import com.cmagent.server.store.InMemoryPlatformStore;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.PathVariable;
+import org.springframework.web.bind.annotation.PostMapping;
+import org.springframework.web.bind.annotation.RequestBody;
+import org.springframework.web.bind.annotation.RequestMapping;
+import org.springframework.web.bind.annotation.RestController;
+
+import java.util.List;
+import java.util.UUID;
+
+@RestController
+@RequestMapping("/api/tools")
+public class ToolController {
+    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
+    private final InMemoryPlatformStore store;
+
+    public ToolController(InMemoryPlatformStore store) {
+        this.store = store;
+    }
+
+    @GetMapping
+    public List<ToolDefinition> list() {
+        return List.copyOf(store.tools().values());
+    }
+
+    @PostMapping
+    public ToolDefinition create(@RequestBody CreateToolRequest request) {
+        ToolDefinition tool = new ToolDefinition(
+                UUID.randomUUID(), TENANT_ID, request.name(), request.description(),
+                ToolType.valueOf(request.type()), "{\"type\":\"object\"}",
+                ToolRiskLevel.valueOf(request.riskLevel()), true, "", "admin", "admin"
+        );
+        store.tools().put(tool.id(), tool);
+        return tool;
+    }
+
+    @PostMapping("/{id}/grants")
+    public ToolGrant grant(@PathVariable UUID id, @RequestBody GrantToolRequest request) {
+        ToolGrant grant = new ToolGrant(TENANT_ID, id, request.agentId(), "", true);
+        store.grants().add(grant);
+        return grant;
+    }
+
+    public record CreateToolRequest(String name, String description, String type, String riskLevel) {
+    }
+
+    public record GrantToolRequest(UUID agentId) {
+    }
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/web/RunController.java
+package com.cmagent.server.web;
+
+import com.cmagent.api.PrincipalRef;
+import com.cmagent.core.audit.AuditEvent;
+import com.cmagent.core.domain.AgentRunRequest;
+import com.cmagent.core.runtime.AgentRuntime;
+import com.cmagent.server.store.InMemoryPlatformStore;
+import org.springframework.web.bind.annotation.PathVariable;
+import org.springframework.web.bind.annotation.PostMapping;
+import org.springframework.web.bind.annotation.RequestBody;
+import org.springframework.web.bind.annotation.RequestMapping;
+import org.springframework.web.bind.annotation.RestController;
+
+import java.time.Instant;
+import java.util.Set;
+import java.util.UUID;
+
+@RestController
+@RequestMapping("/api/agents/{agentId}/runs")
+public class RunController {
+    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
+    private final AgentRuntime runtime;
+    private final InMemoryPlatformStore store;
+
+    public RunController(AgentRuntime runtime, InMemoryPlatformStore store) {
+        this.runtime = runtime;
+        this.store = store;
+    }
+
+    @PostMapping
+    public Object run(@PathVariable UUID agentId, @RequestBody RunRequest request) {
+        var principal = new PrincipalRef(TENANT_ID, "admin", "系统管理员", Set.of("agent:run"));
+        var result = runtime.run(new AgentRunRequest(TENANT_ID, agentId, principal, request.input(), store.tools().values().stream().toList()));
+        store.auditEvents().add(new AuditEvent(UUID.randomUUID(), TENANT_ID, "admin", "AGENT_RUN", "AGENT", agentId.toString(), result.status().name(), "Agent 运行完成", Instant.now()));
+        return result;
+    }
+
+    public record RunRequest(String input) {
+    }
+}
*** Add File: cm-agent-server/src/main/java/com/cmagent/server/web/AuditController.java
+package com.cmagent.server.web;
+
+import com.cmagent.core.audit.AuditEvent;
+import com.cmagent.server.store.InMemoryPlatformStore;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.RequestMapping;
+import org.springframework.web.bind.annotation.RestController;
+
+import java.util.List;
+
+@RestController
+@RequestMapping("/api/audit-events")
+public class AuditController {
+    private final InMemoryPlatformStore store;
+
+    public AuditController(InMemoryPlatformStore store) {
+        this.store = store;
+    }
+
+    @GetMapping
+    public List<AuditEvent> list() {
+        return store.auditEvents().reversed();
+    }
+}
*** End Patch
```

- [ ] **Step 4: 运行 run 链路测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=RunControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交 REST API 纵切**

Run:

```powershell
git add cm-agent-server
git commit -m "feat: 添加 Agent 工具 run 和审计 API"
```

Expected:

```text
输出包含：feat: 添加 Agent 工具 run 和审计 API
```

## Task 10: 最小控制台静态资源

**Files:**
- Create: `F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\index.html`
- Create: `F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\assets\app.js`
- Create: `F:\java\cm-agent\cm-agent-console\src\main\resources\META-INF\resources\assets\styles.css`
- Create: `F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\ConsoleSmokeTest.java`

- [ ] **Step 1: 写控制台冒烟测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-server/src/test/java/com/cmagent/server/web/ConsoleSmokeTest.java
+package com.cmagent.server.web;
+
+import com.cmagent.server.CmAgentServerApplication;
+import org.junit.jupiter.api.Test;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
+import org.springframework.boot.test.context.SpringBootTest;
+import org.springframework.test.web.servlet.MockMvc;
+
+import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
+import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
+import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
+
+@SpringBootTest(classes = CmAgentServerApplication.class)
+@AutoConfigureMockMvc
+class ConsoleSmokeTest {
+
+    @Autowired
+    MockMvc mockMvc;
+
+    @Test
+    void serveConsoleIndex() throws Exception {
+        mockMvc.perform(get("/"))
+                .andExpect(status().isOk())
+                .andExpect(content().string(org.hamcrest.Matchers.containsString("CM Agent 控制台")));
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=ConsoleSmokeTest test
```

Expected:

```text
Status expected:<200> but was:<404>
```

- [ ] **Step 3: 写控制台静态资源**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-console/src/main/resources/META-INF/resources/index.html
+<!doctype html>
+<html lang="zh-CN">
+<head>
+    <meta charset="utf-8">
+    <meta name="viewport" content="width=device-width, initial-scale=1">
+    <title>CM Agent 控制台</title>
+    <link rel="stylesheet" href="/assets/styles.css">
+</head>
+<body>
+<main class="shell">
+    <section class="toolbar">
+        <h1>CM Agent 控制台</h1>
+        <button id="loginBtn">登录默认管理员</button>
+    </section>
+    <section class="grid">
+        <article>
+            <h2>Agent 管理</h2>
+            <input id="agentName" value="企业助手">
+            <textarea id="systemPrompt">你是企业助手，回答必须简洁。</textarea>
+            <button id="createAgentBtn">创建 Agent</button>
+            <pre id="agentOutput"></pre>
+        </article>
+        <article>
+            <h2>工具治理</h2>
+            <input id="toolName" value="echo">
+            <button id="createToolBtn">创建工具并授权</button>
+            <pre id="toolOutput"></pre>
+        </article>
+        <article>
+            <h2>聊天调试</h2>
+            <textarea id="runInput">你好</textarea>
+            <button id="runBtn">运行 Agent</button>
+            <pre id="runOutput"></pre>
+        </article>
+        <article>
+            <h2>审计日志</h2>
+            <button id="auditBtn">刷新审计</button>
+            <pre id="auditOutput"></pre>
+        </article>
+    </section>
+</main>
+<script src="/assets/app.js"></script>
+</body>
+</html>
*** Add File: cm-agent-console/src/main/resources/META-INF/resources/assets/styles.css
+body {
+    margin: 0;
+    font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
+    background: #f6f7f9;
+    color: #1f2937;
+}
+
+.shell {
+    max-width: 1180px;
+    margin: 0 auto;
+    padding: 24px;
+}
+
+.toolbar {
+    display: flex;
+    justify-content: space-between;
+    align-items: center;
+    margin-bottom: 16px;
+}
+
+.grid {
+    display: grid;
+    grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
+    gap: 16px;
+}
+
+article {
+    background: #ffffff;
+    border: 1px solid #d8dee8;
+    border-radius: 8px;
+    padding: 16px;
+}
+
+input, textarea, button {
+    width: 100%;
+    box-sizing: border-box;
+    margin: 6px 0;
+    padding: 10px;
+    border-radius: 6px;
+    border: 1px solid #c8d0dc;
+}
+
+button {
+    background: #14532d;
+    color: white;
+    cursor: pointer;
+}
+
+pre {
+    min-height: 80px;
+    white-space: pre-wrap;
+    background: #f1f5f9;
+    padding: 10px;
+    border-radius: 6px;
+}
*** Add File: cm-agent-console/src/main/resources/META-INF/resources/assets/app.js
+let token = "";
+let agentId = "";
+let toolId = "";
+
+async function request(path, options = {}) {
+    const headers = Object.assign({"Content-Type": "application/json"}, options.headers || {});
+    if (token) {
+        headers.Authorization = "Bearer " + token;
+    }
+    const response = await fetch(path, Object.assign({}, options, {headers}));
+    return response.json();
+}
+
+document.getElementById("loginBtn").onclick = async () => {
+    const result = await request("/api/auth/login", {
+        method: "POST",
+        body: JSON.stringify({username: "admin", password: "admin123456"})
+    });
+    token = result.accessToken;
+    document.getElementById("agentOutput").textContent = "已登录：" + result.displayName;
+};
+
+document.getElementById("createAgentBtn").onclick = async () => {
+    const result = await request("/api/agents", {
+        method: "POST",
+        body: JSON.stringify({
+            name: document.getElementById("agentName").value,
+            systemPrompt: document.getElementById("systemPrompt").value,
+            modelName: "qwen-max"
+        })
+    });
+    agentId = result.id;
+    document.getElementById("agentOutput").textContent = JSON.stringify(result, null, 2);
+};
+
+document.getElementById("createToolBtn").onclick = async () => {
+    const tool = await request("/api/tools", {
+        method: "POST",
+        body: JSON.stringify({name: document.getElementById("toolName").value, description: "回显输入", type: "LOCAL", riskLevel: "LOW"})
+    });
+    toolId = tool.id;
+    const grant = await request("/api/tools/" + toolId + "/grants", {
+        method: "POST",
+        body: JSON.stringify({agentId})
+    });
+    document.getElementById("toolOutput").textContent = JSON.stringify({tool, grant}, null, 2);
+};
+
+document.getElementById("runBtn").onclick = async () => {
+    const result = await request("/api/agents/" + agentId + "/runs", {
+        method: "POST",
+        body: JSON.stringify({input: document.getElementById("runInput").value})
+    });
+    document.getElementById("runOutput").textContent = JSON.stringify(result, null, 2);
+};
+
+document.getElementById("auditBtn").onclick = async () => {
+    const result = await request("/api/audit-events");
+    document.getElementById("auditOutput").textContent = JSON.stringify(result, null, 2);
+};
*** End Patch
```

- [ ] **Step 4: 运行控制台测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=ConsoleSmokeTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交控制台**

Run:

```powershell
git add cm-agent-console cm-agent-server
git commit -m "feat: 添加最小控制台"
```

Expected:

```text
输出包含：feat: 添加最小控制台
```

## Task 11: AgentScope adapter 契约和受控集成路径

**Files:**
- Create: `F:\java\cm-agent\cm-agent-agentscope-adapter\src\main\java\com\cmagent\agentscope\AgentScopeRunSpec.java`
- Create: `F:\java\cm-agent\cm-agent-agentscope-adapter\src\main\java\com\cmagent\agentscope\AgentScopeRuntimeAdapter.java`
- Create: `F:\java\cm-agent\cm-agent-agentscope-adapter\src\test\java\com\cmagent\agentscope\AgentScopeRuntimeAdapterTest.java`

- [ ] **Step 1: 写 adapter 契约测试**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-agentscope-adapter/src/test/java/com/cmagent/agentscope/AgentScopeRuntimeAdapterTest.java
+package com.cmagent.agentscope;
+
+import com.cmagent.api.PrincipalRef;
+import com.cmagent.core.domain.AgentRunRequest;
+import org.junit.jupiter.api.Test;
+
+import java.util.List;
+import java.util.Set;
+import java.util.UUID;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+class AgentScopeRuntimeAdapterTest {
+
+    @Test
+    void mapCmAgentRequestToAgentScopeRunSpec() {
+        UUID tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
+        UUID agentId = UUID.fromString("00000000-0000-0000-0000-000000000201");
+        AgentRunRequest request = new AgentRunRequest(
+                tenantId,
+                agentId,
+                new PrincipalRef(tenantId, "admin", "系统管理员", Set.of("agent:run")),
+                "查询客户状态",
+                List.of()
+        );
+
+        AgentScopeRunSpec spec = new AgentScopeRuntimeAdapter().toRunSpec(request);
+
+        assertThat(spec.tenantId()).isEqualTo(tenantId.toString());
+        assertThat(spec.agentId()).isEqualTo(agentId.toString());
+        assertThat(spec.userInput()).isEqualTo("查询客户状态");
+        assertThat(spec.principalId()).isEqualTo("admin");
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
mvn -q -pl cm-agent-agentscope-adapter -Dtest=AgentScopeRuntimeAdapterTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class AgentScopeRuntimeAdapter
```

- [ ] **Step 3: 实现 adapter 契约层**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRunSpec.java
+package com.cmagent.agentscope;
+
+public record AgentScopeRunSpec(String tenantId, String agentId, String principalId, String userInput) {
+}
*** Add File: cm-agent-agentscope-adapter/src/main/java/com/cmagent/agentscope/AgentScopeRuntimeAdapter.java
+package com.cmagent.agentscope;
+
+import com.cmagent.core.domain.AgentRunRequest;
+import com.cmagent.core.domain.AgentRunResult;
+import com.cmagent.core.domain.RunStatus;
+import com.cmagent.core.runtime.AgentRuntime;
+
+import java.time.Instant;
+import java.util.List;
+import java.util.UUID;
+
+public class AgentScopeRuntimeAdapter implements AgentRuntime {
+
+    public AgentScopeRunSpec toRunSpec(AgentRunRequest request) {
+        return new AgentScopeRunSpec(
+                request.tenantId().toString(),
+                request.agentId().toString(),
+                request.principal().principalId(),
+                request.input()
+        );
+    }
+
+    @Override
+    public AgentRunResult run(AgentRunRequest request) {
+        AgentScopeRunSpec spec = toRunSpec(request);
+        Instant now = Instant.now();
+        return new AgentRunResult(
+                UUID.randomUUID(),
+                RunStatus.FAILED,
+                "",
+                List.of(),
+                now,
+                now,
+                "AgentScope 真实运行桥接尚未启用，已生成受控运行规格 " + spec.agentId()
+        );
+    }
+}
*** End Patch
```

- [ ] **Step 4: 运行 adapter 测试并确认通过**

Run:

```powershell
mvn -q -pl cm-agent-agentscope-adapter -Dtest=AgentScopeRuntimeAdapterTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: 提交 adapter 契约**

Run:

```powershell
git add cm-agent-agentscope-adapter
git commit -m "feat: 添加 AgentScope adapter 契约"
```

Expected:

```text
输出包含：feat: 添加 AgentScope adapter 契约
```

## Task 12: 示例、Docker Compose、中文生产文档和整体验证

**Files:**
- Create: `F:\java\cm-agent\docker-compose.yml`
- Create: `F:\java\cm-agent\docs\deployment.md`
- Create: `F:\java\cm-agent\docs\operations.md`
- Create: `F:\java\cm-agent\docs\configuration.md`
- Create: `F:\java\cm-agent\docs\release-notes.md`
- Create: `F:\java\cm-agent\cm-agent-examples\starter-local-tool\src\main\java\com\cmagent\examples\LocalToolExampleApplication.java`

- [ ] **Step 1: 写中文生产文档和示例应用**

使用 `apply_patch`：

```diff
*** Begin Patch
*** Add File: docker-compose.yml
+services:
+  mysql:
+    image: mysql:8.4
+    environment:
+      MYSQL_ROOT_PASSWORD: cmagent
+      MYSQL_DATABASE: cm_agent
+    ports:
+      - "3306:3306"
+  postgres:
+    image: postgres:16-alpine
+    environment:
+      POSTGRES_PASSWORD: cmagent
+      POSTGRES_DB: cm_agent
+    ports:
+      - "5432:5432"
*** Add File: docs/deployment.md
+# 部署指南
+
+## 本地构建
+
+```powershell
+mvn -q -DskipTests package
+```
+
+预期输出包含：
+
+```text
+BUILD SUCCESS
+```
+
+## 启动开发数据库
+
+```powershell
+docker compose up -d mysql postgres
+```
+
+预期输出包含：
+
+```text
+Started
+```
+
+## 启动服务端
+
+```powershell
+mvn -pl cm-agent-server spring-boot:run
+```
+
+预期输出包含：
+
+```text
+Started CmAgentServerApplication
+```
*** Add File: docs/operations.md
+# 运维说明
+
+## 健康检查
+
+访问 `GET /actuator/health`。
+
+健康响应示例：
+
+```json
+{"status":"UP"}
+```
+
+## 审计查询
+
+管理员登录后访问 `GET /api/audit-events`。审计记录用于追踪登录、Agent 运行、工具授权和权限拒绝。
*** Add File: docs/configuration.md
+# 配置说明
+
+## 默认配置
+
+`cm-agent.fake-runtime-enabled=true` 表示第一阶段使用 fake runtime 验证链路。
+
+## 模型供应商
+
+DashScope 原生模式和 OpenAI-compatible 模式通过租户级 `model_configs` 表保存。API Key 必须加密或交给 secret provider，接口不能返回明文。
*** Add File: docs/release-notes.md
+# 发布说明
+
+## 0.1.0-SNAPSHOT
+
+第一阶段目标是打通模块化单体纵切：核心接口、Starter、server、console、迁移、认证、工具治理、Agent run 和审计。
*** Add File: cm-agent-examples/starter-local-tool/src/main/java/com/cmagent/examples/LocalToolExampleApplication.java
+package com.cmagent.examples;
+
+import com.cmagent.core.domain.ToolDefinition;
+import com.cmagent.core.domain.ToolRiskLevel;
+import com.cmagent.core.domain.ToolType;
+import com.cmagent.core.tool.ToolRegistry;
+import org.springframework.boot.CommandLineRunner;
+import org.springframework.boot.SpringApplication;
+import org.springframework.boot.autoconfigure.SpringBootApplication;
+import org.springframework.context.annotation.Bean;
+
+import java.util.UUID;
+
+@SpringBootApplication
+public class LocalToolExampleApplication {
+    public static void main(String[] args) {
+        SpringApplication.run(LocalToolExampleApplication.class, args);
+    }
+
+    @Bean
+    CommandLineRunner registerEchoTool(ToolRegistry registry) {
+        return args -> registry.register(
+                new ToolDefinition(
+                        UUID.fromString("00000000-0000-0000-0000-000000000101"),
+                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
+                        "echo",
+                        "回显输入",
+                        ToolType.LOCAL,
+                        "{\"type\":\"object\"}",
+                        ToolRiskLevel.LOW,
+                        true,
+                        "",
+                        "example",
+                        "example"
+                ),
+                request -> new com.cmagent.core.tool.ToolExecutionResult("示例工具收到：" + request.inputJson(), true)
+        );
+    }
+}
*** End Patch
```

- [ ] **Step 2: 运行全量单元和集成测试**

Run:

```powershell
mvn -q test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 3: 构建完整项目**

Run:

```powershell
mvn -q -DskipTests package
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 4: 启动服务端冒烟验证**

Run:

```powershell
mvn -pl cm-agent-server spring-boot:run
```

Expected:

```text
Started CmAgentServerApplication
```

Stop the server with `Ctrl+C` after seeing the expected startup line.

- [ ] **Step 5: 检查中文文档约束**

Run:

```powershell
rg -n "README|部署指南|运维说明|配置说明|发布说明|中文" README.md docs
```

Expected:

```text
README.md:1:# CM Agent
docs\deployment.md:1:# 部署指南
docs\operations.md:1:# 运维说明
docs\configuration.md:1:# 配置说明
docs\release-notes.md:1:# 发布说明
```

- [ ] **Step 6: 提交示例和文档**

Run:

```powershell
git add docker-compose.yml docs README.md cm-agent-examples
git commit -m "docs: 添加中文生产文档和 Starter 示例"
```

Expected:

```text
输出包含：docs: 添加中文生产文档和 Starter 示例
```

## 最终验收命令

- [ ] **Step 1: 检查 AgentScope 类型没有泄漏到 core**

Run:

```powershell
rg -n "agentscope|AgentScope" cm-agent-core
```

Expected:

```text
没有输出
```

- [ ] **Step 2: 检查计划覆盖的生产文档为中文**

Run:

```powershell
rg -n "Deployment[ ]Guide|Operations[ ]Guide|Configuration[ ]Guide|Release[ ]Notes" README.md docs
```

Expected:

```text
没有输出
```

- [ ] **Step 3: 运行全量验证**

Run:

```powershell
mvn -q test
mvn -q -DskipTests package
```

Expected:

```text
BUILD SUCCESS
BUILD SUCCESS
```

## 实施完成后的提交检查

Run:

```powershell
git status --short
git log --oneline -12
```

Expected:

```text
git status --short 没有输出，git log --oneline -12 显示最近 12 个任务提交。
```

## 计划自检

- spec 中的模块结构由 Task 1、Task 7、Task 8、Task 10、Task 11、Task 12 覆盖。
- spec 中的核心领域模型由 Task 2 覆盖。
- spec 中的权限和工具授权由 Task 3 覆盖。
- spec 中的 fake runtime 和 Agent run 纵切由 Task 4、Task 8、Task 9 覆盖。
- spec 中的 MySQL/PostgreSQL 迁移由 Task 5 覆盖。
- spec 中的审计能力由 Task 6 和 Task 9 覆盖。
- spec 中的控制台由 Task 10 覆盖。
- spec 中的 AgentScope 隔离策略由 Task 11 和最终验收命令覆盖。
- spec 中的中文生产文档要求由 Task 12 和最终验收命令覆盖。
