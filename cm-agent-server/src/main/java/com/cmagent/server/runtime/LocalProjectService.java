package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.*;
import com.cmagent.core.repository.*;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** 本机项目登记和显式文件读取；原生窗口/文件I/O不占用数据库事务。 */
public final class LocalProjectService {
    /** owner过滤先于分页的长期项目仓储。 */ private final LocalProjectRepository projects;
    /** 仅保存认证加密信封的根仓储。 */ private final LocalProjectRootRepository roots;
    /** 固定会话/Run来源，与旧工作区选择分开。 */ private final LocalProjectBindingRepository bindings;
    /** 现有会话元数据，与项目绑定在同一短事务创建。 */ private final ConversationRepository conversations;
    /** Agent必须属于可信租户，不能从请求另指定tenant。 */ private final AgentDefinitionRepository agents;
    /** 固定项目工具目录；新项目会话据此为所选 Agent 准备当前发布兼容的工具。 */ private final LocalProjectToolCatalog toolCatalog;
    /** Agent 级工具授权关系；项目 Run 仍会再次冻结精确工具 ID。 */ private final ToolGrantRepository toolGrants;
    /** 共享严格权限/归属/错误边界。 */ private final LocalProjectAccessService access;
    /** 仅从真实原生选择结果消费授权。 */ private final LocalProjectSelections selections;
    /** 认证根密文，不允许客户端路径登记。 */ private final LocalProjectRootCipher cipher;
    /** 有界普通文本访问原语。 */ private final LocalProjectFiles files;
    /** 整份文本脱敏后才切片，避免密钥跨页边界泄露。 */ private final SensitiveDataRedactor redactor;
    /** 和元数据更新共享的严格审计。 */ private final AuditAppender audit;
    /** 只包元数据和审计，禁止把I/O或原生窗口放入事务。 */ private final TransactionTemplate transactions;
    /** 可信时钟，不读取客户端创建时间。 */ private final Clock clock;
    /** 本实例固定执行节点。 */ private final String nodeId;

    LocalProjectService(LocalProjectRepository projects, LocalProjectRootRepository roots,
            LocalProjectBindingRepository bindings, ConversationRepository conversations, AgentDefinitionRepository agents,
            LocalProjectToolCatalog toolCatalog, ToolGrantRepository toolGrants,
            LocalProjectAccessService access, LocalProjectSelections selections, LocalProjectRootCipher cipher,
            LocalProjectFiles files, SensitiveDataRedactor redactor, AuditAppender audit,
            TransactionTemplate transactions, Clock clock, String nodeId) {
        this.projects = projects; this.roots = roots; this.bindings = bindings; this.conversations = conversations;
        this.agents = agents; this.toolCatalog = toolCatalog; this.toolGrants = toolGrants;
        this.access = access; this.selections = selections; this.cipher = cipher;
        this.files = files; this.redactor = redactor; this.audit = audit; this.transactions = transactions;
        this.clock = clock; this.nodeId = nodeId;
    }

    /** @param principal 认证主体 @param page 已验证的复合游标 @param archived 是否包含已归档项目 @return 当前owner的项目页 */
    public List<View> list(PrincipalRef principal, LocalProjectPageRequest page, boolean archived) {
        access.authorize(principal, "project:read", null);
        try { return projects.list(principal.tenantId(), principal.principalId(), page, archived).stream().map(LocalProjectService::view).toList(); }
        catch (RuntimeException failure) { throw access.infrastructure(principal, null, "PROJECT_LIST", failure); }
    }

    /** @param principal 认证主体 @param projectId 项目标识 @return 不含绝对路径的项目元数据 */
    public View get(PrincipalRef principal, UUID projectId) { return view(access.project(principal, projectId, "project:read")); }

    /** @param principal 认证主体 @param projectId 已登记项目 @param page 稳定会话游标 @return 当前owner的项目会话，归档历史可读取且不打开本机根 */
    public List<Conversation> listConversations(PrincipalRef principal, UUID projectId, ConversationPageRequest page) {
        access.project(principal, projectId, "project:read");
        access.authorize(principal, "agent:read", projectId);
        try { return bindings.listConversations(principal.tenantId(), principal.principalId(), projectId, page); }
        catch (RuntimeException failure) { throw access.infrastructure(principal, projectId, "PROJECT_CONVERSATION_LIST", failure); }
    }

    /** @param principal 认证主体 @param projectId 路由项目 @param conversationId 路由会话 @return 固定Agent的会话元数据，消息/审批服务仍须核验来源见证 */
    public Conversation conversation(PrincipalRef principal,UUID projectId,UUID conversationId) {
        access.project(principal,projectId,"project:read");
        try {
            var binding=bindings.conversation(principal.tenantId(),principal.principalId(),conversationId).orElse(null);
            if(binding==null||!binding.projectId().equals(projectId))throw access.reject(principal,conversationId,"PROJECT_RESOURCE_NOT_FOUND",404,"项目会话不存在或不可访问");
            return conversations.findByTenantAndAgentAndId(principal.tenantId(),binding.agentId(),conversationId)
                    .orElseThrow(()->access.reject(principal,conversationId,"PROJECT_RESOURCE_NOT_FOUND",404,"项目会话不存在或不可访问"));
        }catch(LocalProjectAccessException failure){throw failure;}
        catch(RuntimeException failure){throw access.infrastructure(principal,conversationId,"PROJECT_CONVERSATION_GET",failure);}
    }

    /**
     * 消费一次原生选择并登记固定根；持久化失败需重新选择，不自动重放选择票据。
     * @param principal 认证主体
     * @param requestId 原明确选择动作编号
     * @param ticketId 同主体原生选择票据
     * @param name 展示名，不用作路径
     * @return 已登记私有项目
     */
    public View create(PrincipalRef principal, UUID requestId, UUID ticketId, String name) {
        access.authorize(principal, "project:manage", requestId);
        requireName(principal, requestId, name);
        var root = selections.consume(principal, requestId, ticketId);
        UUID id = UUID.randomUUID();
        try {
            files.list(root, "", null, 1);
            Instant now = clock.instant();
            var protectedRoot = cipher.seal(principal, UUID.randomUUID(), root, now);
            var project = new LocalProject(id, principal.tenantId(), principal.principalId(), name, nodeId,
                    protectedRoot.id(), protectedRoot.identity(), LocalProject.State.ACTIVE, 1, now, now);
            return transactions.execute(status -> {
                if (!roots.insertRoot(protectedRoot) || !projects.insert(project)) throw new IllegalStateException("项目登记冲突");
                audit(principal, id, "PROJECT_CREATE");
                return view(project);
            });
        } catch (LocalProjectFileException failure) { throw access.fileFailure(principal, id, failure); }
        catch (LocalProjectAccessException failure) { throw failure; }
        catch (Exception failure) { throw access.infrastructure(principal, id, "PROJECT_CREATE", failure); }
    }

    /** @param principal 认证主体 @param projectId 项目标识 @param expectedVersion 页面已读取版本 @param name 新展示名 @return 新元数据，不移动目录 */
    public View rename(PrincipalRef principal, UUID projectId, long expectedVersion, String name) {
        requireName(principal, projectId, name);
        access.project(principal, projectId, "project:manage");
        try {
            return transactions.execute(status -> {
                if (!projects.rename(principal.tenantId(), principal.principalId(), projectId, expectedVersion, name, clock.instant()))
                    throw new VersionConflict();
                audit(principal, projectId, "PROJECT_RENAME");
                return view(projects.find(principal.tenantId(), principal.principalId(), projectId).orElseThrow());
            });
        } catch (VersionConflict failure) {
            // 拒绝审计必须在业务短事务退出后写入，否则随CAS失败回滚会丢失安全拒绝记录。
            throw access.reject(principal, projectId, "PROJECT_VERSION_CONFLICT", 409, "项目已变化或已归档，请刷新后重试");
        } catch (LocalProjectAccessException failure) { throw failure; }
        catch (RuntimeException failure) { throw access.infrastructure(principal, projectId, "PROJECT_RENAME", failure); }
    }

    /**
     * 新会话先保存空元数据，再在同事务原子写入项目来源头4；不产生旧DEFAULT可见窗口。
     * @param principal 认证主体
     * @param projectId 已登记项目
     * @param agentId 当前租户内固定Agent
     * @return 新会话元数据；正文不在此处自动读取
     */
    public Conversation createConversation(PrincipalRef principal, UUID projectId, UUID agentId) {
        var scope = access.scope(principal, projectId, "project:read");
        access.authorize(principal, "agent:read", projectId);
        access.authorize(principal, "agent:run", projectId);
        // 新项目会话会显式准备固定 project_* 工具，因此同时要求项目管理和工具授权权限；
        // 不能仅凭已读取项目元数据就静默扩大所选 Agent 的能力。
        access.authorize(principal, "project:manage", projectId);
        access.authorize(principal, "tool:grant", projectId);
        try {
            if (agents.findByTenantAndId(principal.tenantId(), agentId).isEmpty())
                throw access.reject(principal, projectId, "PROJECT_AGENT_NOT_FOUND", 404, "Agent不存在或不可访问");
            return transactions.execute(status -> {
                Instant now = clock.instant();
                ensureProjectTools(principal, projectId, agentId);
                var conversation = new Conversation(UUID.randomUUID(), principal.tenantId(), agentId, Conversation.DEFAULT_TITLE,
                        principal.principalId(), now, now);
                conversations.save(principal.tenantId(), conversation);
                var project = scope.project();
                if (!bindings.insertConversation(new LocalProjectBinding(principal.tenantId(), principal.principalId(),
                        project.id(), agentId, conversation.id(), null, project.rootBindingId(), project.rootIdentity(),
                        project.nodeId(), project.version(), now))) throw new IllegalStateException("项目会话绑定冲突");
                audit(principal, projectId, "PROJECT_CONVERSATION_CREATE");
                return conversation;
            });
        } catch (LocalProjectAccessException failure) { throw failure; }
        catch (RuntimeException failure) { throw access.infrastructure(principal, projectId, "PROJECT_CONVERSATION_CREATE", failure); }
    }

    /**
     * 确保选择 Agent 的项目会话具有当前固定工具目录的精确 grant。
     *
     * <p>项目会话创建是用户的显式选择；此处只准备由目录派生、定义完全一致的工具，
     * 不接受浏览器提交的工具 ID。定义安装、Agent 关联、grant 和审计与项目会话处于同一事务，
     * 任一步失败都不会留下一个表面可创建但实际没有文件能力的会话。</p>
     */
    private void ensureProjectTools(PrincipalRef principal, UUID projectId, UUID agentId) {
        List<ToolDefinition> available = toolCatalog.install(principal);
        Set<UUID> granted = toolGrants.listByTenantAndAgent(principal.tenantId(), agentId).stream()
                .filter(ToolGrant::granted)
                .map(ToolGrant::toolId)
                .collect(java.util.stream.Collectors.toSet());
        for (ToolDefinition tool : available) {
            if (granted.contains(tool.id())) continue;
            ToolGrant existing = toolGrants.listByTenantAgentAndTool(principal.tenantId(), agentId, tool.id()).stream()
                    .findFirst().orElse(null);
            if (existing != null) toolGrants.delete(principal.tenantId(), agentId, tool.id());
            toolGrants.save(new ToolGrant(principal.tenantId(), tool.id(), agentId, null, true));
            agents.addToolToAgent(principal.tenantId(), agentId, tool.id());
            audit.appendControlled(principal.tenantId(), principal.principalId(), "PROJECT_AGENT_TOOL_GRANT",
                    "TOOL", tool.id().toString(), "SUCCEEDED",
                    "project=" + projectId + " agent=" + agentId + " tool=" + tool.name());
        }
    }

    /** @param principal 认证主体 @param projectId 项目标识 @param path 根内相对目录 @param cursor 原服务端游标 @param limit 页容量 @return 安全文件目录，不含正文 */
    public Tree tree(PrincipalRef principal, UUID projectId, String path, String cursor, int limit) {
        var scope = access.scope(principal, projectId, "project:read");
        try {
            access.record(principal, projectId, "PROJECT_TREE", "STARTED");
            var result = files.list(scope.root(), path, cursor, limit);
            access.record(principal, projectId, "PROJECT_TREE", "SUCCEEDED");
            return new Tree(result.items().stream().map(entry -> new Item(entry.name(), entry.path(), entry.kind().name(), entry.sizeBytes(), entry.readable())).toList(), result.nextCursor());
        } catch (LocalProjectFileException failure) { throw access.fileFailure(principal, projectId, failure); }
        catch (LocalProjectAccessException failure) { throw failure; }
        catch (Exception failure) { throw access.infrastructure(principal, projectId, "PROJECT_TREE", failure); }
    }

    /**
     * 整份有界文本先脱敏再按UTF-8字节分页；offset只针对脱敏视图，sha256仍标识实际原文件。
     * @param principal 认证主体
     * @param projectId 项目标识
     * @param path 根内相对文件
     * @param offset 脱敏视图的UTF-8续读位置
     * @param maxBytes 单次预算，最多64KiB
     * @return 不进入日志/toString的显式正文响应
     */
    public Text read(PrincipalRef principal, UUID projectId, String path, int offset, int maxBytes) {
        var scope = access.scope(principal, projectId, "project:read");
        byte[] raw = null, safe = null;
        try {
            if (offset < 0 || maxBytes < 4 || maxBytes > 65_536)
                throw access.reject(principal, projectId, "PROJECT_READ_RANGE_INVALID", 400, "文件预览范围无效");
            access.record(principal, projectId, "PROJECT_FILE_READ", "STARTED");
            raw = files.mutationSnapshot(scope.root(), path);
            String text = new String(raw, StandardCharsets.UTF_8);
            String redacted = text.isBlank() ? text : redactor.redact(text);
            safe = redacted.getBytes(StandardCharsets.UTF_8);
            if (offset > safe.length || offset < safe.length && (safe[offset] & 0xc0) == 0x80)
                throw access.reject(principal, projectId, "PROJECT_READ_RANGE_INVALID", 400, "文件预览续读位置无效，请从头读取");
            int end = Math.min(safe.length, offset + maxBytes);
            while (end < safe.length && (safe[end] & 0xc0) == 0x80) end--;
            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
            access.record(principal, projectId, "PROJECT_FILE_READ", "SUCCEEDED");
            return new Text(path, sha, safe.length, offset, end, end < safe.length, !text.equals(redacted),
                    new String(safe, offset, end - offset, StandardCharsets.UTF_8));
        } catch (LocalProjectFileException failure) { throw access.fileFailure(principal, projectId, failure); }
        catch (LocalProjectAccessException failure) { throw failure; }
        catch (Exception failure) { throw access.infrastructure(principal, projectId, "PROJECT_FILE_READ", failure); }
        finally { if (raw != null) Arrays.fill(raw, (byte) 0); if (safe != null) Arrays.fill(safe, (byte) 0); }
    }

    /** @param principal 认证主体 @param projectId 项目标识 @param query 有界字面查询 @param content 是否搜索已脱敏正文 @return 有界片段及覆盖不完整标记 */
    public Search search(PrincipalRef principal, UUID projectId, String query, boolean content) {
        var scope = access.scope(principal, projectId, "project:read");
        try {
            access.record(principal, projectId, "PROJECT_SEARCH", "STARTED");
            var result = files.search(scope.root(), query, content, text -> text.isBlank() ? text : redactor.redact(text));
            access.record(principal, projectId, "PROJECT_SEARCH", "SUCCEEDED");
            return new Search(result.matches().stream().map(match -> new Match(match.relativePath(), match.contentMatch(), match.excerpt())).toList(),
                    result.partial(), result.scanned(), result.skipped());
        } catch (LocalProjectFileException failure) { throw access.fileFailure(principal, projectId, failure); }
        catch (LocalProjectAccessException failure) { throw failure; }
        catch (Exception failure) { throw access.infrastructure(principal, projectId, "PROJECT_SEARCH", failure); }
    }

    private void requireName(PrincipalRef principal, UUID id, String name) {
        if (name == null || name.isBlank() || name.length() > 120 || name.codePoints().anyMatch(Character::isISOControl))
            throw access.reject(principal, id, "PROJECT_NAME_INVALID", 400, "项目名称须为1至120个可见字符");
    }
    private void audit(PrincipalRef principal, UUID id, String action) {
        audit.appendControlled(principal.tenantId(), principal.principalId(), action, "LOCAL_PROJECT", id.toString(), "SUCCEEDED", "本机项目显式操作");
    }
    private static View view(LocalProject project) { return new View(project.id(), project.name(), project.state().name(), project.version(), project.createdAt(), project.updatedAt()); }

    /** 只跨出元数据短事务，不包含请求值或路径；随后由最终访问边界补写严格拒绝审计。 */
    private static final class VersionConflict extends RuntimeException {}

    /** @param id 项目标识 @param name 展示名 @param state 登记状态 @param version 条件更新版本 @param createdAt 创建时间 @param updatedAt 最近修改时间 */
    public record View(UUID id, String name, String state, long version, Instant createdAt, Instant updatedAt) {}
    /** @param name 安全显示名 @param path 根内相对路径 @param kind 项目条目类型 @param sizeBytes 元数据大小 @param readable 是否为允许预览的文本候选 */
    public record Item(String name, String path, String kind, long sizeBytes, boolean readable) {}
    /** @param items 有界条目 @param nextCursor 下一页不透明游标，没有更多时为空 */
    public record Tree(List<Item> items, String nextCursor) { public Tree { items = List.copyOf(items); } }
    /** @param path 根内相对路径 @param sha256 原文件版本 @param totalBytes 脱敏视图字节数 @param offset 本次起点 @param nextOffset UTF-8安全续读点 @param hasMore 是否还有内容 @param redacted 是否含脱敏替换 @param content 有界脱敏正文 */
    public record Text(String path, String sha256, int totalBytes, int offset, int nextOffset, boolean hasMore, boolean redacted, String content) {
        @Override public String toString() { return "Text[受保护的项目正文]"; }
    }
    /** @param path 根内相对路径 @param contentMatch 是否匹配脱敏正文 @param excerpt 脱敏后截取的有界片段 */
    public record Match(String path, boolean contentMatch, String excerpt) { @Override public String toString() { return "Match[受保护的搜索片段]"; } }
    /** @param matches 有界命中 @param partial 是否未完全覆盖 @param scanned 已扫描元数据项数 @param skipped 排除/不可读项数 */
    public record Search(List<Match> matches, boolean partial, int scanned, int skipped) {
        public Search { matches = List.copyOf(matches); }
        @Override public String toString() { return "Search[受保护的搜索结果]"; }
    }
}
