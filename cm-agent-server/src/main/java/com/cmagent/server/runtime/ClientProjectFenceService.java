package com.cmagent.server.runtime;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.persistence.JdbcClientProjectFenceAcknowledgements;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.diagnostic.ErrorDiagnosticLogger;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 签名设备代次确认的服务端边界；不会证明既有本机副作用已经停止。 */
public final class ClientProjectFenceService {
    private static final Logger log = LoggerFactory.getLogger(ClientProjectFenceService.class);
    private final JdbcClientProjectFenceAcknowledgements acknowledgements;
    private final AuditAppender audit;
    private final ErrorDiagnosticLogger diagnostics;

    /** @param acknowledgements 条件确认及成功审计仓储 @param audit 拒绝审计 @param diagnostics 安全失败日志 */
    public ClientProjectFenceService(JdbcClientProjectFenceAcknowledgements acknowledgements,
            AuditAppender audit, ErrorDiagnosticLogger diagnostics) {
        this.acknowledgements = Objects.requireNonNull(acknowledgements);
        this.audit = Objects.requireNonNull(audit);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    /**
     * 保存经设备签名的代次确认；失败不改变运行状态或解除未知副作用隔离。
     * @param device 签名及单次挑战验证后的设备，租户和主体从此可信身份取得
     * @param runId 请求目标运行，由仓储继续检查设备及来源
     * @param epoch 请求中已签名的正数代次，必须匹配数据库归属
     */
    public void acknowledge(LocalClientDevice device, UUID runId, long epoch) {
        Objects.requireNonNull(device); Objects.requireNonNull(runId);
        String errorId = UUID.randomUUID().toString();
        try {
            if (epoch < 1 || !acknowledgements.acknowledge(device, runId, epoch)) {
                String code = "CLIENT_PROJECT_FENCE_CONFLICT";
                audit.appendControlled(device.tenantId(), device.ownerId(), "CLIENT_PROJECT_FENCE_ACK",
                        "CLIENT_PROJECT_RUN", runId.toString(), "DENIED", "errorCode=" + code + " errorId=" + errorId);
                log.warn("errorId={} operation=CLIENT_PROJECT_FENCE_ACK errorCode={} tenantId={} principalId={} resourceType=CLIENT_PROJECT_RUN resourceId={}",
                        errorId, code, device.tenantId(), device.ownerId(), runId);
                throw new LocalProjectAccessException(code, errorId, 409, "设备运行代次或来源已变化，请重新核对设备状态");
            }
        } catch (LocalProjectAccessException expected) {
            throw expected;
        } catch (RuntimeException failure) {
            // 异常可能包含 SQL 或内部地址；保留调用栈位置并丢弃不受控消息。
            var safe = new IllegalStateException("设备代次确认基础设施失败，异常内容已脱敏");
            safe.setStackTrace(failure.getStackTrace());
            diagnostics.error(new ErrorDiagnosticLogger.DiagnosticContext(errorId, "CLIENT_PROJECT_FENCE_ACK",
                    "CLIENT_PROJECT_FENCE_UNAVAILABLE", device.tenantId().toString(), device.ownerId(),
                    null, runId.toString(), null, null, "CLIENT_PROJECT"), safe,
                    "CLIENT_PROJECT_RUN", runId.toString());
            throw new LocalProjectAccessException("CLIENT_PROJECT_FENCE_UNAVAILABLE", errorId, 503,
                    "设备代次确认暂不可用，请凭错误编号联系管理员");
        }
    }

    /**
     * 为已验签设备读取当前运行代次和不透明根身份。
     * @param device 已验签设备，可信租户和主体从此处取得
     * @param runId 请求中的运行，仓储仍会校验设备归属和有效租期
     * @return 当前阶段、代次和根身份；不返回文件系统路径
     */
    public FenceView current(LocalClientDevice device, UUID runId) {
        Objects.requireNonNull(device); Objects.requireNonNull(runId);
        String errorId = UUID.randomUUID().toString();
        try {
            var current = acknowledgements.current(device, runId).orElseThrow(() -> {
                String code = "CLIENT_PROJECT_FENCE_CONFLICT";
                audit.appendControlled(device.tenantId(), device.ownerId(), "CLIENT_PROJECT_FENCE_READ",
                        "CLIENT_PROJECT_RUN", runId.toString(), "DENIED", "errorCode=" + code + " errorId=" + errorId);
                log.warn("errorId={} operation=CLIENT_PROJECT_FENCE_READ errorCode={} tenantId={} principalId={} resourceType=CLIENT_PROJECT_RUN resourceId={}",
                        errorId, code, device.tenantId(), device.ownerId(), runId);
                return new LocalProjectAccessException(code, errorId, 409, "设备运行代次或来源不可用，请重新核对设备状态");
            });
            return new FenceView(current.runId(), current.rootId(), current.rootIdentity(), current.epoch(),
                    current.phase().name(), current.acknowledged());
        } catch (LocalProjectAccessException expected) {
            throw expected;
        } catch (RuntimeException failure) {
            var safe = new IllegalStateException("设备代次读取基础设施失败，异常内容已脱敏");
            safe.setStackTrace(failure.getStackTrace());
            diagnostics.error(new ErrorDiagnosticLogger.DiagnosticContext(errorId, "CLIENT_PROJECT_FENCE_READ",
                    "CLIENT_PROJECT_FENCE_UNAVAILABLE", device.tenantId().toString(), device.ownerId(),
                    null, runId.toString(), null, null, "CLIENT_PROJECT"), safe,
                    "CLIENT_PROJECT_RUN", runId.toString());
            throw new LocalProjectAccessException("CLIENT_PROJECT_FENCE_UNAVAILABLE", errorId, 503,
                    "设备代次读取暂不可用，请凭错误编号联系管理员");
        }
    }

    /**
     * 设备可以核对的运行代次，不包含本机绝对路径。
     * @param runId 已固定来源的运行标识
     * @param rootId 已登记的设备根标识
     * @param rootIdentity 目录对象身份摘要
     * @param epoch 当前有效代次
     * @param phase 执行或受限恢复阶段
     * @param acknowledged 当前设备是否已确认此代次
     */
    public record FenceView(UUID runId, UUID rootId, String rootIdentity, long epoch, String phase, boolean acknowledged) { }
}
