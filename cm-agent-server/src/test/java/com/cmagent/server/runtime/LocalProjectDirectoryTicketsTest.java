package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LocalProjectDirectoryTicketsTest {
    @TempDir Path temp;

    @Test void 票据不含宿主路径且只能由原主体请求消费一次() throws Exception {
        var tickets = store(Clock.systemUTC(), 8);
        var owner = owner(UUID.randomUUID(), "owner");
        UUID request = UUID.randomUUID();
        var ticket = tickets.issue(owner, request, temp);
        assertThat(ticket.toString()).doesNotContain(temp.toString());
        var root = tickets.consume(owner, request, ticket.id());
        assertThat(root.directory()).isEqualTo(temp.toRealPath());
        assertThat(root.toString()).doesNotContain(temp.toString(), root.identity());
        assertThatThrownBy(() -> tickets.consume(owner, request, ticket.id()))
                .isInstanceOf(LocalProjectNativeException.class).hasMessage("目录选择授权不存在或已失效，请重新选择");
        assertThatThrownBy(() -> tickets.issue(owner, request, temp)).isInstanceOf(LocalProjectNativeException.class);
    }

    @Test void 跨租户主体请求及大小写差异均拒绝且不消耗原票据() throws Exception {
        var tickets = store(Clock.systemUTC(), 8);
        UUID tenant = UUID.randomUUID(), request = UUID.randomUUID();
        var owner = owner(tenant, "Owner");
        var ticket = tickets.issue(owner, request, temp);
        for (var intruder : new PrincipalRef[]{owner(UUID.randomUUID(), "Owner"), owner(tenant, "owner"), owner(tenant, "Owner ")}) {
            assertThatThrownBy(() -> tickets.consume(intruder, request, ticket.id()))
                    .isInstanceOf(LocalProjectNativeException.class).hasNoCause();
        }
        assertThatThrownBy(() -> tickets.consume(owner, UUID.randomUUID(), ticket.id()))
                .isInstanceOf(LocalProjectNativeException.class);
        assertThat(tickets.consume(owner, request, ticket.id()).directory()).isEqualTo(temp.toRealPath());
    }

    @Test void 重复签发返回原结果不能替换为另一目录() throws Exception {
        var tickets = store(Clock.systemUTC(), 8);
        var owner = owner(UUID.randomUUID(), "owner");
        UUID request = UUID.randomUUID();
        var first = tickets.issue(owner, request, temp);
        Path other = Files.createDirectory(temp.resolve("other"));
        assertThat(tickets.issue(owner, request, other)).isEqualTo(first);
        assertThat(tickets.consume(owner, request, first.id()).directory()).isEqualTo(temp.toRealPath());
    }

    @Test void 到期失效且清理条目归还容量() throws Exception {
        var clock = new MutableClock();
        var tickets = store(clock, 1);
        var owner = owner(UUID.randomUUID(), "owner");
        UUID request = UUID.randomUUID();
        var first = tickets.issue(owner, request, temp);
        assertThatThrownBy(() -> tickets.issue(owner, UUID.randomUUID(), temp))
                .isInstanceOfSatisfying(LocalProjectNativeException.class,
                        error -> assertThat(error.reason()).isEqualTo(LocalProjectNativeException.Reason.SELECTION_CAPACITY));
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThatThrownBy(() -> tickets.consume(owner, request, first.id())).isInstanceOf(LocalProjectNativeException.class);
        assertThat(tickets.issue(owner, UUID.randomUUID(), temp).id()).isNotEqualTo(first.id());
    }

    @Test void 同路径目录替换后不能消费原授权() throws Exception {
        var tickets = store(Clock.systemUTC(), 8);
        var owner = owner(UUID.randomUUID(), "owner");
        UUID request = UUID.randomUUID();
        Path selected = Files.createDirectory(temp.resolve("selected"));
        var ticket = tickets.issue(owner, request, selected);
        Files.move(selected, temp.resolve("original"));
        Files.createDirectory(selected);
        assertThatThrownBy(() -> tickets.consume(owner, request, ticket.id()))
                .isInstanceOf(LocalProjectNativeException.class).hasNoCause();
    }

    @Test void 多线程消费同一票据只有一个获胜() throws Exception {
        var tickets = store(Clock.systemUTC(), 8);
        var owner = owner(UUID.randomUUID(), "owner");
        UUID request = UUID.randomUUID();
        var ticket = tickets.issue(owner, request, temp);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 32; i++) results.add(pool.submit(() -> {
                try { tickets.consume(owner, request, ticket.id()); return true; }
                catch (LocalProjectNativeException failure) { return false; }
            }));
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
    }

    @Test void 缺失目录相对路径和文件统一安全拒绝() throws Exception {
        var tickets = store(Clock.systemUTC(), 8);
        var owner = owner(UUID.randomUUID(), "owner");
        Path file = Files.writeString(temp.resolve("file.txt"), "合成正文");
        for (Path invalid : new Path[]{temp.resolve("missing"), Path.of("relative"), file}) {
            assertThatThrownBy(() -> tickets.issue(owner, UUID.randomUUID(), invalid))
                    .isInstanceOf(LocalProjectNativeException.class).hasNoCause()
                    .hasMessage("目录选择授权不存在或已失效，请重新选择");
        }
    }

    private static LocalProjectDirectoryTickets store(Clock clock, int capacity) {
        return new LocalProjectDirectoryTickets(clock, Duration.ofMinutes(2), capacity);
    }
    private static PrincipalRef owner(UUID tenant, String id) { return new PrincipalRef(tenant, id, "测试主体", Set.of()); }

    /** 使用服务端可控时钟覆盖恰好到期，不靠sleep碰运气。 */
    private static final class MutableClock extends Clock {
        /** 固定测试时刻，只由测试推进，不使用客户端时间。 */
        private Instant now = Instant.parse("2026-10-29T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

