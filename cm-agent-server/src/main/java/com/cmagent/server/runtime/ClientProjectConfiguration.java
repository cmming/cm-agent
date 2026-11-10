package com.cmagent.server.runtime;

import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.repository.ClientProjectRepository;
import com.cmagent.core.repository.ClientProjectTaskRepository;
import com.cmagent.core.repository.ClientProjectBindingRepository;
import com.cmagent.core.repository.ClientProjectOperationRepository;
import com.cmagent.core.repository.ClientProjectCommandRepository;
import com.cmagent.core.repository.RunRepository;
import com.cmagent.core.repository.ToolApprovalRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.PermissionEvaluator;
import com.cmagent.persistence.JdbcClientProjectRepository;
import com.cmagent.persistence.JdbcClientProjectTaskRepository;
import com.cmagent.persistence.JdbcClientProjectBindingRepository;
import com.cmagent.persistence.JdbcClientProjectOperationRepository;
import com.cmagent.persistence.JdbcClientProjectCommandRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.ClientProjectProperties;
import com.cmagent.server.config.LocalClientProperties;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已配对设备项目功能的独立装配。
 *
 * <p>功能默认关闭，且只允许在显式 local/test、JDBC 和已启用设备通道的部署中打开。
 * 该条件不复用 Server 本机项目开关，关闭任一功能都不能把请求回退到另一来源。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientProjectProperties.class)
public class ClientProjectConfiguration {
    @Bean
    InitializingBean clientProjectConfigurationValidator(ClientProjectProperties properties,
            LocalClientProperties localClient, Environment environment) {
        return () -> {
            properties.validate();
            if (!properties.isEnabled()) return;
            var profiles = java.util.Set.of(environment.getActiveProfiles());
            if (!localClient.isEnabled()
                    || !"jdbc".equals(environment.getProperty("cm-agent.persistence.mode", "memory"))
                    || !profiles.contains("local") && !profiles.contains("test")
                    || profiles.stream().anyMatch(value ->
                        java.util.Set.of("prod", "production", "supabase").contains(value))) {
                throw new IllegalStateException(
                        "已配对设备项目只支持显式 local/test、JDBC 和已启用的设备客户端通道");
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cm-agent.client-projects", name = "enabled", havingValue = "true")
    static class Enabled {
        @Bean ClientProjectRepository clientProjectRepository(JdbcClient jdbc,
                TransactionTemplate transaction) {
            return new JdbcClientProjectRepository(jdbc, transaction);
        }

        @Bean ClientProjectTaskRepository clientProjectTaskRepository(JdbcClient jdbc,
                TransactionTemplate transaction) {
            return new JdbcClientProjectTaskRepository(jdbc, transaction);
        }

        @Bean ClientProjectOperationRepository clientProjectOperationRepository(JdbcClient jdbc,
                TransactionTemplate transaction) {
            return new JdbcClientProjectOperationRepository(jdbc, transaction);
        }

        @Bean ClientProjectCommandRepository clientProjectCommandRepository(JdbcClient jdbc,
                TransactionTemplate transaction) {
            return new JdbcClientProjectCommandRepository(jdbc, transaction);
        }

        @Bean ClientProjectBindingRepository clientProjectBindingRepository(JdbcClient jdbc,
                TransactionTemplate transaction) {
            return new JdbcClientProjectBindingRepository(jdbc, transaction);
        }

        @Bean ClientProjectRequestBoundary clientProjectRequestBoundary(
                ClientProjectProperties properties) {
            return new ClientProjectRequestBoundary(properties);
        }

        @Bean ClientProjectService clientProjectService(ClientProjectRepository projects,
                ClientProjectOperationRepository operations,
                ClientProjectTaskRepository tasks, LocalClientDeviceRepository devices,
                PermissionEvaluator permissions, AuditAppender audit,
                ErrorDiagnosticLogger diagnostics, ModelCredentialCipher cipher,
                SensitiveDataRedactor redactor, ObjectMapper json,
                TransactionTemplate transaction, ClientProjectProperties properties) {
            return new ClientProjectService(projects, operations, tasks, devices, permissions, audit,
                    diagnostics, cipher, redactor, json, transaction, properties, Clock.systemUTC());
        }

        @Bean ClientProjectToolCatalog clientProjectToolCatalog(ToolDefinitionRepository tools,
                PermissionEvaluator permissions, TransactionTemplate transaction,
                AuditAppender audit, ObjectMapper json) {
            return new ClientProjectToolCatalog(tools, permissions, transaction, audit, json);
        }

        @Bean ClientProjectTools clientProjectTools(RunRepository runs,
                ClientProjectRepository projects, ClientProjectBindingRepository bindings,
                ClientProjectOperationRepository operations,
                ClientProjectCommandService commandService,
                ToolApprovalRepository approvals,
                ClientProjectTaskRepository tasks, ClientProjectToolCatalog catalog,
                ClientProjectService service, PermissionEvaluator permissions,
                AuditAppender audit, ObjectMapper json, ClientProjectProperties properties,
                ClientProjectReadSnapshots snapshots) {
            return new ClientProjectTools(runs, projects, bindings, operations, commandService,
                    approvals, tasks, catalog, service,
                    permissions, audit, json, properties, Clock.systemUTC(), snapshots);
        }

        @Bean ClientProjectCommandService clientProjectCommandService(
                ClientProjectCommandRepository commands, ClientProjectTaskRepository tasks,
                ClientProjectOperationRepository projectOperations,
                ClientProjectBindingRepository bindings,
                ClientProjectRepository projects, LocalClientDeviceRepository devices,
                PermissionEvaluator permissions, AuditAppender audit, ModelCredentialCipher cipher,
                SensitiveDataRedactor redactor, ObjectMapper json,
                TransactionTemplate transaction, ClientProjectProperties properties) {
            return new ClientProjectCommandService(commands, projectOperations, tasks,
                    bindings, projects,
                    devices, permissions, audit, cipher, redactor, json,
                    transaction, properties, Clock.systemUTC());
        }

        @Bean ClientProjectReadSnapshots clientProjectReadSnapshots(
                ClientProjectService service,
                com.cmagent.core.repository.RuntimeCheckpointRepository checkpoints,
                ModelCredentialCipher cipher, SensitiveDataRedactor redactor,
                ObjectMapper json, ClientProjectProperties properties) {
            return new ClientProjectReadSnapshots(service, checkpoints, cipher, redactor,
                    json, properties, Clock.systemUTC());
        }

        @Bean ClientProjectRunService clientProjectRunService(RunRepository runs,
                ClientProjectRepository projects, ClientProjectBindingRepository bindings,
                com.cmagent.core.repository.AgentDefinitionRepository agents,
                com.cmagent.core.repository.ConversationRepository conversations,
                com.cmagent.core.repository.ToolGrantRepository grants,
                ClientProjectToolCatalog catalog, PermissionEvaluator permissions,
                AuditAppender audit, TransactionTemplate transaction,
                ClientProjectReadSnapshots snapshots) {
            return new ClientProjectRunService(runs, projects, bindings, agents, conversations,
                    grants, catalog, permissions, audit, transaction, Clock.systemUTC(),
                    snapshots);
        }
    }
}
