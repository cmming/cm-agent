package com.cmagent.server.service;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.runtime.ToolRuntimeReadiness;
import com.cmagent.server.runtime.local.MysqlLocalExampleCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/** 编排内置 LOCAL 示例的查询、安装、审计和事务一致性。 */
@Service
@Profile("mysql & !prod & !production & !supabase")
@ConditionalOnProperty(prefix = "cm-agent.persistence", name = "mode", havingValue = "jdbc")
public class MysqlLocalExampleService {
    private final ToolDefinitionRepository toolRepository;
    private final TransactionOperations transactionOperations;
    private final AuditAppender auditAppender;
    private final MysqlLocalExampleCatalog catalog;
    private final ToolRuntimeReadiness runtimeReadiness;
    private final ObjectMapper objectMapper;

    public MysqlLocalExampleService(
            ToolDefinitionRepository toolRepository,
            TransactionOperations transactionOperations,
            AuditAppender auditAppender,
            MysqlLocalExampleCatalog catalog,
            ToolRuntimeReadiness runtimeReadiness,
            ObjectMapper objectMapper
    ) {
        this.toolRepository = Objects.requireNonNull(toolRepository, "toolRepository 不能为空");
        this.transactionOperations = Objects.requireNonNull(transactionOperations, "transactionOperations 不能为空");
        this.auditAppender = Objects.requireNonNull(auditAppender, "auditAppender 不能为空");
        this.catalog = Objects.requireNonNull(catalog, "catalog 不能为空");
        this.runtimeReadiness = Objects.requireNonNull(runtimeReadiness, "runtimeReadiness 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 仅示例租户可以查看固定目录。 */
    public List<LocalToolExampleSummary> list(PrincipalRef principal) {
        Objects.requireNonNull(principal, "principal 不能为空");
        if (!MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID.equals(principal.tenantId())) {
            return List.of();
        }
        return catalog.list().stream().map(example -> summary(principal, example)).toList();
    }

    /** 在一个事务内保存固定定义并写入严格审计。 */
    public LocalToolExampleSummary install(PrincipalRef principal, String key) {
        Objects.requireNonNull(principal, "principal 不能为空");
        requireExampleTenant(principal);
        MysqlLocalExampleCatalog.LocalExample example = catalog.find(key)
                .orElseThrow(() -> notFound("内置 LOCAL 示例不存在"));
        ToolDefinition target = example.persistentDefinition(principal.principalId());
        ToolDefinition existing = toolRepository.findByTenantAndId(principal.tenantId(), target.id()).orElse(null);
        if (existing != null) {
            if (!sameManagedDefinition(existing, target)) {
                throw conflict();
            }
            return summary(principal, example);
        }
        if (toolRepository.listByTenant(principal.tenantId()).stream()
                .anyMatch(tool -> tool.name().equals(target.name()) && !tool.id().equals(target.id()))) {
            throw conflict();
        }
        try {
            transactionOperations.executeWithoutResult(status -> {
                toolRepository.save(target);
                auditAppender.append(principal.tenantId(), principal.principalId(), "LOCAL_EXAMPLE_INSTALL", "TOOL",
                        target.id().toString(), "SUCCEEDED", "内置 LOCAL 示例安装成功");
            });
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "内置 LOCAL 示例与现有工具冲突", exception);
        }
        return summary(principal, example);
    }

    private LocalToolExampleSummary summary(PrincipalRef principal, MysqlLocalExampleCatalog.LocalExample example) {
        ToolDefinition target = example.persistentDefinition(principal.principalId());
        ToolDefinition existing = toolRepository.findByTenantAndId(principal.tenantId(), target.id()).orElse(null);
        boolean installed = existing != null && sameManagedDefinition(existing, target);
        return new LocalToolExampleSummary(example.key(), target.id(), target.name(), target.description(),
                readSchema(target.inputSchema()), example.sampleInput(), installed,
                installed && runtimeReadiness.isReady(existing, null));
    }

    private JsonNode readSchema(String schema) {
        try {
            return objectMapper.readTree(schema);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("内置 LOCAL 示例 Schema 无效", exception);
        }
    }

    private void requireExampleTenant(PrincipalRef principal) {
        if (!MysqlLocalExampleCatalog.EXAMPLE_TENANT_ID.equals(principal.tenantId())) {
            throw notFound("内置 LOCAL 示例不存在");
        }
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "内置 LOCAL 示例与现有工具冲突");
    }

    private boolean sameManagedDefinition(ToolDefinition existing, ToolDefinition target) {
        return existing.id().equals(target.id())
                && existing.tenantId().equals(target.tenantId())
                && existing.name().equals(target.name())
                && existing.description().equals(target.description())
                && existing.type() == target.type()
                && existing.inputSchema().equals(target.inputSchema())
                && existing.riskLevel() == target.riskLevel()
                && existing.enabled() == target.enabled()
                && existing.endpoint().equals(target.endpoint());
    }
}
