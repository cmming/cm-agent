package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.repository.LocalClientRunGrantRepository;
import com.cmagent.core.repository.ToolDefinitionRepository;
import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.OperationAuthorizationSnapshot;
import com.cmagent.core.security.OperationCategory;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.LocalClientProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** 创建、复核和撤销仅绑定一次 Run 的设备操作授权。 */
@Service
@ConditionalOnProperty(prefix = "cm-agent.local-client", name = "enabled", havingValue = "true")
public class LocalClientAuthorizationService {
    private static final long POLICY_VERSION = 1;
    private final LocalClientRunGrantRepository grants;
    private final LocalClientDeviceRepository devices;
    private final ToolDefinitionRepository toolDefinitions;
    private final AuditAppender audit;
    private final LocalClientProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    // 同时保留供测试注入时钟的构造器时，显式指定 Spring 使用的生产构造器。
    @org.springframework.beans.factory.annotation.Autowired
    public LocalClientAuthorizationService(LocalClientRunGrantRepository grants,
            LocalClientDeviceRepository devices, ToolDefinitionRepository toolDefinitions,
            AuditAppender audit, LocalClientProperties properties,
            @org.springframework.lang.Nullable TransactionTemplate transaction) {
        this(grants, devices, toolDefinitions, audit, properties, transaction, Clock.systemUTC());
    }

    LocalClientAuthorizationService(LocalClientRunGrantRepository grants,
            LocalClientDeviceRepository devices, ToolDefinitionRepository toolDefinitions,
            AuditAppender audit, LocalClientProperties properties,
            @org.springframework.lang.Nullable TransactionTemplate transaction, Clock clock) {
        this.grants = Objects.requireNonNull(grants);
        this.devices = Objects.requireNonNull(devices);
        this.toolDefinitions = Objects.requireNonNull(toolDefinitions);
        this.audit = Objects.requireNonNull(audit);
        this.properties = Objects.requireNonNull(properties);
        this.transaction = transaction;
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 在运行、用户消息和会话已由服务端固定后创建单轮授权。
     *
     * @param principal 运行发起主体
     * @param agentId 当前 Agent
     * @param conversationId 当前会话
     * @param runId 当前运行
     * @param deviceId 浏览器选择的已配对设备
     * @param mode 用户选择的确认方式
     * @param requestedCategories 用户选择的能力集合
     * @return 已持久化授权
     */
    public OperationAuthorizationSnapshot createForRun(PrincipalRef principal, UUID agentId,
            UUID conversationId, UUID runId, UUID deviceId,
            OperationAuthorizationMode mode, Set<OperationCategory> requestedCategories) {
        Objects.requireNonNull(principal);
        Objects.requireNonNull(agentId);
        Objects.requireNonNull(conversationId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(mode);
        Set<OperationCategory> categories = normalizeCategories(requestedCategories);
        LocalClientDevice device = devices.find(principal.tenantId(), principal.principalId(), deviceId)
                .filter(value -> value.state() == LocalClientDevice.State.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "本机设备不存在或已撤销"));
        Instant now = clock.instant();
        var grant = new OperationAuthorizationSnapshot(UUID.randomUUID(), principal.tenantId(),
                principal.principalId(), agentId, conversationId, runId, device.id(), mode,
                categories, POLICY_VERSION, now, now.plus(properties.getRunGrantTtl()));
        return inTransaction(() -> {
            try {
                provisionTools(principal.tenantId());
                if (!grants.insertGrant(grant)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮已有本机设备授权或运行状态已变化");
                }
                audit.append(principal.tenantId(), principal.principalId(), "LOCAL_CLIENT_RUN_GRANT_CREATE",
                        "LOCAL_CLIENT_RUN_GRANT", grant.authorizationId().toString(), "ACTIVE",
                        "已创建单轮本机客户端授权");
                return grant;
            } catch (RuntimeException failure) {
                if (transaction == null) {
                    try {
                        grants.revokeGrant(principal.tenantId(), principal.principalId(),
                                grant.authorizationId(), clock.instant());
                    } catch (RuntimeException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        });
    }

    /** 读取本次运行仍有效且未撤销的唯一设备授权。 */
    public Optional<OperationAuthorizationSnapshot> active(PrincipalRef principal, UUID runId) {
        return grants.findActiveGrantByRun(principal.tenantId(), principal.principalId(), runId, clock.instant());
    }

    /** 本轮授权对应的虚拟工具；授权撤销或过期后不再注入。 */
    public List<com.cmagent.core.domain.ToolDefinition> tools(OperationAuthorizationSnapshot grant) {
        if (grant == null || !grant.isWithinTimeWindow(clock.instant())) return List.of();
        return LocalClientToolCatalog.definitions(grant.tenantId()).stream()
                .filter(tool -> grant.categories().contains(
                        LocalClientToolCatalog.category(grant.tenantId(), tool.id())))
                .toList();
    }

    /** 浏览器或运行终态撤销当前 Run 授权，不重试或回滚已派发外部操作。 */
    public void revoke(PrincipalRef principal, UUID authorizationId) {
        inTransaction(() -> {
            if (!grants.revokeGrant(principal.tenantId(), principal.principalId(),
                    authorizationId, clock.instant())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "授权已撤销、过期或不存在");
            }
            audit.append(principal.tenantId(), principal.principalId(), "LOCAL_CLIENT_RUN_GRANT_REVOKE",
                    "LOCAL_CLIENT_RUN_GRANT", authorizationId.toString(), "REVOKED",
                    "已撤销单轮本机客户端授权");
            return Boolean.TRUE;
        });
    }

    private static Set<OperationCategory> normalizeCategories(Set<OperationCategory> requested) {
        if (requested == null || requested.isEmpty() || requested.contains(OperationCategory.UNKNOWN)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本机操作能力选择无效");
        }
        EnumSet<OperationCategory> categories = EnumSet.noneOf(OperationCategory.class);
        for (OperationCategory category : requested) {
            if (category == OperationCategory.FILE_CHANGE) {
                categories.add(OperationCategory.READ_ONLY);
            }
            if (category != OperationCategory.READ_ONLY && category != OperationCategory.FILE_CHANGE
                    && category != OperationCategory.NETWORK && category != OperationCategory.COMMAND) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的本机操作能力");
            }
            categories.add(category);
        }
        return Set.copyOf(categories);
    }

    /** 为运行历史外键建立租户内固定工具墓碑，已存在时必须保持同一系统定义。 */
    private void provisionTools(UUID tenantId) {
        for (var expected : LocalClientToolCatalog.definitions(tenantId)) {
            var current = toolDefinitions.findByTenantAndId(tenantId, expected.id());
            if (current.isEmpty()) {
                toolDefinitions.save(expected);
                continue;
            }
            var stored = current.orElseThrow();
            if (!stored.name().equals(expected.name()) || stored.type() != expected.type()
                    || !Objects.equals(stored.endpoint(), expected.endpoint())) {
                throw new IllegalStateException("本机客户端固定工具定义与持久记录冲突");
            }
        }
    }

    private <T> T inTransaction(Supplier<T> operation) {
        if (transaction != null) return transaction.execute(status -> operation.get());
        // 内存仓储与派发共用同一监视器，授权只有审计完成后才能被其他线程观察。
        synchronized (grants) {
            return operation.get();
        }
    }
}
