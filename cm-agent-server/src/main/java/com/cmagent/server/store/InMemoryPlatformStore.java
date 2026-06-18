package com.cmagent.server.store;

import com.cmagent.core.audit.AuditEvent;
import com.cmagent.core.audit.AuditEventRepository;
import com.cmagent.core.domain.AgentDefinition;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class InMemoryPlatformStore implements AuditEventRepository {

    private final ConcurrentHashMap<UUID, AgentDefinition> agents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ToolDefinition> tools = new ConcurrentHashMap<>();
    private final List<ToolGrant> grants = Collections.synchronizedList(new ArrayList<>());
    private final List<AuditEvent> auditEvents = Collections.synchronizedList(new ArrayList<>());

    public AgentDefinition saveAgent(AgentDefinition agent) {
        agents.put(agent.id(), agent);
        return agent;
    }

    public Optional<AgentDefinition> findAgent(UUID tenantId, UUID agentId) {
        AgentDefinition agent = agents.get(agentId);
        if (agent == null || !agent.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(agent);
    }

    public List<AgentDefinition> listAgents(UUID tenantId) {
        return agents.values().stream()
                .filter(agent -> agent.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(AgentDefinition::name, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(agent -> agent.id().toString()))
                .toList();
    }

    public AgentDefinition addToolToAgent(UUID tenantId, UUID agentId, UUID toolId) {
        AtomicReference<AgentDefinition> updated = new AtomicReference<>();
        agents.computeIfPresent(agentId, (id, agent) -> {
            if (!agent.tenantId().equals(tenantId)) {
                throw new NoSuchElementException("Agent 不存在");
            }
            if (agent.toolIds().contains(toolId)) {
                updated.set(agent);
                return agent;
            }
            List<UUID> toolIds = new ArrayList<>(agent.toolIds());
            toolIds.add(toolId);
            AgentDefinition merged = new AgentDefinition(
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
                    toolIds,
                    agent.createdBy(),
                    agent.updatedBy()
            );
            updated.set(merged);
            return merged;
        });
        AgentDefinition result = updated.get();
        if (result == null) {
            throw new NoSuchElementException("Agent 不存在");
        }
        return result;
    }

    public ToolDefinition saveTool(ToolDefinition tool) {
        tools.put(tool.id(), tool);
        return tool;
    }

    public Optional<ToolDefinition> findTool(UUID tenantId, UUID toolId) {
        ToolDefinition tool = tools.get(toolId);
        if (tool == null || !tool.tenantId().equals(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(tool);
    }

    public List<ToolDefinition> listTools(UUID tenantId) {
        return tools.values().stream()
                .filter(tool -> tool.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(ToolDefinition::name, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(tool -> tool.id().toString()))
                .toList();
    }

    public ToolGrant saveGrant(ToolGrant grant) {
        synchronized (grants) {
            return grants.stream()
                    .filter(existing -> existing.tenantId().equals(grant.tenantId())
                            && existing.agentId().equals(grant.agentId())
                            && existing.toolId().equals(grant.toolId()))
                    .findFirst()
                    .orElseGet(() -> {
                        grants.add(grant);
                        return grant;
                    });
        }
    }

    public List<ToolGrant> listGrants(UUID tenantId) {
        synchronized (grants) {
            return grants.stream()
                    .filter(grant -> grant.tenantId().equals(tenantId))
                    .toList();
        }
    }

    public List<ToolGrant> listGrants(UUID tenantId, UUID agentId) {
        synchronized (grants) {
            return grants.stream()
                    .filter(grant -> grant.tenantId().equals(tenantId) && grant.agentId().equals(agentId))
                    .toList();
        }
    }

    public List<ToolGrant> listGrants(UUID tenantId, UUID agentId, UUID toolId) {
        synchronized (grants) {
            return grants.stream()
                    .filter(grant -> grant.tenantId().equals(tenantId)
                            && grant.agentId().equals(agentId)
                            && grant.toolId().equals(toolId))
                    .toList();
        }
    }

    @Override
    public void append(AuditEvent event) {
        synchronized (auditEvents) {
            auditEvents.add(event);
        }
    }

    @Override
    public List<AuditEvent> listByTenant(UUID tenantId, int limit) {
        synchronized (auditEvents) {
            return auditEvents.stream()
                    .filter(event -> event.tenantId().equals(tenantId))
                    .sorted(Comparator.comparing(AuditEvent::createdAt).reversed()
                            .thenComparing(AuditEvent::id, Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
        }
    }

    public List<AuditEvent> listAuditEvents(UUID tenantId) {
        return listByTenant(tenantId, Integer.MAX_VALUE);
    }
}
