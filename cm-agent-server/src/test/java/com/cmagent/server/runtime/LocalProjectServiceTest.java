package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.SensitiveDataRedactor;
import com.sun.jna.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalProjectServiceTest {
    @TempDir Path directory;
    private final PrincipalRef owner = new PrincipalRef(UUID.randomUUID(), "owner", "测试", Set.of("project:read"));
    private final LocalProjectAccessService access = mock(LocalProjectAccessService.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final LocalProjectBindingRepository bindings = mock(LocalProjectBindingRepository.class);
    private final AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    private final LocalProjectToolCatalog toolCatalog = mock(LocalProjectToolCatalog.class);
    private final ToolGrantRepository toolGrants = mock(ToolGrantRepository.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final AuditAppender audit = mock(AuditAppender.class);
    private final UUID projectId = UUID.randomUUID();
    private LocalProject project;
    private LocalProjectDirectoryTickets.Root root;
    private LocalProjectService service;

    @BeforeEach void setup() throws Exception {
        var now = Instant.now();
        root = new LocalProjectDirectoryTickets.Root(directory.toAbsolutePath(), Platform.isWindows()
                ? WindowsLocalProjectDirectoryIdentity.read(directory) : Files.readAttributes(directory, BasicFileAttributes.class).fileKey().toString());
        project = new LocalProject(projectId, owner.tenantId(), owner.principalId(), "测试项目", "node", UUID.randomUUID(),
                LocalProjectRootCipher.digest(root.identity()), LocalProject.State.ACTIVE, 1, now, now);
        when(access.scope(owner, projectId, "project:read")).thenReturn(new LocalProjectAccessService.Scope(owner, project, root, null));
        when(access.reject(any(), any(), anyString(), anyInt(), anyString())).thenAnswer(call ->
                new LocalProjectAccessException(call.getArgument(2), "test-error", call.getArgument(3), call.getArgument(4)));
        when(access.infrastructure(any(), any(), anyString(), any())).thenReturn(new LocalProjectAccessException("PROJECT_UNAVAILABLE", "test-error", 503, "服务不可用"));
        when(access.fileFailure(any(), any(), any())).thenReturn(new LocalProjectAccessException("PROJECT_FILE_UNAVAILABLE", "test-error", 422, "文件不可用"));
        service = new LocalProjectService(null, null, bindings, conversations, agents, toolCatalog, toolGrants,
                access, null, null, new LocalProjectFiles(List.of()), new SensitiveDataRedactor(), audit,
                transactions, Clock.systemUTC(), "node");
    }

    @Test void 先脱敏全文件再分页且仍返回实际文件版本避免跨页密钥泄露() throws Exception {
        String input = "a".repeat(65_530) + " token=test-only-private-fragment-value\n结尾";
        byte[] original = input.getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("source.txt"), original);
        var first = service.read(owner, projectId, "source.txt", 0, 65_536);
        assertTrue(first.redacted()); assertTrue(first.hasMore());
        assertFalse(first.content().contains("private-fragment"));
        var second = service.read(owner, projectId, "source.txt", first.nextOffset(), 65_536);
        assertFalse(second.hasMore()); assertFalse(second.content().contains("private-fragment"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)), first.sha256());
        assertEquals(first.sha256(), second.sha256());
        assertFalse(first.toString().contains("token"));
        verify(access, times(2)).record(owner, projectId, "PROJECT_FILE_READ", "STARTED");
        verify(access, times(2)).record(owner, projectId, "PROJECT_FILE_READ", "SUCCEEDED");
    }

    @Test void UTF8续读必须位于码点边界并保留空白文件() throws Exception {
        Files.writeString(directory.resolve("source.txt"), "甲乙丙丁");
        var first = service.read(owner, projectId, "source.txt", 0, 4);
        assertEquals("甲", first.content()); assertEquals(3, first.nextOffset());
        var failure = assertThrows(LocalProjectAccessException.class, () -> service.read(owner, projectId, "source.txt", 1, 4));
        assertEquals(400, failure.status());
        Files.writeString(directory.resolve("source.txt"), " \n\t");
        var blank = service.read(owner, projectId, "source.txt", 0, 4);
        assertEquals(" \n\t", blank.content()); assertFalse(blank.redacted());
    }

    @Test void 权限失败不读取正文且审计开始失败时不交付内容() throws Exception {
        Files.writeString(directory.resolve("source.txt"), "ordinary source");
        var denied = new LocalProjectAccessException("PROJECT_FORBIDDEN", "safe-id", 403, "权限不足");
        when(access.scope(owner, projectId, "project:read")).thenThrow(denied);
        assertSame(denied, assertThrows(LocalProjectAccessException.class, () -> service.read(owner, projectId, "source.txt", 0, 64)));
        verify(access, never()).record(any(), any(), anyString(), anyString());
    }

    @Test void 新项目会话自动准备固定项目工具并跳过已有授权() {
        UUID agentId = UUID.randomUUID();
        var first = new ToolDefinition(UUID.randomUUID(), owner.tenantId(), "project_list_files", "列目录",
                ToolType.LOCAL, "{}", ToolRiskLevel.LOW, true, "local-project:LIST", "Owner", "Owner");
        var second = new ToolDefinition(UUID.randomUUID(), owner.tenantId(), "project_read_file", "读文件",
                ToolType.LOCAL, "{}", ToolRiskLevel.LOW, true, "local-project:READ", "Owner", "Owner");
        var agent = new AgentDefinition(agentId, owner.tenantId(), "测试Agent", "", "测试", UUID.randomUUID(),
                "test-model", 0.2, 4, true, List.of(), "Owner", "Owner");
        when(agents.findByTenantAndId(owner.tenantId(), agentId)).thenReturn(Optional.of(agent));
        when(toolCatalog.install(owner)).thenReturn(List.of(first, second));
        when(toolGrants.listByTenantAndAgent(owner.tenantId(), agentId)).thenReturn(List.of(
                new ToolGrant(owner.tenantId(), first.id(), agentId, null, true)));
        when(toolGrants.listByTenantAgentAndTool(owner.tenantId(), agentId, second.id())).thenReturn(List.of());
        when(transactions.execute(any())).thenAnswer(call -> ((org.springframework.transaction.support.TransactionCallback<?>)
                call.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        when(conversations.save(eq(owner.tenantId()), any())).thenAnswer(call -> call.getArgument(1));
        when(bindings.insertConversation(any())).thenReturn(true);

        service.createConversation(owner, projectId, agentId);

        verify(toolGrants, times(1)).save(argThat(grant -> grant.toolId().equals(second.id()) && grant.granted()));
        verify(toolGrants, never()).save(argThat(grant -> grant.toolId().equals(first.id())));
        verify(agents).addToolToAgent(owner.tenantId(), agentId, second.id());
        verify(audit).appendControlled(eq(owner.tenantId()), eq(owner.principalId()),
                eq("PROJECT_CONVERSATION_CREATE"), eq("LOCAL_PROJECT"), eq(projectId.toString()),
                eq("SUCCEEDED"), eq("本机项目显式操作"));
        verify(audit).appendControlled(eq(owner.tenantId()), eq(owner.principalId()),
                eq("PROJECT_AGENT_TOOL_GRANT"), eq("TOOL"), eq(second.id().toString()), eq("SUCCEEDED"),
                contains("tool=project_read_file"));
    }

    @Test void 缺少项目管理权限时新会话不安装或授权工具() {
        UUID agentId = UUID.randomUUID();
        var denied = new LocalProjectAccessException("PROJECT_FORBIDDEN", "permission-id", 403, "权限不足");
        doThrow(denied).when(access).authorize(owner, "project:manage", projectId);

        assertSame(denied, assertThrows(LocalProjectAccessException.class,
                () -> service.createConversation(owner, projectId, agentId)));
        verifyNoInteractions(transactions, toolCatalog, toolGrants, agents);
    }

    @Test void 缺少工具授权权限时新会话不安装或授权工具() {
        UUID agentId = UUID.randomUUID();
        var denied = new LocalProjectAccessException("PROJECT_FORBIDDEN", "permission-id", 403, "权限不足");
        doThrow(denied).when(access).authorize(owner, "tool:grant", projectId);

        assertSame(denied, assertThrows(LocalProjectAccessException.class,
                () -> service.createConversation(owner, projectId, agentId)));
        verifyNoInteractions(transactions, toolCatalog, toolGrants, agents);
    }
}
