package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static com.cmagent.server.runtime.LocalProjectNativeException.Reason.*;

/**
 * 原生目录选择后签发的短时、一次性、主体绑定票据；不是浏览器路径登记接口。
 *
 * <p>仅受信桌面适配器可以调用issue。所有方法在实例锁内串行，同一请求重复签发不会覆盖原根，
 * 跨tenant/owner探测和重放统一不可用。目录路径只保留在本机内存，重启后必须重新选择。
 * 宿主入口还须检查本机来源、权限及严格审计，本类不接收浏览器宿主路径。</p>
 */
final class LocalProjectDirectoryTickets {
    /** 服务端期限来源，测试可替换时钟，不使用客户端时间。 */
    private final Clock clock;
    /** 固定签发期限，不因读取/重试续期。 */
    private final Duration ttl;
    /** 包含已消费墓碑的有界条目上限，防止重复请求绕过容量。 */
    private final int capacity;
    /** 本机临时记录，不写localStorage、日志或共享数据库。 */
    private final Map<UUID, Entry> entries = new HashMap<>();

    /**
     * @param clock 可信宿主时钟
     * @param ttl 有效期，必须大于零且不超过五分钟
     * @param capacity 同一实例最多保存条目数，限定1至128
     */
    LocalProjectDirectoryTickets(Clock clock, Duration ttl, int capacity) {
        this.clock = Objects.requireNonNull(clock);
        this.ttl = Objects.requireNonNull(ttl);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(5)) > 0 || capacity < 1 || capacity > 128)
            throw new IllegalArgumentException("目录选择票据预算不合法");
        this.capacity = capacity;
    }

    /**
     * 由真实桌面选择结果签发票据，不读取正文；同主体同requestId只能取得原票据。
     * @param principal 已认证主体，不能由HTTP请求体自行构造
     * @param requestId 当前明确选择动作的随机请求编号
     * @param directory 原生选择器返回的目录，不得接入模型或HTTP绝对路径字段
     * @return 不含宿主路径的短时选择票据
     * @throws IOException 目录身份不可核实、请求已消费或实例容量不足
     */
    synchronized Ticket issue(PrincipalRef principal, UUID requestId, Path directory) throws IOException {
        Objects.requireNonNull(principal); Objects.requireNonNull(requestId);
        prune();
        for (Entry entry : entries.values()) {
            if (entry.same(principal, requestId)) {
                if (entry.consumed) throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
                return entry.ticket;
            }
        }
        if (entries.size() >= capacity) throw new LocalProjectNativeException(SELECTION_CAPACITY);
        Root root = root(directory);
        var ticket = new Ticket(UUID.randomUUID(), requestId, clock.instant().plus(ttl));
        entries.put(ticket.id(), new Entry(principal.tenantId(), principal.principalId(), ticket, root));
        return ticket;
    }

    /**
     * 原子消费票据，错误主体不能消耗他人的授权；失败与不存在统一分类。
     * @param principal 已认证且将创建项目的主体
     * @param requestId 原选择动作编号
     * @param ticketId 原生结果返回的不可预测票据
     * @return 仅供本机项目登记服务使用的根身份，不得作为HTTP响应
     * @throws IOException 身份/请求/期限不符、已消费或原目录被替换
     */
    synchronized Root consume(PrincipalRef principal, UUID requestId, UUID ticketId) throws IOException {
        Objects.requireNonNull(principal); Objects.requireNonNull(requestId); Objects.requireNonNull(ticketId);
        prune();
        Entry entry = entries.get(ticketId);
        if (entry == null || !entry.same(principal, requestId) || entry.consumed)
            throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
        Root actual = root(entry.root.directory());
        if (!entry.root.equals(actual)) throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
        entry.consumed = true;
        return entry.root;
    }

    private void prune() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> !entry.ticket.expiresAt().isAfter(now));
    }

    /** 根身份不稳定时拒绝；完整私有目录/网络卷策略还由本机文件访问层逐次复核。 */
    private static Root root(Path path) throws IOException {
        try {
            if (path == null || !path.isAbsolute() || path.getParent() == null)
                throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
            Path normalized = path.toAbsolutePath().normalize();
            var attributes = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()
                    || !normalized.equals(normalized.toRealPath()))
                throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
            // 当前Windows JDK21的fileKey为空，使用同一目录句柄的卷/128位ID；不按路径降级。
            String identity = Platform.isWindows() ? WindowsLocalProjectDirectoryIdentity.read(normalized)
                    : attributes.fileKey() == null ? null : attributes.fileKey().toString();
            if (identity == null) throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
            return new Root(normalized, identity);
        } catch (LocalProjectNativeException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            // Files异常可能携带绝对路径，必须在原生内部边界转换为固定消息，不能附带原始cause。
            throw new LocalProjectNativeException(TICKET_UNAVAILABLE);
        }
    }

    /**
     * @param id 一次选择授权的随机票据标识
     * @param requestId 原明确选择动作编号，不能被另一请求复用
     * @param expiresAt 固定有效期，客户端不能延长
     */
    record Ticket(UUID id, UUID requestId, Instant expiresAt) {}

    /**
     * @param directory 本机可信根，只供内部登记持久化，不可返回HTTP或模型
     * @param identity 宿主文件标识，用于拒绝同路径被替换的目录
     */
    record Root(Path directory, String identity) {
        /** 根路径和文件标识不能因默认record字符串进入诊断日志。 */
        @Override public String toString() { return "Root[受保护的本机目录]"; }
    }

    /** 生命周期只覆盖一个实例的短期选择，消费后的墓碑保留至固定到期以阻止重复签发。 */
    private static final class Entry {
        /** 身份只来自认证上下文，不接受外部指定租户覆盖。 */
        private final UUID tenant;
        /** 精确主体标识，不忽略大小写或空白。 */
        private final String owner;
        /** 对外安全票据，不包含目录。 */
        private final Ticket ticket;
        /** 本机内存的原根身份；到期从实例删除。 */
        private final Root root;
        /** 在实例锁内单次推进，错误主体或重复请求不能修改。 */
        private boolean consumed;

        private Entry(UUID tenant, String owner, Ticket ticket, Root root) {
            this.tenant = tenant; this.owner = owner; this.ticket = ticket; this.root = root;
        }
        private boolean same(PrincipalRef principal, UUID request) {
            return tenant.equals(principal.tenantId()) && owner.equals(principal.principalId())
                    && ticket.requestId().equals(request);
        }
    }
}

