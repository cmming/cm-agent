package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 单桌面有界原生选择队列；HTTP立即返回编号，原生STA线程只处理一个明确选择动作。 */
public final class LocalProjectSelections implements AutoCloseable {
    /** 原生窗口只能从受信适配器返回路径；测试替身不能出现在HTTP配置。 */
    private final Chooser chooser;
    /** 单次授权票据由本实例持有，重启不恢复原生选择。 */
    private final LocalProjectDirectoryTickets tickets;
    /** 权限、拒绝审计及最终诊断。 */
    private final LocalProjectAccessService access;
    /** 固定期限依据，不能使用浏览器时间。 */
    private final Clock clock;
    /** 单次请求最长保留期限，不随轮询续期。 */
    private final Duration ttl;
    /** 单实例最多32项，不允许无界提交弹出原生窗口。 */
    private final Map<UUID, Entry> requests = new HashMap<>();
    /** 不把服务关停依赖于正在等待人的模态窗口；禁止重复排队。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cm-agent-local-project-picker"); thread.setDaemon(true); return thread;
    });
    /** 对窗口创建、关闭和拒绝排队的判断在同一实例锁下进行。 */
    private boolean busy, closed;

    LocalProjectSelections(Chooser chooser, LocalProjectDirectoryTickets tickets, LocalProjectAccessService access,
            Clock clock, Duration ttl) {
        this.chooser = chooser; this.tickets = tickets; this.access = access; this.clock = clock; this.ttl = ttl;
    }

    /** @param principal 认证主体 @param requestId 明确选择动作的随机编号 @return 当前选择状态，不含宿主路径 */
    public synchronized View start(PrincipalRef principal, UUID requestId) {
        access.authorize(principal, "project:manage", requestId);
        prune();
        Entry old = requests.get(requestId);
        if (old != null) return view(principal, requestId, old);
        if (closed || busy || requests.size() >= 32)
            throw access.reject(principal, requestId, "PROJECT_SELECTION_BUSY", 409, "已有目录选择窗口或请求过多，请完成当前选择后重试");
        access.record(principal, requestId, "PROJECT_SELECTION", "STARTED");
        var entry = new Entry(principal, clock.instant().plus(ttl));
        requests.put(requestId, entry); busy = true;
        try { executor.execute(() -> choose(requestId, entry)); }
        catch (RejectedExecutionException failure) {
            requests.remove(requestId); busy = false;
            throw access.infrastructure(principal, requestId, "PROJECT_SELECTION_START", failure);
        }
        return snapshot(requestId, entry);
    }

    /** @param principal 认证主体 @param requestId 原选择动作编号 @return 固定期限内的同主体状态 */
    public synchronized View get(PrincipalRef principal, UUID requestId) {
        access.authorize(principal, "project:manage", requestId); prune();
        return view(principal, requestId, requests.get(requestId));
    }

    /** 只供项目登记服务使用，浏览器或模型不能提交绝对路径替代这个一次票据。 */
    LocalProjectDirectoryTickets.Root consume(PrincipalRef principal, UUID requestId, UUID ticketId) {
        access.authorize(principal, "project:manage", requestId);
        try { return tickets.consume(principal, requestId, ticketId); }
        catch (Exception failure) { throw access.reject(principal, requestId, "PROJECT_SELECTION_EXPIRED", 409, "目录选择授权已失效或已使用，请重新选择"); }
    }

    private void choose(UUID id, Entry entry) {
        try {
            Optional<Path> selected = chooser.choose();
            synchronized (this) {
                // 超期或服务关闭后到达的窗口结果不能重新签发授权；用户需重新执行明确选择。
                if (closed || !entry.expires.isAfter(clock.instant())) { entry.state = State.CANCELLED; return; }
                if (selected.isEmpty()) { access.record(entry.principal, id, "PROJECT_SELECTION", "CANCELLED"); entry.state = State.CANCELLED; return; }
                var ticket = tickets.issue(entry.principal, id, selected.get());
                access.record(entry.principal, id, "PROJECT_SELECTION", "SUCCEEDED");
                entry.expires = ticket.expiresAt();
                entry.ticket = ticket.id(); entry.state = State.SELECTED;
            }
        } catch (LocalProjectAccessException failure) {
            synchronized (this) { entry.errorId = failure.errorId(); entry.state = State.FAILED; }
        } catch (Exception failure) {
            synchronized (this) {
                var error = access.infrastructure(entry.principal, id, "PROJECT_NATIVE_SELECTION", failure);
                entry.errorId = error.errorId(); entry.state = State.FAILED;
            }
        } finally { synchronized (this) { busy = false; } }
    }

    private View view(PrincipalRef principal, UUID id, Entry entry) {
        if (entry == null || !entry.principal.tenantId().equals(principal.tenantId())
                || !entry.principal.principalId().equals(principal.principalId()))
            throw access.reject(principal, id, "PROJECT_SELECTION_NOT_FOUND", 404, "目录选择不存在或已过期");
        return snapshot(id, entry);
    }
    private static View snapshot(UUID id, Entry entry) { return new View(id, entry.state, entry.ticket, entry.expires, entry.errorId); }
    private void prune() { requests.entrySet().removeIf(value -> !value.getValue().expires.isAfter(clock.instant())); }

    /** 停止接受新窗口；已打开的系统模态窗口仍由用户关闭，关闭后结果作废，不自动选择。 */
    @Override public synchronized void close() { closed = true; executor.shutdown(); }

    /** 仅由同包配置装配原生选择器。 */
    @FunctionalInterface interface Chooser { Optional<Path> choose() throws Exception; }
    public enum State {
        /** 系统模态窗口仍在等待用户操作。 */ SELECTING,
        /** 已取得可单次消费的主体绑定票据。 */ SELECTED,
        /** 用户取消或窗口结果超过固定期限，无目录授权。 */ CANCELLED,
        /** 原生能力或基础设施失败，可凭编号定位。 */ FAILED
    }
    /** @param requestId 明确动作编号 @param state 选择状态 @param ticketId 单次票据，未选中时为空 @param expiresAt 固定到期时间 @param errorId 失败时同号诊断编号 */
    public record View(UUID requestId, State state, UUID ticketId, Instant expiresAt, String errorId) {}
    private static final class Entry {
        /** 当前认证上下文的不可变快照，不能从轮询请求覆盖。 */ private final PrincipalRef principal;
        /** 等待窗口时为请求期限；选中后一次固定为票据期限，轮询和重试不能续期。 */ private Instant expires;
        /** 仅在实例锁内更新。 */ private State state = State.SELECTING;
        /** 成功签发的不透明票据。 */ private UUID ticket;
        /** 已记录的失败关联号，不存异常正文。 */ private String errorId;
        Entry(PrincipalRef principal, Instant expires) { this.principal = principal; this.expires = expires; }
    }
}
