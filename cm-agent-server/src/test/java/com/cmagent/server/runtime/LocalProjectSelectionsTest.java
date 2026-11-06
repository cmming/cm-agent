package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用受控选择器替身核验异步票据队列，不打开真实系统窗口；原生目录身份另有集成测试。 */
class LocalProjectSelectionsTest {
    private final PrincipalRef owner = new PrincipalRef(UUID.randomUUID(), "Owner", "测试", Set.of("project:manage"));
    private final LocalProjectAccessService access = mock(LocalProjectAccessService.class);
    private final LocalProjectDirectoryTickets tickets = mock(LocalProjectDirectoryTickets.class);

    @BeforeEach void setup() {
        when(access.reject(any(), any(), anyString(), anyInt(), anyString())).thenAnswer(call ->
                new LocalProjectAccessException(call.getArgument(2), "selection-test-error", call.getArgument(3), call.getArgument(4)));
    }

    @Test void 重复查询及开始不延长固定期限且过期窗口结果不能签发票据() throws Exception {
        var time = new AtomicReference<>(Instant.parse("2026-10-29T00:00:00Z"));
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var selections = new LocalProjectSelections(() -> { release.await(); return calls.incrementAndGet() == 1 ? Optional.of(Path.of("test-only-directory")) : Optional.empty(); },
                tickets, access, clock(time), Duration.ofSeconds(30))) {
            var id = UUID.randomUUID(); var first = selections.start(owner, id);
            time.set(time.get().plusSeconds(10));
            assertThat(selections.get(owner, id).expiresAt()).isEqualTo(first.expiresAt());
            assertThat(selections.start(owner, id).expiresAt()).isEqualTo(first.expiresAt());
            time.set(first.expiresAt());
            assertThatThrownBy(() -> selections.get(owner, id)).isInstanceOfSatisfying(LocalProjectAccessException.class,
                    failure -> { assertThat(failure.errorCode()).isEqualTo("PROJECT_SELECTION_NOT_FOUND"); assertThat(failure.status()).isEqualTo(404); });
            release.countDown();
            // 后续新请求可被接收代表前一个选择的finally已释放busy，而旧结果没有签发授权。
            UUID next = UUID.randomUUID();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(selections.start(owner, next)).isNotNull());
            verify(tickets, never()).issue(eq(owner), eq(id), any());
        } finally { release.countDown(); }
    }

    @Test void 精确owner和租户不可探测或复用他人的请求() throws Exception {
        var release = new CountDownLatch(1);
        try (var selections = new LocalProjectSelections(() -> { release.await(); return Optional.empty(); }, tickets, access,
                Clock.systemUTC(), Duration.ofMinutes(1))) {
            UUID id = UUID.randomUUID(); selections.start(owner, id);
            for (var other : List.of(new PrincipalRef(UUID.randomUUID(), "Owner", "测试", Set.of()),
                    new PrincipalRef(owner.tenantId(), "owner", "测试", Set.of()), new PrincipalRef(owner.tenantId(), "Owner ", "测试", Set.of()))) {
                assertThatThrownBy(() -> selections.get(other, id)).isInstanceOfSatisfying(LocalProjectAccessException.class,
                        failure -> assertThat(failure.status()).isEqualTo(404));
                assertThatThrownBy(() -> selections.start(other, id)).isInstanceOf(LocalProjectAccessException.class);
            }
            assertThat(selections.get(owner, id).state()).isEqualTo(LocalProjectSelections.State.SELECTING);
        } finally { release.countDown(); }
    }

    @Test void 取消无票据且容量达到三十二项后拒绝新请求直到固定到期() throws Exception {
        var time = new AtomicReference<>(Instant.parse("2026-10-29T00:00:00Z"));
        try (var selections = new LocalProjectSelections(Optional::empty, tickets, access, clock(time), Duration.ofSeconds(30))) {
            for (int i = 0; i < 32; i++) {
                UUID id = UUID.randomUUID();
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(selections.start(owner, id)).isNotNull());
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    var view = selections.get(owner, id);
                    assertThat(view.state()).isEqualTo(LocalProjectSelections.State.CANCELLED); assertThat(view.ticketId()).isNull();
                });
            }
            assertThatThrownBy(() -> selections.start(owner, UUID.randomUUID())).isInstanceOfSatisfying(LocalProjectAccessException.class,
                    failure -> { assertThat(failure.errorCode()).isEqualTo("PROJECT_SELECTION_BUSY"); assertThat(failure.status()).isEqualTo(409); });
            time.set(time.get().plusSeconds(30)); UUID next = UUID.randomUUID();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(selections.start(owner, next)).isNotNull());
            verifyNoInteractions(tickets);
        }
    }

    @Test void 关闭后拒绝新窗口且在途原生结果不签发目录授权() throws Exception {
        var release = new CountDownLatch(1);
        var selections = new LocalProjectSelections(() -> { release.await(); return Optional.of(Path.of("test-only-directory")); },
                tickets, access, Clock.systemUTC(), Duration.ofMinutes(1));
        UUID id = UUID.randomUUID(); selections.start(owner, id); selections.close(); release.countDown();
        assertThatThrownBy(() -> selections.start(owner, UUID.randomUUID())).isInstanceOf(LocalProjectAccessException.class);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(selections.get(owner, id).state()).isEqualTo(LocalProjectSelections.State.CANCELLED));
        verifyNoInteractions(tickets);
    }

    /** 时钟只在测试推进，不等待真实TTL；线程通过AtomicReference观察同一个可信时间。 */
    private static Clock clock(AtomicReference<Instant> time) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
    }
}
