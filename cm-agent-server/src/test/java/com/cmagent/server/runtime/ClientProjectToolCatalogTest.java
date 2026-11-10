package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.DefaultPermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientProjectToolCatalogTest {
    private final UUID tenant = UUID.randomUUID();
    private final ToolDefinitionRepository tools = mock(ToolDefinitionRepository.class);
    private final AuditAppender audit = mock(AuditAppender.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final Map<UUID, ToolDefinition> stored = new HashMap<>();
    private ClientProjectToolCatalog catalog;

    @BeforeEach void setup() {
        when(transaction.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(
                        mock(org.springframework.transaction.TransactionStatus.class)));
        when(tools.findByTenantAndId(eq(tenant), any())).thenAnswer(call ->
                Optional.ofNullable(stored.get(call.getArgument(1))));
        when(tools.save(any())).thenAnswer(call -> {
            ToolDefinition value = call.getArgument(0);
            stored.put(value.id(), value);
            return value;
        });
        catalog = new ClientProjectToolCatalog(tools, new DefaultPermissionEvaluator(),
                transaction, audit, new ObjectMapper());
    }

    @Test void 固定客户端项目工具使用独立命名空间并保持幂等() {
        var principal = new PrincipalRef(tenant, "owner", "测试",
                Set.of("project:manage", "tool:grant"));
        var first = catalog.install(principal);
        var second = catalog.install(principal);

        assertThat(first).hasSize(ClientProjectToolCatalog.Kind.values().length);
        assertThat(second).isEqualTo(first);
        assertThat(first).allMatch(tool -> tool.name().startsWith("client_project_"));
        assertThat(first).noneMatch(tool -> tool.name().startsWith("project_"));
        assertThat(first).extracting(ToolDefinition::name).contains(
                "client_project_list_toolchains", "client_project_propose_command",
                "client_project_run_command", "client_project_command_status",
                "client_project_cancel_command");
        verify(audit, org.mockito.Mockito.times(2)).appendControlled(eq(tenant),
                eq("owner"), eq("CLIENT_PROJECT_TOOLS_INSTALL"), eq("CLIENT_PROJECT_TOOLS"),
                eq("-"), eq("SUCCEEDED"), eq("仅登记固定项目工具目录，未授予Agent"));
    }

    @Test void 缺少工具治理权限时不写目录() {
        var principal = new PrincipalRef(tenant, "owner", "测试", Set.of("project:manage"));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> catalog.install(principal)))
                .isInstanceOf(LocalProjectAccessException.class)
                .hasMessageContaining("缺少设备项目工具授权权限");
        org.mockito.Mockito.verifyNoInteractions(tools);
    }
}
