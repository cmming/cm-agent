# Production Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 CM Agent 的 Agent、Tool 和 ToolGrant 管理数据从内存演示存储推进到可配置 JDBC 持久化，并保证 production/prod profile 不会静默使用内存存储。

**Architecture:** 在 `cm-agent-core` 定义 repository 接口，`cm-agent-server` 通过接口访问管理数据，`cm-agent-persistence` 提供 JDBC 实现。local/test 默认使用内存 fallback，`cm-agent.persistence.mode=jdbc` 时启用显式 DataSource、Flyway、JdbcClient 和 JDBC repository。

**Tech Stack:** Java 21、Maven、Spring Boot 3.5.0、Spring JDBC `JdbcClient`、Flyway、Jackson、JUnit 5、Testcontainers PostgreSQL、Spring MockMvc。

---

## File Structure

- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\repository\AgentDefinitionRepository.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\repository\ToolDefinitionRepository.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\repository\ToolGrantRepository.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\config\CmAgentPersistenceProperties.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\config\ServerRepositoryConfiguration.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\config\JdbcPersistenceConfiguration.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\main\java\com\cmagent\persistence\JdbcAgentDefinitionRepository.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\main\java\com\cmagent\persistence\JdbcToolDefinitionRepository.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\main\java\com\cmagent\persistence\JdbcToolGrantRepository.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\JdbcAgentDefinitionRepositoryTest.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\JdbcToolDefinitionRepositoryTest.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\JdbcToolGrantRepositoryTest.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\AgentControllerJdbcPersistenceTest.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\RunControllerJdbcPersistenceTest.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\store\InMemoryPlatformStore.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\AgentController.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\ToolController.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\RunController.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\resources\application.yml`
- Modify: `F:\java\cm-agent\cm-agent-server\pom.xml`
- Modify: `F:\java\cm-agent\cm-agent-persistence\pom.xml`
- Modify: `F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\config\ApplicationProfileConfigurationTest.java`

## Task 1: Repository Contracts And Memory Fallback Wiring

**Files:**
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\repository\AgentDefinitionRepository.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\repository\ToolDefinitionRepository.java`
- Create: `F:\java\cm-agent\cm-agent-core\src\main\java\com\cmagent\core\repository\ToolGrantRepository.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\config\CmAgentPersistenceProperties.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\config\ServerRepositoryConfiguration.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\store\InMemoryPlatformStore.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\resources\application.yml`
- Modify: `F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\config\ApplicationProfileConfigurationTest.java`

- [ ] **Step 1: Write failing production persistence guard test**

Append this test method to `ApplicationProfileConfigurationTest` before `assertTestProfileLoaded`:

```java
    @Test
    void productionProfileRejectsMemoryPersistenceModeWhenJwtSecretExists() {
        webContextRunner
                .withPropertyValues("spring.profiles.active=production")
                .withPropertyValues("cm-agent.security.jwt-secret=" + TEST_JWT_SECRET)
                .withPropertyValues("cm-agent.persistence.mode=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("production/prod profile 必须使用 jdbc 持久化模式");
                });
    }
```

- [ ] **Step 2: Run the guard test to verify RED**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=ApplicationProfileConfigurationTest#productionProfileRejectsMemoryPersistenceModeWhenJwtSecretExists test
```

Expected:

```text
Expecting actual:
  <Started application ...>
to have failed
```

- [ ] **Step 3: Add repository interfaces**

Create `cm-agent-core/src/main/java/com/cmagent/core/repository/AgentDefinitionRepository.java`:

```java
package com.cmagent.core.repository;

import com.cmagent.core.domain.AgentDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentDefinitionRepository {
    AgentDefinition save(AgentDefinition agent);

    Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId);

    List<AgentDefinition> listByTenant(UUID tenantId);

    AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId);
}
```

Create `cm-agent-core/src/main/java/com/cmagent/core/repository/ToolDefinitionRepository.java`:

```java
package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToolDefinitionRepository {
    ToolDefinition save(ToolDefinition tool);

    Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId);

    List<ToolDefinition> listByTenant(UUID tenantId);
}
```

Create `cm-agent-core/src/main/java/com/cmagent/core/repository/ToolGrantRepository.java`:

```java
package com.cmagent.core.repository;

import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

public interface ToolGrantRepository {
    ToolGrant save(ToolGrant grant);

    List<ToolGrant> listByTenant(UUID tenantId);

    List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId);

    List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId);
}
```

- [ ] **Step 4: Add persistence mode properties**

Create `cm-agent-server/src/main/java/com/cmagent/server/config/CmAgentPersistenceProperties.java`:

```java
package com.cmagent.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@ConfigurationProperties(prefix = "cm-agent.persistence")
public class CmAgentPersistenceProperties {
    private Mode mode = Mode.MEMORY;
    private final Jdbc jdbc = new Jdbc();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.MEMORY : mode;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public void validate(Environment environment) {
        if (hasProductionProfile(environment) && mode != Mode.JDBC) {
            throw new IllegalStateException("production/prod profile 必须使用 jdbc 持久化模式，不能使用内存存储");
        }
        if (mode == Mode.JDBC && jdbc.getUrl().isBlank()) {
            throw new IllegalStateException("启用 jdbc 持久化模式时必须配置 cm-agent.persistence.jdbc.url");
        }
    }

    private boolean hasProductionProfile(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "production".equalsIgnoreCase(profile) || "prod".equalsIgnoreCase(profile));
    }

    public enum Mode {
        MEMORY,
        JDBC
    }

    public static class Jdbc {
        private String url = "";
        private String username = "";
        private String password = "";
        private String driverClassName = "";

        public String getUrl() {
            return url == null ? "" : url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username == null ? "" : username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password == null ? "" : password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName == null ? "" : driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }
    }
}
```

- [ ] **Step 5: Replace implicit store component with explicit memory repository configuration**

Remove `import org.springframework.stereotype.Component;` and `@Component` from `cm-agent-server/src/main/java/com/cmagent/server/store/InMemoryPlatformStore.java`. Leave the class declaration as:

```java
public class InMemoryPlatformStore implements AuditEventRepository {
```

Create `cm-agent-server/src/main/java/com/cmagent/server/config/ServerRepositoryConfiguration.java`:

```java
package com.cmagent.server.config;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.server.store.InMemoryPlatformStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(CmAgentPersistenceProperties.class)
public class ServerRepositoryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    InMemoryPlatformStore inMemoryPlatformStore() {
        return new InMemoryPlatformStore();
    }

    @Bean
    Object persistenceModeValidator(CmAgentPersistenceProperties properties, Environment environment) {
        properties.validate(environment);
        return new Object();
    }

    @Bean
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    AgentDefinitionRepository inMemoryAgentDefinitionRepository(InMemoryPlatformStore store) {
        return new AgentDefinitionRepository() {
            @Override
            public AgentDefinition save(AgentDefinition agent) {
                return store.saveAgent(agent);
            }

            @Override
            public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
                return store.findAgent(tenantId, agentId);
            }

            @Override
            public List<AgentDefinition> listByTenant(UUID tenantId) {
                return store.listAgents(tenantId);
            }

            @Override
            public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
                return store.addToolToAgent(tenantId, agentId, toolId);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    ToolDefinitionRepository inMemoryToolDefinitionRepository(InMemoryPlatformStore store) {
        return new ToolDefinitionRepository() {
            @Override
            public ToolDefinition save(ToolDefinition tool) {
                return store.saveTool(tool);
            }

            @Override
            public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
                return store.findTool(tenantId, toolId);
            }

            @Override
            public List<ToolDefinition> listByTenant(UUID tenantId) {
                return store.listTools(tenantId);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "memory", matchIfMissing = true)
    ToolGrantRepository inMemoryToolGrantRepository(InMemoryPlatformStore store) {
        return new ToolGrantRepository() {
            @Override
            public ToolGrant save(ToolGrant grant) {
                return store.saveGrant(grant);
            }

            @Override
            public List<ToolGrant> listByTenant(UUID tenantId) {
                return store.listGrants(tenantId);
            }

            @Override
            public List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId) {
                return store.listGrants(tenantId, agentId);
            }

            @Override
            public List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId) {
                return store.listGrants(tenantId, agentId, toolId);
            }
        };
    }
}
```

- [ ] **Step 6: Add persistence mode defaults to application configuration**

Add this block under `cm-agent:` in `cm-agent-server/src/main/resources/application.yml`:

```yaml
  persistence:
    mode: ${CM_AGENT_PERSISTENCE_MODE:memory}
    jdbc:
      url: ${CM_AGENT_JDBC_URL:}
      username: ${CM_AGENT_JDBC_USERNAME:}
      password: ${CM_AGENT_JDBC_PASSWORD:}
      driver-class-name: ${CM_AGENT_JDBC_DRIVER_CLASS_NAME:}
```

- [ ] **Step 7: Run guard and existing web tests to verify GREEN**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=ApplicationProfileConfigurationTest,RunControllerTest,AuthControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 8: Commit repository contracts and memory fallback**

Run:

```powershell
git add cm-agent-core cm-agent-server
git commit -m "feat: 添加管理数据 repository 契约"
```

Expected:

```text
[<branch> <sha>] feat: 添加管理数据 repository 契约
```

## Task 2: JDBC AgentDefinition Repository

**Files:**
- Create: `F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\JdbcAgentDefinitionRepositoryTest.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\main\java\com\cmagent\persistence\JdbcAgentDefinitionRepository.java`
- Modify: `F:\java\cm-agent\cm-agent-persistence\pom.xml`

- [ ] **Step 1: Write failing JDBC Agent repository test**

Create `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcAgentDefinitionRepositoryTest.java`:

```java
package com.cmagent.persistence;

import com.cmagent.core.domain.AgentDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcAgentDefinitionRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MODEL_PROVIDER_A = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID MODEL_PROVIDER_B = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000401");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcAgentDefinitionRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        seedTenantAndModelConfigs(dataSource);
        repository = new JdbcAgentDefinitionRepository(JdbcClient.create(dataSource), new ObjectMapper());
    }

    @Test
    void saveFindAndListByTenant() {
        AgentDefinition agentA = agent(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                TENANT_A,
                MODEL_PROVIDER_A,
                "企业助手",
                List.of(TOOL_ID)
        );
        AgentDefinition agentB = agent(
                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                TENANT_B,
                MODEL_PROVIDER_B,
                "其他租户助手",
                List.of()
        );

        repository.save(agentA);
        repository.save(agentB);

        assertThat(repository.findByTenantAndId(TENANT_A, agentA.id())).contains(agentA);
        assertThat(repository.findByTenantAndId(TENANT_B, agentA.id())).isEmpty();
        assertThat(repository.listByTenant(TENANT_A))
                .extracting(AgentDefinition::id)
                .containsExactly(agentA.id());
    }

    @Test
    void addToolToAgentPersistsUniqueToolId() {
        UUID agentId = UUID.fromString("10000000-0000-0000-0000-000000000003");
        UUID newToolId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        repository.save(agent(agentId, TENANT_A, MODEL_PROVIDER_A, "工具助手", List.of(TOOL_ID)));

        repository.addToolToAgent(TENANT_A, agentId, newToolId);
        repository.addToolToAgent(TENANT_A, agentId, newToolId);

        AgentDefinition saved = repository.findByTenantAndId(TENANT_A, agentId).orElseThrow();
        assertThat(saved.toolIds()).containsExactly(TOOL_ID, newToolId);
    }

    private static AgentDefinition agent(UUID id, UUID tenantId, UUID modelProviderId, String name, List<UUID> toolIds) {
        return new AgentDefinition(
                id,
                tenantId,
                name,
                "用于持久化测试",
                "你是企业助手",
                modelProviderId,
                "qwen-max",
                0.2d,
                6,
                true,
                toolIds,
                "tester",
                "tester"
        );
    }

    private static void seedTenantAndModelConfigs(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Instant now = Instant.parse("2026-06-26T00:00:00Z");
        insertTenant(jdbcClient, TENANT_A, "tenant-a", "租户A", now);
        insertTenant(jdbcClient, TENANT_B, "tenant-b", "租户B", now);
        insertModelConfig(jdbcClient, MODEL_PROVIDER_A, TENANT_A, now);
        insertModelConfig(jdbcClient, MODEL_PROVIDER_B, TENANT_B, now);
    }

    private static void insertTenant(JdbcClient jdbcClient, UUID tenantId, String code, String name, Instant now) {
        jdbcClient.sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, true, :createdAt)
                        """)
                .param("id", tenantId.toString())
                .param("code", code)
                .param("name", name)
                .param("createdAt", now)
                .update();
    }

    private static void insertModelConfig(JdbcClient jdbcClient, UUID modelProviderId, UUID tenantId, Instant now) {
        jdbcClient.sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url, model_name,
                            encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, 'OPENAI_COMPATIBLE', '默认模型', 'https://example.invalid',
                            'qwen-max', 'not-configured', true, :createdAt
                        )
                        """)
                .param("id", modelProviderId.toString())
                .param("tenantId", tenantId.toString())
                .param("createdAt", now)
                .update();
    }
}
```

- [ ] **Step 2: Run the Agent repository test to verify RED**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=JdbcAgentDefinitionRepositoryTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class JdbcAgentDefinitionRepository
```

- [ ] **Step 3: Add Jackson dependency to persistence module**

Add this dependency to `cm-agent-persistence/pom.xml` inside `<dependencies>`:

```xml
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
```

- [ ] **Step 4: Implement JDBC Agent repository**

Create `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcAgentDefinitionRepository.java`:

```java
package com.cmagent.persistence;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcAgentDefinitionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentDefinition save(AgentDefinition agent) {
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO agent_definitions (
                            id, tenant_id, name, description, system_prompt, model_provider_id,
                            model_name, temperature, max_iterations, enabled, tool_ids_json,
                            created_by, updated_by, created_at, updated_at
                        ) VALUES (
                            :id, :tenantId, :name, :description, :systemPrompt, :modelProviderId,
                            :modelName, :temperature, :maxIterations, :enabled, :toolIdsJson,
                            :createdBy, :updatedBy, :createdAt, :updatedAt
                        )
                        """)
                .param("id", agent.id().toString())
                .param("tenantId", agent.tenantId().toString())
                .param("name", agent.name())
                .param("description", agent.description())
                .param("systemPrompt", agent.systemPrompt())
                .param("modelProviderId", agent.modelProviderId().toString())
                .param("modelName", agent.modelName())
                .param("temperature", agent.temperature())
                .param("maxIterations", agent.maxIterations())
                .param("enabled", agent.enabled())
                .param("toolIdsJson", writeToolIds(agent.toolIds()))
                .param("createdBy", agent.createdBy())
                .param("updatedBy", agent.updatedBy())
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        return agent;
    }

    @Override
    public Optional<AgentDefinition> findByTenantAndId(UUID tenantId, UUID agentId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, name, description, system_prompt, model_provider_id,
                               model_name, temperature, max_iterations, enabled, tool_ids_json,
                               created_by, updated_by
                        FROM agent_definitions
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", agentId.toString())
                .query(this::mapAgent)
                .optional();
    }

    @Override
    public List<AgentDefinition> listByTenant(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, name, description, system_prompt, model_provider_id,
                               model_name, temperature, max_iterations, enabled, tool_ids_json,
                               created_by, updated_by
                        FROM agent_definitions
                        WHERE tenant_id = :tenantId
                        ORDER BY name ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .query(this::mapAgent)
                .list();
    }

    @Override
    public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
        AgentDefinition agent = findByTenantAndId(tenantId, agentId)
                .orElseThrow(() -> new NoSuchElementException("Agent 不存在"));
        if (agent.toolIds().contains(toolId)) {
            return agent;
        }
        List<UUID> mergedToolIds = new java.util.ArrayList<>(agent.toolIds());
        mergedToolIds.add(toolId);
        AgentDefinition updated = new AgentDefinition(
                agent.id(),
                agent.tenantId(),
                agent.name(),
                agent.description(),
                agent.systemPrompt(),
                agent.modelProviderId(),
                agent.modelName(),
                agent.temperature(),
                agent.maxIterations(),
                agent.enabled(),
                mergedToolIds,
                agent.createdBy(),
                agent.updatedBy()
        );
        jdbcClient.sql("""
                        UPDATE agent_definitions
                        SET tool_ids_json = :toolIdsJson, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("toolIdsJson", writeToolIds(updated.toolIds()))
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("tenantId", tenantId.toString())
                .param("id", agentId.toString())
                .update();
        return updated;
    }

    private AgentDefinition mapAgent(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AgentDefinition(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("system_prompt"),
                UUID.fromString(rs.getString("model_provider_id")),
                rs.getString("model_name"),
                rs.getDouble("temperature"),
                rs.getInt("max_iterations"),
                rs.getBoolean("enabled"),
                readToolIds(rs.getString("tool_ids_json")),
                rs.getString("created_by"),
                rs.getString("updated_by")
        );
    }

    private String writeToolIds(List<UUID> toolIds) {
        try {
            List<String> values = toolIds.stream().map(UUID::toString).toList();
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("toolIds 序列化失败", e);
        }
    }

    private List<UUID> readToolIds(String json) {
        try {
            String[] values = objectMapper.readValue(json, String[].class);
            return Arrays.stream(values).map(UUID::fromString).toList();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("toolIds 反序列化失败", e);
        }
    }
}
```

- [ ] **Step 5: Run the Agent repository test to verify GREEN**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=JdbcAgentDefinitionRepositoryTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit JDBC Agent repository**

Run:

```powershell
git add cm-agent-persistence
git commit -m "feat: 添加 Agent JDBC repository"
```

Expected:

```text
[<branch> <sha>] feat: 添加 Agent JDBC repository
```

## Task 3: JDBC Mode Wiring And AgentController Repository Integration

**Files:**
- Create: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\config\JdbcPersistenceConfiguration.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\AgentControllerJdbcPersistenceTest.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\AgentController.java`
- Modify: `F:\java\cm-agent\cm-agent-server\pom.xml`

- [ ] **Step 1: Write failing JDBC Agent controller integration test**

Add these test dependencies to `cm-agent-server/pom.xml` inside `<dependencies>`:

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

Create `cm-agent-server/src/test/java/com/cmagent/server/web/AgentControllerJdbcPersistenceTest.java`:

```java
package com.cmagent.server.web;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentControllerJdbcPersistenceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void jdbcProperties(DynamicPropertyRegistry registry) {
        registry.add("cm-agent.persistence.mode", () -> "jdbc");
        registry.add("cm-agent.persistence.jdbc.url", postgres::getJdbcUrl);
        registry.add("cm-agent.persistence.jdbc.username", postgres::getUsername);
        registry.add("cm-agent.persistence.jdbc.password", postgres::getPassword);
        registry.add("cm-agent.persistence.jdbc.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AgentDefinitionRepository agentRepository;

    @Test
    void createAgentPersistsToJdbcAndCanBeListed() throws Exception {
        String token = token(TENANT_ID, "admin");

        String response = mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"企业助手","systemPrompt":"你是企业助手","modelName":"qwen-max"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("企业助手"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String agentId = JsonPath.read(response, "$.id");
        assertThat(agentRepository.findByTenantAndId(TENANT_ID, UUID.fromString(agentId))).isPresent();

        mockMvc.perform(get("/api/agents")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(agentId))
                .andExpect(jsonPath("$[0].toolIds.length()").value(0));
    }

    @Test
    void getAgentRejectsCrossTenantRead() throws Exception {
        String token = token(TENANT_ID, "admin");
        String otherTenantToken = token(OTHER_TENANT_ID, "other-admin");

        String response = mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"隔离助手","systemPrompt":"你是企业助手","modelName":"qwen-max"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String agentId = JsonPath.read(response, "$.id");

        mockMvc.perform(get("/api/agents/{id}", agentId)
                        .header("Authorization", bearer(otherTenantToken)))
                .andExpect(status().isNotFound());
    }

    private String token(UUID tenantId, String principalId) {
        return jwtService.createToken(tenantId, principalId, "系统管理员", List.of(
                "agent:run",
                "agent:read",
                "agent:write",
                "tool:read",
                "tool:grant",
                "audit:read",
                "apikey:write"
        ));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
```

- [ ] **Step 2: Run the JDBC Agent controller test to verify RED**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=AgentControllerJdbcPersistenceTest test
```

Expected:

```text
Parameter 0 of method jdbcProperties... or No qualifying bean of type 'AgentDefinitionRepository'
```

- [ ] **Step 3: Add explicit JDBC persistence configuration**

Create `cm-agent-server/src/main/java/com/cmagent/server/config/JdbcPersistenceConfiguration.java`:

```java
package com.cmagent.server.config;

import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.persistence.JdbcAgentDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Instant;

@Configuration
@ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "jdbc")
public class JdbcPersistenceConfiguration {
    private static final String DEFAULT_TENANT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String DEFAULT_MODEL_PROVIDER_ID = "00000000-0000-0000-0000-000000000301";

    @Bean(destroyMethod = "close")
    DataSource cmAgentDataSource(CmAgentPersistenceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getJdbc().getUrl());
        dataSource.setUsername(properties.getJdbc().getUsername());
        dataSource.setPassword(properties.getJdbc().getPassword());
        if (!properties.getJdbc().getDriverClassName().isBlank()) {
            dataSource.setDriverClassName(properties.getJdbc().getDriverClassName());
        }
        dataSource.setPoolName("cm-agent-jdbc");
        return dataSource;
    }

    @Bean
    Flyway cmAgentFlyway(DataSource cmAgentDataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(cmAgentDataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    JdbcClient cmAgentJdbcClient(DataSource cmAgentDataSource, Flyway cmAgentFlyway) {
        return JdbcClient.create(cmAgentDataSource);
    }

    @Bean
    ObjectMapper cmAgentPersistenceObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    AgentDefinitionRepository jdbcAgentDefinitionRepository(JdbcClient cmAgentJdbcClient, ObjectMapper cmAgentPersistenceObjectMapper) {
        return new JdbcAgentDefinitionRepository(cmAgentJdbcClient, cmAgentPersistenceObjectMapper);
    }

    @Bean
    ApplicationRunner defaultTenantDataInitializer(JdbcClient cmAgentJdbcClient, Flyway cmAgentFlyway) {
        return args -> {
            Instant now = Instant.now();
            cmAgentJdbcClient.sql("""
                            INSERT INTO tenants (id, code, name, enabled, created_at)
                            SELECT :id, :code, :name, true, :createdAt
                            WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE id = :id)
                            """)
                    .param("id", DEFAULT_TENANT_ID)
                    .param("code", "default")
                    .param("name", "默认租户")
                    .param("createdAt", now)
                    .update();

            cmAgentJdbcClient.sql("""
                            INSERT INTO model_configs (
                                id, tenant_id, provider_type, display_name, base_url, model_name,
                                encrypted_api_key, enabled, created_at
                            )
                            SELECT :id, :tenantId, 'OPENAI_COMPATIBLE', '默认模型', 'https://example.invalid',
                                   'qwen-max', 'not-configured', true, :createdAt
                            WHERE NOT EXISTS (SELECT 1 FROM model_configs WHERE id = :id)
                            """)
                    .param("id", DEFAULT_MODEL_PROVIDER_ID)
                    .param("tenantId", DEFAULT_TENANT_ID)
                    .param("createdAt", now)
                    .update();
        };
    }
}
```

- [ ] **Step 4: Replace AgentController with repository-based implementation**

Replace `cm-agent-server/src/main/java/com/cmagent/server/web/AgentController.java` with:

```java
package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    private final AgentDefinitionRepository agentRepository;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;

    public AgentController(AgentDefinitionRepository agentRepository,
                           PermissionEvaluator permissionEvaluator,
                           AuditAppender auditAppender) {
        this.agentRepository = agentRepository;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
    }

    @GetMapping
    public List<AgentDefinition> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "AGENT", "list");
        return agentRepository.listByTenant(principal.tenantId());
    }

    @GetMapping("/{id}")
    public AgentDefinition get(@PathVariable("id") UUID id, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:read", "AGENT", id.toString());
        return agentRepository.findByTenantAndId(principal.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
    }

    @PostMapping
    public AgentDefinition create(@Valid @RequestBody AgentCreateRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:write", "AGENT", "create");
        AgentDefinition agent = new AgentDefinition(
                UUID.randomUUID(),
                principal.tenantId(),
                request.name(),
                "",
                request.systemPrompt(),
                MODEL_PROVIDER_ID,
                request.modelName(),
                0.2d,
                6,
                true,
                List.of(),
                principal.principalId(),
                principal.principalId()
        );
        AgentDefinition savedAgent = agentRepository.save(agent);
        auditAppender.append(
                principal.tenantId(),
                principal.principalId(),
                "AGENT_CREATE",
                "AGENT",
                savedAgent.id().toString(),
                "SUCCEEDED",
                "Agent 创建成功"
        );
        return savedAgent;
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission, String resourceType, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, resourceType, resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    public record AgentCreateRequest(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.NotBlank String systemPrompt,
            @jakarta.validation.constraints.NotBlank String modelName
    ) {
    }
}
```

- [ ] **Step 5: Run JDBC Agent controller and existing run tests to verify GREEN**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=AgentControllerJdbcPersistenceTest,RunControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 6: Commit JDBC mode wiring and Agent controller integration**

Run:

```powershell
git add cm-agent-server
git commit -m "feat: 启用 Agent JDBC 持久化模式"
```

Expected:

```text
[<branch> <sha>] feat: 启用 Agent JDBC 持久化模式
```

## Task 4: JDBC ToolDefinition Repository

**Files:**
- Create: `F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\JdbcToolDefinitionRepositoryTest.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\main\java\com\cmagent\persistence\JdbcToolDefinitionRepository.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\config\JdbcPersistenceConfiguration.java`

- [ ] **Step 1: Write failing JDBC Tool repository test**

Create `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcToolDefinitionRepositoryTest.java`:

```java
package com.cmagent.persistence;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcToolDefinitionRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcToolDefinitionRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        seedTenants(dataSource);
        repository = new JdbcToolDefinitionRepository(JdbcClient.create(dataSource));
    }

    @Test
    void saveFindAndListByTenant() {
        ToolDefinition toolA = tool(UUID.fromString("20000000-0000-0000-0000-000000000001"), TENANT_A, "echo");
        ToolDefinition toolB = tool(UUID.fromString("20000000-0000-0000-0000-000000000002"), TENANT_B, "calc");

        repository.save(toolA);
        repository.save(toolB);

        assertThat(repository.findByTenantAndId(TENANT_A, toolA.id())).contains(toolA);
        assertThat(repository.findByTenantAndId(TENANT_B, toolA.id())).isEmpty();
        assertThat(repository.listByTenant(TENANT_A)).extracting(ToolDefinition::id).containsExactly(toolA.id());
    }

    private static ToolDefinition tool(UUID id, UUID tenantId, String name) {
        return new ToolDefinition(
                id,
                tenantId,
                name,
                "回显输入",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "tester",
                "tester"
        );
    }

    private static void seedTenants(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Instant now = Instant.parse("2026-06-26T00:00:00Z");
        insertTenant(jdbcClient, TENANT_A, "tenant-a", "租户A", now);
        insertTenant(jdbcClient, TENANT_B, "tenant-b", "租户B", now);
    }

    private static void insertTenant(JdbcClient jdbcClient, UUID tenantId, String code, String name, Instant now) {
        jdbcClient.sql("""
                        INSERT INTO tenants (id, code, name, enabled, created_at)
                        VALUES (:id, :code, :name, true, :createdAt)
                        """)
                .param("id", tenantId.toString())
                .param("code", code)
                .param("name", name)
                .param("createdAt", now)
                .update();
    }
}
```

- [ ] **Step 2: Run the Tool repository test to verify RED**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=JdbcToolDefinitionRepositoryTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class JdbcToolDefinitionRepository
```

- [ ] **Step 3: Implement JDBC Tool repository**

Create `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcToolDefinitionRepository.java`:

```java
package com.cmagent.persistence;

import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.ToolDefinitionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcToolDefinitionRepository implements ToolDefinitionRepository {
    private final JdbcClient jdbcClient;

    public JdbcToolDefinitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ToolDefinition save(ToolDefinition tool) {
        Instant now = Instant.now();
        jdbcClient.sql("""
                        INSERT INTO tool_definitions (
                            id, tenant_id, name, description, type, input_schema, risk_level,
                            enabled, endpoint, created_by, updated_by, created_at, updated_at
                        ) VALUES (
                            :id, :tenantId, :name, :description, :type, :inputSchema, :riskLevel,
                            :enabled, :endpoint, :createdBy, :updatedBy, :createdAt, :updatedAt
                        )
                        """)
                .param("id", tool.id().toString())
                .param("tenantId", tool.tenantId().toString())
                .param("name", tool.name())
                .param("description", tool.description())
                .param("type", tool.type().name())
                .param("inputSchema", tool.inputSchema())
                .param("riskLevel", tool.riskLevel().name())
                .param("enabled", tool.enabled())
                .param("endpoint", tool.endpoint())
                .param("createdBy", tool.createdBy())
                .param("updatedBy", tool.updatedBy())
                .param("createdAt", Timestamp.from(now))
                .param("updatedAt", Timestamp.from(now))
                .update();
        return tool;
    }

    @Override
    public Optional<ToolDefinition> findByTenantAndId(UUID tenantId, UUID toolId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, name, description, type, input_schema, risk_level,
                               enabled, endpoint, created_by, updated_by
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.toString())
                .param("id", toolId.toString())
                .query(this::mapTool)
                .optional();
    }

    @Override
    public List<ToolDefinition> listByTenant(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, name, description, type, input_schema, risk_level,
                               enabled, endpoint, created_by, updated_by
                        FROM tool_definitions
                        WHERE tenant_id = :tenantId
                        ORDER BY name ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .query(this::mapTool)
                .list();
    }

    private ToolDefinition mapTool(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ToolDefinition(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getString("description"),
                ToolType.valueOf(rs.getString("type")),
                rs.getString("input_schema"),
                ToolRiskLevel.valueOf(rs.getString("risk_level")),
                rs.getBoolean("enabled"),
                rs.getString("endpoint"),
                rs.getString("created_by"),
                rs.getString("updated_by")
        );
    }
}
```

- [ ] **Step 4: Register Tool JDBC repository in JDBC mode**

Add this import to `JdbcPersistenceConfiguration.java`:

```java
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.persistence.JdbcToolDefinitionRepository;
```

Add this bean method after `jdbcAgentDefinitionRepository`:

```java
    @Bean
    ToolDefinitionRepository jdbcToolDefinitionRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcToolDefinitionRepository(cmAgentJdbcClient);
    }
```

- [ ] **Step 5: Run Tool repository and server smoke tests**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=JdbcToolDefinitionRepositoryTest test
mvn -q -pl cm-agent-server -Dtest=AgentControllerJdbcPersistenceTest test
```

Expected:

```text
BUILD SUCCESS
BUILD SUCCESS
```

- [ ] **Step 6: Commit JDBC Tool repository**

Run:

```powershell
git add cm-agent-persistence cm-agent-server
git commit -m "feat: 添加 Tool JDBC repository"
```

Expected:

```text
[<branch> <sha>] feat: 添加 Tool JDBC repository
```

## Task 5: JDBC ToolGrant Repository And Run Path Integration

**Files:**
- Create: `F:\java\cm-agent\cm-agent-persistence\src\test\java\com\cmagent\persistence\JdbcToolGrantRepositoryTest.java`
- Create: `F:\java\cm-agent\cm-agent-persistence\src\main\java\com\cmagent\persistence\JdbcToolGrantRepository.java`
- Create: `F:\java\cm-agent\cm-agent-server\src\test\java\com\cmagent\server\web\RunControllerJdbcPersistenceTest.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\config\JdbcPersistenceConfiguration.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\ToolController.java`
- Modify: `F:\java\cm-agent\cm-agent-server\src\main\java\com\cmagent\server\web\RunController.java`

- [ ] **Step 1: Write failing JDBC Grant repository test**

Create `cm-agent-persistence/src/test/java/com/cmagent/persistence/JdbcToolGrantRepositoryTest.java`:

```java
package com.cmagent.persistence;

import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcToolGrantRepositoryTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MODEL_PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID AGENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TOOL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcToolGrantRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        seedData(dataSource);
        repository = new JdbcToolGrantRepository(JdbcClient.create(dataSource));
    }

    @Test
    void saveIsIdempotentAndListsByTenantAndAgent() {
        ToolGrant grant = new ToolGrant(TENANT_ID, TOOL_ID, AGENT_ID, null, true);

        repository.save(grant);
        repository.save(grant);

        assertThat(repository.listByTenant(TENANT_ID)).containsExactly(grant);
        assertThat(repository.listByTenantAndAgent(TENANT_ID, AGENT_ID)).containsExactly(grant);
        assertThat(repository.listByTenantAgentAndTool(TENANT_ID, AGENT_ID, TOOL_ID)).containsExactly(grant);
    }

    private static void seedData(DataSource dataSource) {
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        Instant now = Instant.parse("2026-06-26T00:00:00Z");
        jdbcClient.sql("INSERT INTO tenants (id, code, name, enabled, created_at) VALUES (:id, 'default', '默认租户', true, :createdAt)")
                .param("id", TENANT_ID.toString())
                .param("createdAt", now)
                .update();
        jdbcClient.sql("""
                        INSERT INTO model_configs (
                            id, tenant_id, provider_type, display_name, base_url, model_name,
                            encrypted_api_key, enabled, created_at
                        ) VALUES (
                            :id, :tenantId, 'OPENAI_COMPATIBLE', '默认模型', 'https://example.invalid',
                            'qwen-max', 'not-configured', true, :createdAt
                        )
                        """)
                .param("id", MODEL_PROVIDER_ID.toString())
                .param("tenantId", TENANT_ID.toString())
                .param("createdAt", now)
                .update();
        new JdbcAgentDefinitionRepository(JdbcClient.create(dataSource), new ObjectMapper()).save(new AgentDefinition(
                AGENT_ID,
                TENANT_ID,
                "企业助手",
                "",
                "你是企业助手",
                MODEL_PROVIDER_ID,
                "qwen-max",
                0.2d,
                6,
                true,
                List.of(),
                "tester",
                "tester"
        ));
        new JdbcToolDefinitionRepository(JdbcClient.create(dataSource)).save(new ToolDefinition(
                TOOL_ID,
                TENANT_ID,
                "echo",
                "回显输入",
                ToolType.LOCAL,
                "{\"type\":\"object\"}",
                ToolRiskLevel.LOW,
                true,
                "",
                "tester",
                "tester"
        ));
    }
}
```

- [ ] **Step 2: Run the Grant repository test to verify RED**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=JdbcToolGrantRepositoryTest test
```

Expected:

```text
COMPILATION ERROR
cannot find symbol
  symbol:   class JdbcToolGrantRepository
```

- [ ] **Step 3: Implement JDBC Grant repository**

Create `cm-agent-persistence/src/main/java/com/cmagent/persistence/JdbcToolGrantRepository.java`:

```java
package com.cmagent.persistence;

import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.ToolGrantRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcToolGrantRepository implements ToolGrantRepository {
    private final JdbcClient jdbcClient;

    public JdbcToolGrantRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public ToolGrant save(ToolGrant grant) {
        List<ToolGrant> existing = listByTenantAgentAndTool(grant.tenantId(), grant.agentId(), grant.toolId());
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        jdbcClient.sql("""
                        INSERT INTO tool_grants (id, tenant_id, tool_id, agent_id, role_code, granted, created_at)
                        VALUES (:id, :tenantId, :toolId, :agentId, :roleCode, :granted, :createdAt)
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", grant.tenantId().toString())
                .param("toolId", grant.toolId().toString())
                .param("agentId", grant.agentId().toString())
                .param("roleCode", grant.roleCode())
                .param("granted", grant.granted())
                .param("createdAt", Timestamp.from(Instant.now()))
                .update();
        return grant;
    }

    @Override
    public List<ToolGrant> listByTenant(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tool_id, agent_id, role_code, granted
                        FROM tool_grants
                        WHERE tenant_id = :tenantId
                        ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .query(this::mapGrant)
                .list();
    }

    @Override
    public List<ToolGrant> listByTenantAndAgent(UUID tenantId, UUID agentId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tool_id, agent_id, role_code, granted
                        FROM tool_grants
                        WHERE tenant_id = :tenantId AND agent_id = :agentId
                        ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .query(this::mapGrant)
                .list();
    }

    @Override
    public List<ToolGrant> listByTenantAgentAndTool(UUID tenantId, UUID agentId, UUID toolId) {
        return jdbcClient.sql("""
                        SELECT tenant_id, tool_id, agent_id, role_code, granted
                        FROM tool_grants
                        WHERE tenant_id = :tenantId AND agent_id = :agentId AND tool_id = :toolId
                        ORDER BY created_at ASC, id ASC
                        """)
                .param("tenantId", tenantId.toString())
                .param("agentId", agentId.toString())
                .param("toolId", toolId.toString())
                .query(this::mapGrant)
                .list();
    }

    private ToolGrant mapGrant(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ToolGrant(
                UUID.fromString(rs.getString("tenant_id")),
                UUID.fromString(rs.getString("tool_id")),
                UUID.fromString(rs.getString("agent_id")),
                rs.getString("role_code"),
                rs.getBoolean("granted")
        );
    }
}
```

- [ ] **Step 4: Register Grant JDBC repository**

Add these imports to `JdbcPersistenceConfiguration.java`:

```java
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.persistence.JdbcToolGrantRepository;
```

Add this bean method after `jdbcToolDefinitionRepository`:

```java
    @Bean
    ToolGrantRepository jdbcToolGrantRepository(JdbcClient cmAgentJdbcClient) {
        return new JdbcToolGrantRepository(cmAgentJdbcClient);
    }
```

- [ ] **Step 5: Replace ToolController with repository-based implementation**

Replace `cm-agent-server/src/main/java/com/cmagent/server/web/ToolController.java` with:

```java
package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.domain.ToolRiskLevel;
import com.cmagent.core.domain.ToolType;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final AgentDefinitionRepository agentRepository;
    private final ToolDefinitionRepository toolRepository;
    private final ToolGrantRepository grantRepository;
    private final PermissionEvaluator permissionEvaluator;
    private final AuditAppender auditAppender;

    public ToolController(AgentDefinitionRepository agentRepository,
                          ToolDefinitionRepository toolRepository,
                          ToolGrantRepository grantRepository,
                          PermissionEvaluator permissionEvaluator,
                          AuditAppender auditAppender) {
        this.agentRepository = agentRepository;
        this.toolRepository = toolRepository;
        this.grantRepository = grantRepository;
        this.permissionEvaluator = permissionEvaluator;
        this.auditAppender = auditAppender;
    }

    @GetMapping
    public List<ToolDefinition> list(Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:read", "TOOL", "list");
        return toolRepository.listByTenant(principal.tenantId());
    }

    @PostMapping
    public ToolDefinition create(@Valid @RequestBody ToolCreateRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", "create");
        ToolDefinition tool = new ToolDefinition(
                UUID.randomUUID(),
                principal.tenantId(),
                request.name(),
                request.description(),
                request.type(),
                "{\"type\":\"object\"}",
                request.riskLevel(),
                true,
                "",
                principal.principalId(),
                principal.principalId()
        );
        ToolDefinition savedTool = toolRepository.save(tool);
        auditAppender.append(
                principal.tenantId(),
                principal.principalId(),
                "TOOL_CREATE",
                "TOOL",
                savedTool.id().toString(),
                "SUCCEEDED",
                "Tool 创建成功"
        );
        return savedTool;
    }

    @PostMapping("/{id}/grants")
    public ToolGrant grant(@PathVariable("id") UUID id, @Valid @RequestBody ToolGrantRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "tool:grant", "TOOL", id.toString());

        ToolDefinition tool = toolRepository.findByTenantAndId(principal.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工具不存在"));
        AgentDefinition agent = agentRepository.findByTenantAndId(principal.tenantId(), request.agentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));

        ToolGrant grant = new ToolGrant(principal.tenantId(), tool.id(), agent.id(), null, true);
        ToolGrant savedGrant = grantRepository.save(grant);
        agentRepository.addToolToAgent(principal.tenantId(), agent.id(), tool.id());
        auditAppender.append(
                principal.tenantId(),
                principal.principalId(),
                "TOOL_GRANT",
                "TOOL",
                tool.id().toString(),
                "SUCCEEDED",
                "Tool 已授权给 Agent " + agent.id()
        );
        return savedGrant;
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission, String resourceType, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, resourceType, resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    public record ToolCreateRequest(
            @NotBlank String name,
            @NotBlank String description,
            @NotNull ToolType type,
            @NotNull ToolRiskLevel riskLevel
    ) {
    }

    public record ToolGrantRequest(@NotNull UUID agentId) {
    }
}
```

- [ ] **Step 6: Replace RunController with repository-based implementation**

Replace `cm-agent-server/src/main/java/com/cmagent/server/web/RunController.java` with:

```java
package com.cmagent.server.web;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import com.cmagent.core.repository.AgentDefinitionRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.repository.ToolGrantRepository;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.core.security.AuthorizationDecision;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.core.security.ToolAuthorizationPolicy;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/agents/{agentId}/runs")
public class RunController {

    private final AgentRuntime runtime;
    private final AgentDefinitionRepository agentRepository;
    private final ToolDefinitionRepository toolRepository;
    private final ToolGrantRepository grantRepository;
    private final PermissionEvaluator permissionEvaluator;
    private final ToolAuthorizationPolicy toolAuthorizationPolicy;
    private final AuditAppender auditAppender;

    public RunController(AgentRuntime runtime,
                         AgentDefinitionRepository agentRepository,
                         ToolDefinitionRepository toolRepository,
                         ToolGrantRepository grantRepository,
                         PermissionEvaluator permissionEvaluator,
                         ToolAuthorizationPolicy toolAuthorizationPolicy,
                         AuditAppender auditAppender) {
        this.runtime = runtime;
        this.agentRepository = agentRepository;
        this.toolRepository = toolRepository;
        this.grantRepository = grantRepository;
        this.permissionEvaluator = permissionEvaluator;
        this.toolAuthorizationPolicy = toolAuthorizationPolicy;
        this.auditAppender = auditAppender;
    }

    @PostMapping
    public AgentRunResult run(@PathVariable("agentId") UUID agentId, @Valid @RequestBody RunRequest request, Authentication authentication) {
        PrincipalRef principal = principal(authentication);
        authorize(principal, "agent:run", "AGENT", agentId.toString());

        AgentDefinition agent = agentRepository.findByTenantAndId(principal.tenantId(), agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在"));
        if (!agent.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 已禁用");
        }

        List<ToolDefinition> authorizedTools = authorizedTools(principal, agent);

        try {
            AgentRunResult result = runtime.run(new AgentRunRequest(
                    principal.tenantId(),
                    agent.id(),
                    principal,
                    request.input(),
                    authorizedTools
            ));
            appendAuditEvent(principal, agent, result.status().name(), result.status() == RunStatus.SUCCEEDED ? "Agent 运行完成" : defaultMessage(result));
            return result;
        } catch (RuntimeException ex) {
            appendAuditEvent(principal, agent, RunStatus.FAILED.name(), defaultMessage(ex));
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent 运行失败", ex);
        }
    }

    private List<ToolDefinition> authorizedTools(PrincipalRef principal, AgentDefinition agent) {
        List<ToolGrant> grants = grantRepository.listByTenantAndAgent(principal.tenantId(), agent.id());
        Map<UUID, ToolDefinition> tools = new LinkedHashMap<>();
        for (ToolGrant grant : grants) {
            if (!grant.granted()) {
                continue;
            }
            toolRepository.findByTenantAndId(principal.tenantId(), grant.toolId())
                    .ifPresent(tool -> {
                        AuthorizationDecision decision = toolAuthorizationPolicy.check(principal, agent.id(), tool, grants);
                        if (decision.allowed()) {
                            tools.putIfAbsent(tool.id(), tool);
                        }
                    });
        }
        return new ArrayList<>(tools.values());
    }

    private String defaultMessage(AgentRunResult result) {
        return result.errorMessage() == null || result.errorMessage().isBlank()
                ? "Agent 运行失败"
                : result.errorMessage();
    }

    private String defaultMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? "Agent 运行失败"
                : ex.getMessage();
    }

    private void appendAuditEvent(PrincipalRef principal, AgentDefinition agent, String status, String message) {
        auditAppender.append(
                principal.tenantId(),
                principal.principalId(),
                "AGENT_RUN",
                "AGENT",
                agent.id().toString(),
                status,
                message
        );
    }

    private PrincipalRef principal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtService.JwtSession session)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或令牌无效");
        }
        return new PrincipalRef(session.tenantId(), session.principalId(), session.displayName(), Set.copyOf(session.permissions()));
    }

    private void authorize(PrincipalRef principal, String permission, String resourceType, String resourceId) {
        AuthorizationDecision decision = permissionEvaluator.check(principal, permission);
        if (!decision.allowed()) {
            auditAppender.accessDenied(principal, resourceType, resourceId, permission, decision.reason());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
    }

    public record RunRequest(@NotBlank String input) {
    }
}
```

- [ ] **Step 7: Add JDBC run path integration test**

Create `cm-agent-server/src/test/java/com/cmagent/server/web/RunControllerJdbcPersistenceTest.java`:

```java
package com.cmagent.server.web;

import com.cmagent.core.domain.AgentRunRequest;
import com.cmagent.core.domain.AgentRunResult;
import com.cmagent.core.domain.RunStatus;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.runtime.AgentRuntime;
import com.cmagent.server.CmAgentServerApplication;
import com.cmagent.server.security.JwtService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CmAgentServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(RunControllerJdbcPersistenceTest.TestRuntimeConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RunControllerJdbcPersistenceTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void jdbcProperties(DynamicPropertyRegistry registry) {
        registry.add("cm-agent.persistence.mode", () -> "jdbc");
        registry.add("cm-agent.persistence.jdbc.url", postgres::getJdbcUrl);
        registry.add("cm-agent.persistence.jdbc.username", postgres::getUsername);
        registry.add("cm-agent.persistence.jdbc.password", postgres::getPassword);
        registry.add("cm-agent.persistence.jdbc.driver-class-name", postgres::getDriverClassName);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CapturingAgentRuntime agentRuntime;

    @Test
    void createToolGrantAndRunLoadsAuthorizedToolFromJdbc() throws Exception {
        String token = jwtService.createToken(TENANT_ID, "admin", "系统管理员", List.of(
                "agent:run",
                "agent:read",
                "agent:write",
                "tool:read",
                "tool:grant",
                "audit:read",
                "apikey:write"
        ));

        String agentResponse = mockMvc.perform(post("/api/agents")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"企业助手","systemPrompt":"你是企业助手","modelName":"qwen-max"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String agentId = JsonPath.read(agentResponse, "$.id");

        String toolResponse = mockMvc.perform(post("/api/tools")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"echo","description":"回显输入","type":"LOCAL","riskLevel":"LOW"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String toolId = JsonPath.read(toolResponse, "$.id");

        mockMvc.perform(post("/api/tools/{toolId}/grants", toolId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":"%s"}
                                """.formatted(agentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted").value(true));

        mockMvc.perform(post("/api/agents/{agentId}/runs", agentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"你好"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(agentRuntime.lastRequest()).isNotNull();
        assertThat(agentRuntime.lastRequest().tools()).extracting(ToolDefinition::name).containsExactly("echo");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @TestConfiguration
    static class TestRuntimeConfig {
        @Bean
        @Primary
        CapturingAgentRuntime agentRuntime() {
            return new CapturingAgentRuntime();
        }
    }

    static class CapturingAgentRuntime implements AgentRuntime {
        private final AtomicReference<AgentRunRequest> lastRequest = new AtomicReference<>();

        @Override
        public AgentRunResult run(AgentRunRequest request) {
            lastRequest.set(request);
            Instant now = Instant.now();
            return new AgentRunResult(
                    UUID.randomUUID(),
                    RunStatus.SUCCEEDED,
                    "fake-runtime: " + request.input(),
                    List.of(),
                    now,
                    now,
                    ""
            );
        }

        AgentRunRequest lastRequest() {
            return lastRequest.get();
        }
    }
}
```

- [ ] **Step 8: Run repository and controller tests to verify GREEN**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=JdbcToolGrantRepositoryTest test
mvn -q -pl cm-agent-server -Dtest=RunControllerTest,RunControllerJdbcPersistenceTest,AgentControllerJdbcPersistenceTest test
```

Expected:

```text
BUILD SUCCESS
BUILD SUCCESS
```

- [ ] **Step 9: Commit JDBC Grant and run path integration**

Run:

```powershell
git add cm-agent-persistence cm-agent-server
git commit -m "feat: 持久化 Tool 授权运行链路"
```

Expected:

```text
[<branch> <sha>] feat: 持久化 Tool 授权运行链路
```

## Task 6: Final Verification

**Files:**
- No new files.

- [ ] **Step 1: Run targeted persistence tests**

Run:

```powershell
mvn -q -pl cm-agent-persistence -Dtest=JdbcAgentDefinitionRepositoryTest,JdbcToolDefinitionRepositoryTest,JdbcToolGrantRepositoryTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 2: Run targeted server tests**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=ApplicationProfileConfigurationTest,AgentControllerJdbcPersistenceTest,RunControllerTest,RunControllerJdbcPersistenceTest,AuthControllerTest test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 3: Run full test suite**

Run:

```powershell
mvn -q test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 4: Verify production memory mode is rejected**

Run:

```powershell
mvn -q -pl cm-agent-server -Dtest=ApplicationProfileConfigurationTest#productionProfileRejectsMemoryPersistenceModeWhenJwtSecretExists test
```

Expected:

```text
BUILD SUCCESS
```

- [ ] **Step 5: Verify no incomplete markers remain in new production persistence code**

Run:

```powershell
rg -n "待实现|临时实现|补充实现" cm-agent-core cm-agent-persistence cm-agent-server
```

Expected:

```text
No output
```

- [ ] **Step 6: Review git status and commit final verification notes if files changed**

Run:

```powershell
git status --short
```

Expected:

```text
No output
```

If `git status --short` shows files, inspect them with:

```powershell
git diff --stat
git diff
```

Expected:

```text
Only intentional production persistence files are changed.
```

## Plan Self-Review

Spec coverage:

- Repository interfaces are covered by Task 1.
- JDBC Agent implementation and tenant isolation are covered by Task 2 and Task 3.
- JDBC Tool implementation is covered by Task 4.
- JDBC Grant implementation and run-path authorized tool loading are covered by Task 5.
- production/prod rejection of memory persistence is covered by Task 1 and Task 6.
- Existing REST API paths are preserved by replacing controller internals without changing mappings.
- local/test memory fallback is preserved by `ServerRepositoryConfiguration`.

Incomplete-marker scan:

- This plan intentionally contains no incomplete implementation markers and no incomplete code blocks.

Type consistency:

- `AgentDefinitionRepository`, `ToolDefinitionRepository`, and `ToolGrantRepository` method names are used consistently by JDBC implementations and controllers.
- `CmAgentPersistenceProperties.Mode` uses `MEMORY` and `JDBC`; configuration values use Spring Boot relaxed enum binding with `memory` and `jdbc`.
- JDBC configuration passes the same `JdbcClient` and `ObjectMapper` types used by repository constructors.
