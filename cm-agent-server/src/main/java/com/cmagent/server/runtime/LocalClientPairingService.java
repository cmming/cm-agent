package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.core.domain.LocalClientPairing;
import com.cmagent.core.repository.LocalClientDeviceRepository;
import com.cmagent.core.repository.LocalClientPairingRepository;
import com.cmagent.core.security.DevicePairingSignatures;
import com.cmagent.server.audit.AuditAppender;
import com.cmagent.server.config.LocalClientProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** 编排客户端配对包登记、浏览器确认、设备撤销与公开状态查询。 */
@Service
@ConditionalOnProperty(prefix = "cm-agent.local-client", name = "enabled", havingValue = "true")
public class LocalClientPairingService {
    private final LocalClientPairingRepository pairings;
    private final LocalClientDeviceRepository devices;
    private final AuditAppender audit;
    private final LocalClientProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /**
     * @param pairings 配对会话仓储
     * @param devices 活动设备仓储
     * @param audit 严格审计入口
     * @param properties 已校验的功能配置
     * @param transaction JDBC 短事务；memory 模式可为空
     */
    // 测试构造器额外接收时钟；生产装配必须固定使用该构造器。
    @org.springframework.beans.factory.annotation.Autowired
    public LocalClientPairingService(LocalClientPairingRepository pairings, LocalClientDeviceRepository devices,
            AuditAppender audit, LocalClientProperties properties,
            @org.springframework.lang.Nullable TransactionTemplate transaction) {
        this(pairings, devices, audit, properties, transaction, Clock.systemUTC());
    }

    LocalClientPairingService(LocalClientPairingRepository pairings, LocalClientDeviceRepository devices,
            AuditAppender audit, LocalClientProperties properties,
            @org.springframework.lang.Nullable TransactionTemplate transaction, Clock clock) {
        this.pairings = Objects.requireNonNull(pairings, "pairings 不能为空");
        this.devices = Objects.requireNonNull(devices, "devices 不能为空");
        this.audit = Objects.requireNonNull(audit, "audit 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.transaction = transaction;
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 接收浏览器转交的客户端签名配对包并登记待确认会话。
     *
     * @param principal 已登录浏览器的可信主体
     * @param request 客户端生成并签名的配对字段
     * @return 待确认配对安全视图
     */
    public PairingView begin(PrincipalRef principal, PairingPackage request) {
        Objects.requireNonNull(request, "配对包不能为空");
        verifyPackage(request);
        Instant now = clock.instant();
        var pairing = new LocalClientPairing(request.requestId(), principal.tenantId(),
                principal.principalId(), request.deviceName(), request.platform(), request.publicKey(),
                request.nonce(), request.proof(), LocalClientPairing.State.PENDING, null,
                now, now.plus(properties.getPairingTtl()), null);
        return inTransaction(() -> {
            try {
                if (!pairings.insertPairing(pairing)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "配对请求标识已存在");
                }
                audit.append(principal.tenantId(), principal.principalId(), "LOCAL_CLIENT_PAIRING_CREATE",
                        "LOCAL_CLIENT_PAIRING", pairing.id().toString(), "PENDING", "已登记待确认设备配对");
                return PairingView.from(pairing, now);
            } catch (RuntimeException failure) {
                compensatePairing(pairing, failure);
                throw failure;
            }
        });
    }

    /**
     * 由同一认证主体确认待配对客户端，原子创建设备活动记录。
     *
     * @param principal 当前认证主体
     * @param pairingId 客户端配对请求标识
     * @return 已确认设备安全视图
     */
    public PairingView confirm(PrincipalRef principal, UUID pairingId) {
        LocalClientPairing current = requirePairing(pairingId);
        Instant now = clock.instant();
        if (!current.tenantId().equals(principal.tenantId())
                || !current.ownerId().equals(principal.principalId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配对请求不存在");
        }
        if (current.state() != LocalClientPairing.State.PENDING || !now.isBefore(current.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE, "配对请求已处理或已过期");
        }
        verifyPackage(new PairingPackage(current.id(), current.nonce(), current.deviceName(),
                current.platform(), current.publicKey(), current.proof()));
        var device = new LocalClientDevice(UUID.randomUUID(), principal.tenantId(), principal.principalId(),
                current.deviceName(), current.platform(), current.publicKey(), LocalClientDevice.State.ACTIVE,
                1, now, now);
        return inTransaction(() -> {
            try {
                if (!pairings.confirmPairing(principal.tenantId(), principal.principalId(), pairingId, device, now)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "配对已被其他请求处理");
                }
                audit.append(principal.tenantId(), principal.principalId(), "LOCAL_CLIENT_PAIRING_CONFIRM",
                        "LOCAL_CLIENT_DEVICE", device.id().toString(), "ACTIVE", "已确认本机客户端配对");
                return PairingView.from(new LocalClientPairing(current.id(), current.tenantId(),
                        current.ownerId(), current.deviceName(), current.platform(), current.publicKey(),
                        current.nonce(), current.proof(), LocalClientPairing.State.CONFIRMED, device.id(),
                        current.createdAt(), current.expiresAt(), now), now);
            } catch (RuntimeException failure) {
                compensateDevice(principal, device, failure);
                throw failure;
            }
        });
    }

    /**
     * 返回配对状态的非秘密视图，供尚未登录的本机客户端轮询。
     *
     * @param pairingId 随机配对请求标识
     * @return 状态和确认后的设备标识
     */
    public PairingView status(UUID pairingId) {
        LocalClientPairing pairing = requirePairing(pairingId);
        Instant now = clock.instant();
        boolean inactiveDevice = pairing.state() == LocalClientPairing.State.CONFIRMED
                && devices.find(pairing.tenantId(), pairing.ownerId(), pairing.deviceId())
                        .filter(device -> device.state() == LocalClientDevice.State.ACTIVE).isEmpty();
        // 内存模式审计失败会撤销已创建的设备；轮询不能把不可用设备报告为配对成功。
        if (inactiveDevice || (pairing.state() == LocalClientPairing.State.PENDING && !now.isBefore(pairing.expiresAt()))) {
            pairing = new LocalClientPairing(pairing.id(), pairing.tenantId(), pairing.ownerId(),
                    pairing.deviceName(), pairing.platform(), pairing.publicKey(), pairing.nonce(),
                    pairing.proof(), LocalClientPairing.State.EXPIRED, null, pairing.createdAt(),
                    pairing.expiresAt(), null);
        }
        return PairingView.from(pairing, now);
    }

    /** 列出当前主体已配对设备。 */
    public List<DeviceView> devices(PrincipalRef principal) {
        return devices.listByOwner(principal.tenantId(), principal.principalId()).stream()
                .map(DeviceView::from).toList();
    }

    /**
     * 撤销当前主体持有的设备。
     *
     * @param principal 当前认证主体
     * @param deviceId 设备标识
     * @param expectedVersion 浏览器看到的版本
     * @return 撤销后的设备视图
     */
    public DeviceView revoke(PrincipalRef principal, UUID deviceId, long expectedVersion) {
        Instant now = clock.instant();
        var device = devices.find(principal.tenantId(), principal.principalId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        return inTransaction(() -> {
            if (!devices.revoke(principal.tenantId(), principal.principalId(), deviceId, expectedVersion, now)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "设备状态已变化，请刷新后重试");
            }
            audit.append(principal.tenantId(), principal.principalId(), "LOCAL_CLIENT_DEVICE_REVOKE",
                    "LOCAL_CLIENT_DEVICE", deviceId.toString(), "REVOKED", "已撤销本机客户端设备");
            return DeviceView.from(new LocalClientDevice(device.id(), device.tenantId(), device.ownerId(),
                    device.displayName(), device.platform(), device.publicKey(), LocalClientDevice.State.REVOKED,
                    device.version() + 1, device.createdAt(), now));
        });
    }

    private LocalClientPairing requirePairing(UUID pairingId) {
        return pairings.findPairing(Objects.requireNonNull(pairingId, "配对请求标识不能为空"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配对请求不存在"));
    }

    private void verifyPackage(PairingPackage request) {
        try {
            if (!DevicePairingSignatures.verify(request.requestId(), request.nonce(), request.deviceName(),
                    request.platform(), request.publicKey(), request.proof())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户端配对签名无效");
            }
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            // 无效 Base64 或公钥属于普通请求拒绝，不回显依赖异常内容。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户端配对包无效");
        }
    }

    private void compensatePairing(LocalClientPairing pairing, RuntimeException original) {
        if (transaction != null) return;
        try {
            pairings.expirePairing(pairing.tenantId(), pairing.ownerId(), pairing.id(), clock.instant());
        } catch (RuntimeException cleanup) {
            original.addSuppressed(cleanup);
        }
    }

    private void compensateDevice(PrincipalRef principal, LocalClientDevice device, RuntimeException original) {
        if (transaction != null) return;
        try {
            devices.find(principal.tenantId(), principal.principalId(), device.id())
                    .ifPresent(created -> devices.revoke(principal.tenantId(), principal.principalId(),
                            created.id(), created.version(), clock.instant()));
        } catch (RuntimeException cleanup) {
            original.addSuppressed(cleanup);
        }
    }

    private <T> T inTransaction(Supplier<T> operation) {
        if (transaction != null) return transaction.execute(status -> operation.get());
        // 内存设备和配对共用监视器，审计失败时须先失效再允许其他线程观察。
        synchronized (pairings) {
            return operation.get();
        }
    }

    /**
     * 浏览器转交的客户端配对包。
     *
     * @param requestId 客户端生成的随机请求标识
     * @param nonce 三十二字节随机值
     * @param deviceName 设备名称
     * @param platform 客户端平台
     * @param publicKey X.509 公钥 Base64 编码
     * @param proof 客户端签名 Base64 编码
     */
    public record PairingPackage(UUID requestId, byte[] nonce, String deviceName,
                                 LocalClientDevice.Platform platform, String publicKey, byte[] proof) {
        /** 防御性复制随机值和签名。 */
        public PairingPackage {
            nonce = nonce == null ? null : nonce.clone();
            proof = proof == null ? null : proof.clone();
        }
        @Override public byte[] nonce() { return nonce == null ? null : nonce.clone(); }
        @Override public byte[] proof() { return proof == null ? null : proof.clone(); }
    }

    /**
     * 配对状态的公开安全视图。
     *
     * @param pairingId 配对请求标识
     * @param state 当前状态
     * @param deviceName 设备名称
     * @param platform 设备平台
     * @param deviceId 确认后的设备标识
     * @param expiresAt 确认期限
     * @param tenantId 确认后的可信租户
     * @param ownerId 用于设备签名绑定的原主体标识
     * @param confirmedAt 确认时间
     */
    public record PairingView(UUID pairingId, LocalClientPairing.State state, String deviceName,
                              LocalClientDevice.Platform platform, UUID deviceId,
                              UUID tenantId, String ownerId, Instant expiresAt, Instant confirmedAt) {
        static PairingView from(LocalClientPairing pairing, Instant now) {
            var state = pairing.state();
            if (state == LocalClientPairing.State.PENDING && !now.isBefore(pairing.expiresAt())) {
                state = LocalClientPairing.State.EXPIRED;
            }
            return new PairingView(pairing.id(), state, pairing.deviceName(), pairing.platform(),
                    pairing.deviceId(), pairing.tenantId(), pairing.ownerId(),
                    pairing.expiresAt(), pairing.confirmedAt());
        }
    }

    /**
     * 已配对设备安全视图。
     *
     * @param deviceId 设备标识
     * @param displayName 设备名称
     * @param platform 设备平台
     * @param state 设备状态
     * @param version 条件更新版本
     * @param createdAt 配对时间
     * @param updatedAt 状态更新时间
     */
    public record DeviceView(UUID deviceId, String displayName, LocalClientDevice.Platform platform,
                             LocalClientDevice.State state, long version,
                             Instant createdAt, Instant updatedAt) {
        static DeviceView from(LocalClientDevice device) {
            return new DeviceView(device.id(), device.displayName(), device.platform(), device.state(),
                    device.version(), device.createdAt(), device.updatedAt());
        }
    }
}
