package com.cmagent.server.runtime;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.LocalClientOperation;
import com.cmagent.core.repository.LocalClientOperationRepository;
import com.cmagent.core.repository.LocalClientRunGrantRepository;
import com.cmagent.core.runtime.ToolInvocationInfrastructureException;
import com.cmagent.core.runtime.ToolInvocationRequest;
import com.cmagent.core.runtime.ToolInvocationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.LocalClientProperties;
import com.cmagent.server.security.SensitiveDataRedactor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** 本机操作的单向派发、在线结果等待和严格审计边界。 */
@Service
@ConditionalOnProperty(prefix = "cm-agent.local-client", name = "enabled", havingValue = "true")
public class LocalClientOperationService {
    private static final int MAX_RESULT_BODY_BYTES = 8 * 1024 * 1024;
    private final LocalClientOperationRepository operations;
    private final LocalClientRunGrantRepository grants;
    private final AuditAppender audit;
    private final ModelCredentialCipher cipher;
    private final ObjectMapper json;
    private final SensitiveDataRedactor redactor;
    private final LocalClientProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Map<UUID, CompletableFuture<DeliveryResult>> waiting = new ConcurrentHashMap<>();

    // 同类中还存在测试时钟构造器，生产装配需要明确指定。
    @org.springframework.beans.factory.annotation.Autowired
    public LocalClientOperationService(LocalClientOperationRepository operations,
            LocalClientRunGrantRepository grants, AuditAppender audit, ModelCredentialCipher cipher,
            ObjectMapper json, SensitiveDataRedactor redactor, LocalClientProperties properties,
            @org.springframework.lang.Nullable TransactionTemplate transaction) {
        this(operations, grants, audit, cipher, json, redactor, properties, transaction, Clock.systemUTC());
    }

    LocalClientOperationService(LocalClientOperationRepository operations,
            LocalClientRunGrantRepository grants, AuditAppender audit, ModelCredentialCipher cipher,
            ObjectMapper json, SensitiveDataRedactor redactor, LocalClientProperties properties,
            @org.springframework.lang.Nullable TransactionTemplate transaction, Clock clock) {
        this.operations = Objects.requireNonNull(operations);
        this.grants = Objects.requireNonNull(grants);
        this.audit = Objects.requireNonNull(audit);
        this.cipher = Objects.requireNonNull(cipher);
        this.json = Objects.requireNonNull(json);
        this.redactor = Objects.requireNonNull(redactor);
        this.properties = Objects.requireNonNull(properties);
        this.transaction = transaction;
        this.clock = Objects.requireNonNull(clock);
    }

    /** 由治理网关同步派发一次操作并在有界时间内等待当前 Server 实例上的结果。 */
    public ToolInvocationResult invoke(ToolInvocationRequest request, LocalClientToolCatalog.Kind kind) {
        Objects.requireNonNull(request, "工具调用请求不能为空");
        Objects.requireNonNull(kind, "本机工具类别不能为空");
        if (!kind.toolName().equals(request.toolName())) {
            return ToolInvocationResult.deniedProtected("本机工具名称与固定目录不一致");
        }
        var grant = grants.findActiveGrantByRun(request.tenantId(), request.principal().principalId(),
                request.runId(), clock.instant()).orElse(null);
        // Run 授权绑定特定 Agent；不能仅凭租户和 Run 标识把另一个 Agent 的请求送往该设备。
        if (grant == null || !grant.agentId().equals(request.agentId())
                || !grant.categories().contains(kind.category())) {
            return ToolInvocationResult.deniedProtected("本轮未授权该本机操作能力");
        }
        UUID operationId = UUID.randomUUID();
        Instant now = clock.instant();
        var operation = new LocalClientOperation(operationId, request.tenantId(),
                request.principal().principalId(), grant.deviceId(), request.agentId(),
                grant.conversationId(), request.runId(), request.toolId(), request.toolCallId(),
                kind.category(), cipher.encrypt(request.inputJson()),
                LocalClientOperation.State.PENDING, "", "", now,
                now.plus(properties.getOperationTimeout()), null, null, 1);
        var future = new CompletableFuture<DeliveryResult>();
        waiting.put(operationId, future);
        try {
            inTransaction(() -> {
                if (!operations.insertOperation(operation)) {
                    throw new ToolInvocationInfrastructureException("本机操作无法登记", null);
                }
                try {
                    audit.append(request.tenantId(), request.principal().principalId(), "LOCAL_CLIENT_OPERATION_QUEUE",
                            "LOCAL_CLIENT_OPERATION", operationId.toString(), "PENDING", "已登记本机客户端操作");
                } catch (RuntimeException failure) {
                    // 内存模式没有回滚；持有仓储监视器时取消任务，确保审计失败不能与领取竞争。
                    if (transaction == null) {
                        operations.cancelPending(operationId, operation.tenantId(), operation.ownerId(),
                                operation.deviceId(), operation.version(), clock.instant());
                    }
                    throw failure;
                }
                return Boolean.TRUE;
            });
        } catch (RuntimeException failure) {
            waiting.remove(operationId);
            throw failure;
        }
        try {
            DeliveryResult result = future.get(properties.getOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return result.success()
                    ? ToolInvocationResult.succeededProtected(result.output(), "本机操作已完成，编号：" + operationId)
                    : ToolInvocationResult.failedProtected(result.error());
        } catch (TimeoutException timeout) {
            expireWaitingOperation(grant, operationId, "本机操作结果等待超时，外部副作用可能已发生");
            return ToolInvocationResult.failedProtected("本机操作结果未知，外部副作用可能已发生，未自动重放");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            expireWaitingOperation(grant, operationId, "本机操作等待被中断，外部副作用可能已发生");
            throw new ToolInvocationInfrastructureException("本机操作等待已中断", interrupted);
        } catch (ExecutionException failure) {
            expireWaitingOperation(grant, operationId, "本机操作结果处理失败，外部副作用可能已发生");
            throw new ToolInvocationInfrastructureException("本机操作结果处理失败", failure.getCause());
        } finally {
            waiting.remove(operationId);
        }
    }

    /**
     * 客户端用已完成签名验证的设备身份领取最早任务。
     *
     * @param device 已完成本次挑战证明的活动设备
     * @return 有任务时返回解密请求，否则为空
     */
    public java.util.Optional<TaskView> claim(LocalClientDevice device) {
        Instant now = clock.instant();
        var claimed = inTransaction(() -> {
            var operation = operations.claimNextOperation(device.tenantId(), device.ownerId(), device.id(), now);
            operation.ifPresent(value -> {
                try {
                    audit.append(value.tenantId(), value.ownerId(), "LOCAL_CLIENT_OPERATION_DISPATCH",
                            "LOCAL_CLIENT_OPERATION", value.id().toString(), "DISPATCHED", "本机客户端已领取操作");
                } catch (RuntimeException failure) {
                    // 内存模式无法回滚领取；标记未知且不返回正文，禁止该任务再次派发。
                    if (transaction == null) {
                        operations.markUnknown(value.id(), value.tenantId(), value.ownerId(), value.deviceId(),
                                "派发审计失败", value.version(), clock.instant());
                    }
                    throw failure;
                }
            });
            return operation;
        });
        if (claimed.isEmpty()) return java.util.Optional.empty();
        var operation = claimed.orElseThrow();
        try {
            JsonNode request = json.readTree(cipher.decrypt(operation.encryptedRequest()));
            return java.util.Optional.of(new TaskView(operation.id(), operation.category(),
                    operation.version(), request));
        } catch (IOException | RuntimeException failure) {
            markUnknown(device, operation, "本机操作请求密文不可用");
            throw new ToolInvocationInfrastructureException("本机操作请求密文不可用", failure);
        }
    }

    /**
     * 登记签名客户端返回的终态并唤醒同一实例上的等待调用。
     *
     * @param device 已完成挑战证明的设备
     * @param operationId 客户端领取的操作
     * @param body 客户端返回的原始 JSON
     */
    public void complete(LocalClientDevice device, UUID operationId, byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_RESULT_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本机操作结果大小无效");
        }
        ResultPayload payload;
        try {
            payload = json.readValue(body, ResultPayload.class);
        } catch (IOException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本机操作结果格式无效");
        }
        if (payload == null || payload.state() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本机操作结果状态不能为空");
        }
        if (payload.state() != LocalClientOperation.State.SUCCEEDED
                && payload.state() != LocalClientOperation.State.FAILED
                && payload.state() != LocalClientOperation.State.UNKNOWN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本机操作结果终态无效");
        }
        LocalClientOperation operation = operations.findOperation(device.tenantId(), device.ownerId(),
                device.id(), operationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "本机操作不存在"));
        if (operation.state() != LocalClientOperation.State.DISPATCHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本机操作已处理或不可回报");
        }
        String encryptedResult = "";
        String safeError = "";
        if (payload.state() == LocalClientOperation.State.SUCCEEDED) {
            if (payload.output() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成功结果缺少输出");
            }
            try {
                encryptedResult = cipher.encrypt(json.writeValueAsString(payload.output()));
            } catch (IOException impossible) {
                throw new ToolInvocationInfrastructureException("本机操作结果无法加密", impossible);
            }
        } else {
            safeError = redactor.redact(payload.error() == null || payload.error().isBlank()
                    ? "本机操作失败" : payload.error());
            if (safeError.length() > 500) safeError = safeError.substring(0, 500);
        }
        String finalResult = encryptedResult;
        String finalError = safeError;
        inTransaction(() -> {
            if (!operations.completeOperation(operationId, device.tenantId(), device.ownerId(), device.id(),
                    payload.state(), finalResult, finalError, payload.expectedVersion(), clock.instant())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "本机操作结果版本已变化");
            }
            audit.append(device.tenantId(), device.ownerId(), "LOCAL_CLIENT_OPERATION_COMPLETE",
                    "LOCAL_CLIENT_OPERATION", operationId.toString(), payload.state().name(),
                    payload.state() == LocalClientOperation.State.SUCCEEDED
                            ? "本机客户端返回成功结果" : "本机客户端返回失败或未知结果");
            return Boolean.TRUE;
        });
        CompletableFuture<DeliveryResult> future = waiting.get(operationId);
        if (future != null) {
            future.complete(payload.state() == LocalClientOperation.State.SUCCEEDED
                    ? new DeliveryResult(true, outputText(payload.output()), "")
                    : new DeliveryResult(false, "", finalError));
        }
    }

    private void expireWaitingOperation(com.cmagent.core.security.OperationAuthorizationSnapshot grant,
                                        UUID operationId, String reason) {
        try {
            inTransaction(() -> {
                var operation = operations.findOperation(grant.tenantId(), grant.principalId(),
                        grant.deviceId(), operationId).orElse(null);
                if (operation == null) return Boolean.FALSE;
                if (operation.state() == LocalClientOperation.State.PENDING) {
                    operations.cancelPending(operationId, grant.tenantId(), grant.principalId(),
                            grant.deviceId(), operation.version(), clock.instant());
                    audit.append(grant.tenantId(), grant.principalId(), "LOCAL_CLIENT_OPERATION_CANCEL",
                            "LOCAL_CLIENT_OPERATION", operationId.toString(), "CANCELLED",
                            "本机操作在派发前结束等待");
                } else if (operation.state() == LocalClientOperation.State.DISPATCHED) {
                    operations.markUnknown(operationId, grant.tenantId(), grant.principalId(),
                            grant.deviceId(), reason, operation.version(), clock.instant());
                    audit.append(grant.tenantId(), grant.principalId(), "LOCAL_CLIENT_OPERATION_UNKNOWN",
                            "LOCAL_CLIENT_OPERATION", operationId.toString(), "UNKNOWN", reason);
                }
                return Boolean.TRUE;
            });
        } catch (RuntimeException ignored) {
            // 超时或被中断的主路径将返回受控未知结果；无法写入未知状态时保留原调用结果。
        }
    }

    private void markUnknown(LocalClientDevice device, LocalClientOperation operation, String reason) {
        try {
            operations.markUnknown(operation.id(), device.tenantId(), device.ownerId(), device.id(),
                    reason, operation.version(), clock.instant());
        } catch (RuntimeException ignored) {
            // 解密失败仍以基础设施异常结束；不自动重试或重新派发。
        }
    }

    private static String outputText(JsonNode output) {
        if (output == null || output.isNull()) return "";
        return output.isTextual() ? output.textValue() : output.toString();
    }

    private <T> T inTransaction(Supplier<T> operation) {
        if (transaction != null) return transaction.execute(status -> operation.get());
        // 内存仓储所有设备、授权及任务方法使用同一个监视器；审计与补偿须处于同一临界区。
        synchronized (operations) {
            return operation.get();
        }
    }

    private record DeliveryResult(boolean success, String output, String error) { }

    /**
     * 客户端领取到的任务视图；请求正文只在本次签名响应中返回，不写普通日志。
     *
     * @param operationId 操作标识
     * @param category 固定操作类别
     * @param version 回报结果所需的版本
     * @param request 解密后的工具输入 JSON
     */
    public record TaskView(UUID operationId, com.cmagent.core.security.OperationCategory category,
                           long version, JsonNode request) { }

    /**
     * 签名客户端结果。
     *
     * @param state 成功、失败或未知
     * @param expectedVersion 领取时的操作版本
     * @param output 成功的 JSON 输出
     * @param error 已脱敏失败原因
     */
    public record ResultPayload(LocalClientOperation.State state, long expectedVersion,
                                JsonNode output, String error) { }
}
