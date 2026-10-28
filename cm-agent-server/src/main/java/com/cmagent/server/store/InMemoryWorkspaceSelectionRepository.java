package com.cmagent.server.store;

import com.cmagent.core.domain.WorkspaceBinding;
import com.cmagent.core.repository.WorkspaceSelectionRepository;
import java.util.*;

/** 仅本地/测试使用；同步方法保护条件更新，进程重启不保留选择。生产使用 JDBC。 */
public final class InMemoryWorkspaceSelectionRepository implements WorkspaceSelectionRepository {
    /** 租户与资源构成键；Agent 在读取和更新时再次核对，不能借相同资源 ID 越界。 */
    private final Map<String, WorkspaceBinding> conversations = new HashMap<>();
    /** Run 选择不可覆盖，生命周期清理正文时保留该元数据。 */
    private final Map<String, WorkspaceBinding> runs = new HashMap<>();
    private static String key(UUID tenant, UUID resource) { return tenant + ":" + resource; }
    private Optional<WorkspaceBinding> find(Map<String, WorkspaceBinding> rows, UUID tenant, UUID agent, UUID id) {
        return Optional.ofNullable(rows.get(key(tenant, id))).filter(row -> row.agentId().equals(agent));
    }
    @Override public synchronized Optional<WorkspaceBinding> conversation(UUID t, UUID a, UUID id) { return find(conversations,t,a,id); }
    @Override public synchronized Optional<WorkspaceBinding> run(UUID t, UUID a, UUID id) { return find(runs,t,a,id); }
    @Override public synchronized boolean saveConversation(WorkspaceBinding binding, long expectedVersion) {
        String key = key(binding.tenantId(), binding.resourceId());
        var old = conversations.get(key);
        if (binding.version() != expectedVersion + 1 || (old == null ? expectedVersion != 0
                : !old.agentId().equals(binding.agentId()) || old.version() != expectedVersion)) return false;
        conversations.put(key, binding); return true;
    }
    @Override public synchronized void abandonConversation(WorkspaceBinding binding) {
        conversations.remove(key(binding.tenantId(),binding.resourceId()),binding);
    }
    @Override public synchronized void saveRun(WorkspaceBinding binding) {
        if (runs.putIfAbsent(key(binding.tenantId(), binding.resourceId()), binding) != null)
            throw new IllegalStateException("运行工作区选择已经固定");
    }
}
