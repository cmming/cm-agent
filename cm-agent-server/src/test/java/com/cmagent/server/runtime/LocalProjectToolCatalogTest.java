package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.repository.*;
import com.cmagent.core.security.DefaultPermissionEvaluator;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalProjectToolCatalogTest {
    private final UUID tenant=UUID.randomUUID();
    private final ToolDefinitionRepository tools=mock(ToolDefinitionRepository.class);
    private final AuditAppender audit=mock(AuditAppender.class);
    private final TransactionTemplate transaction=mock(TransactionTemplate.class);
    private final Map<UUID,ToolDefinition> stored=new HashMap<>();
    private LocalProjectToolCatalog catalog;

    @BeforeEach void setup() {
        // 使用真实权限判定，避免手造授权替身再次掩盖目录登记引用了错误权限码。
        var access=new LocalProjectAccessService(mock(LocalProjectRepository.class),mock(LocalProjectRootRepository.class),
                mock(LocalProjectBindingRepository.class),new DefaultPermissionEvaluator(),audit,mock(ErrorDiagnosticLogger.class),null,null,"node");
        when(transaction.execute(any())).thenAnswer(call->((TransactionCallback<?>)call.getArgument(0))
                .doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        when(tools.findByTenantAndId(eq(tenant),any())).thenAnswer(call->Optional.ofNullable(stored.get(call.getArgument(1))));
        when(tools.save(any())).thenAnswer(call->{ToolDefinition value=call.getArgument(0);stored.put(value.id(),value);return value;});
        catalog=new LocalProjectToolCatalog(tools,access,transaction,audit,new ObjectMapper());
    }

    @Test void 既有工具治理权限与项目管理权限可登记固定目录且重复登记不改定义() {
        var principal=principal(Set.of("project:manage","tool:grant"));
        var first=catalog.install(principal);
        assertEquals(LocalProjectToolCatalog.Kind.values().length,first.size());
        assertEquals(first,catalog.install(principal));
        for(var kind:LocalProjectToolCatalog.Kind.values())assertTrue(first.stream()
                .anyMatch(value->value.id().equals(LocalProjectToolCatalog.id(tenant,kind))&&value.name().equals(kind.toolName())));
        // memory替身不执行数据库NOT NULL约束；固定目录也必须满足既有双方言持久化合同。
        assertTrue(first.stream().allMatch(value->value.endpoint()!=null&&!value.endpoint().isBlank()));
        verify(tools,times(first.size())).save(any());
        // 目录登记组件没有Agent或grant仓储能力，审计也明确说明未赋权。
        verify(audit,times(2)).appendControlled(eq(tenant),eq("Owner"),eq("PROJECT_TOOLS_INSTALL"),
                eq("LOCAL_PROJECT_TOOLS"),eq("-"),eq("SUCCEEDED"),eq("仅登记固定目录，未授予Agent"));
    }

    @Test void 缺少任一实际所需权限均先拒绝而不进入事务或写目录() {
        for(var permissions:List.of(Set.of("project:manage"),Set.of("tool:grant"),Set.of("project:manage","tool:write"))){
            var failure=assertThrows(LocalProjectAccessException.class,()->catalog.install(principal(permissions)));
            assertEquals(403,failure.status());assertEquals("PROJECT_FORBIDDEN",failure.errorCode());assertFalse(failure.errorId().isBlank());
        }
        verifyNoInteractions(transaction,tools);
        verify(audit,times(3)).appendControlled(eq(tenant),eq("Owner"),eq("LOCAL_PROJECT_ACCESS"),eq("LOCAL_PROJECT"),
                eq("-"),eq("DENIED"),contains("errorCode=PROJECT_FORBIDDEN"));
    }

    private PrincipalRef principal(Set<String> permissions){return new PrincipalRef(tenant,"Owner","测试",permissions);}
}
