package com.cmagent.server.runtime;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.cmagent.server.runtime.WindowsWorkspaceAclPolicy.*;
import static org.assertj.core.api.Assertions.*;

/** 纯SID策略不调用Windows，不用显示名或模拟账户来冒充真实NTFS验证。 */
class WorkspaceWindowsAclPolicyTest {
    private static final Path ROOT = Path.of("synthetic-root");
    private static final String OWNER = "S-1-5-21-1-2-3-1001";
    private static long deadline() { return System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos(); }
    private static Descriptor descriptor(String owner, Dacl dacl, List<Ace> entries, String fingerprint) {
        return new Descriptor(owner, dacl, entries, fingerprint);
    }
    private static Descriptor trusted() { return descriptor(OWNER, Dacl.PRESENT, List.of(new Ace(ALLOW, 0, -1, OWNER), new Ace(ALLOW, 16, -1, SYSTEM), new Ace(ALLOW, 3, -1, ADMINISTRATORS)), "fixed"); }

    @Test void 所有者和两个固定系统SID允许显式及继承权限() throws Exception {
        var policy = new WindowsWorkspaceAclPolicy((path, deadline) -> trusted());
        var access = policy.open(ROOT, deadline()); access.verify(ROOT.resolve("file.md")); access.finish();
    }
    @Test void 普通组即使只读也不因本轮兼容而放行() {
        for (String sid : List.of("S-1-1-0", "S-1-5-11", "S-1-5-32-545", "S-1-5-21-9-8-7-544")) {
            var value = descriptor(OWNER, Dacl.PRESENT, List.of(new Ace(ALLOW, 0, 0x120089, sid)), "fixed");
            assertThatThrownBy(() -> new WindowsWorkspaceAclPolicy((p,d) -> value).open(ROOT, deadline())).isInstanceOf(WorkspaceContextFiles.Invalid.class);
        }
    }
    @Test void SYSTEM或Administrators显示名不能代替SID() {
        for (String name : List.of("SYSTEM", "Administrators", "DOMAIN\\Administrators", "S-1-5-21-1-2-3-544")) {
            var value = descriptor(OWNER, Dacl.PRESENT, List.of(new Ace(ALLOW, 0, -1, name)), "fixed");
            assertThatThrownBy(() -> new WindowsWorkspaceAclPolicy((p,d) -> value).open(ROOT, deadline())).isInstanceOf(WorkspaceContextFiles.Invalid.class);
        }
    }
    @Test void 子文件所有者不能自行扩大根信任集合() throws Exception {
        var policy = new WindowsWorkspaceAclPolicy((p,d) -> p.equals(ROOT) ? trusted() : descriptor("S-1-5-21-9-8-7-1001", Dacl.PRESENT, trusted().entries(), "child"));
        var access = policy.open(ROOT, deadline());
        assertThatThrownBy(() -> access.verify(ROOT.resolve("file.md"))).isInstanceOf(WorkspaceContextFiles.Invalid.class);
    }
    @Test void 根已由SYSTEM或内置管理员拥有时集合去重仍有效() throws Exception {
        for (String owner : List.of(SYSTEM, ADMINISTRATORS)) {
            var value = descriptor(owner, Dacl.PRESENT, List.of(new Ace(ALLOW, 0, -1, owner)), "fixed");
            new WindowsWorkspaceAclPolicy((p,d) -> value).open(ROOT, deadline()).finish();
        }
    }
    @Test void NULL缺失和空DACL均失败关闭() {
        for (Dacl dacl : Dacl.values()) {
            var value = descriptor(OWNER, dacl, List.of(), "fixed");
            assertThatThrownBy(() -> new WindowsWorkspaceAclPolicy((p,d) -> value).open(ROOT, deadline())).isInstanceOf(WorkspaceContextFiles.Invalid.class);
        }
    }
    @Test void 未知ACE类型不能忽略且DENY不抵消不可信ALLOW() {
        for (List<Ace> entries : List.of(List.of(new Ace(5, 0, -1, OWNER)), List.of(new Ace(DENY, 0, -1, "S-1-1-0"), new Ace(ALLOW, 0, -1, "S-1-1-0")))) {
            var value = descriptor(OWNER, Dacl.PRESENT, entries, "fixed");
            assertThatThrownBy(() -> new WindowsWorkspaceAclPolicy((p,d) -> value).open(ROOT, deadline())).isInstanceOf(WorkspaceContextFiles.Invalid.class);
        }
    }
    @Test void 读后ACL指纹漂移即使新ACL仍可信也拒绝本轮交付() throws Exception {
        var calls = new AtomicInteger();
        var policy = new WindowsWorkspaceAclPolicy((p,d) -> descriptor(OWNER, Dacl.PRESENT, trusted().entries(), calls.getAndIncrement() == 0 ? "before" : "after"));
        var access = policy.open(ROOT, deadline());
        assertThatThrownBy(access::finish).isInstanceOf(WorkspaceContextFiles.Invalid.class);
    }
    @Test void 每次打开都重新读取且超时和中断不读取描述符() throws Exception {
        var calls = new AtomicInteger();var policy = new WindowsWorkspaceAclPolicy((p,d) -> {calls.incrementAndGet();return trusted();});
        policy.open(ROOT, deadline());policy.open(ROOT, deadline());assertThat(calls).hasValue(2);
        assertThatThrownBy(() -> policy.open(ROOT, System.nanoTime() - 1)).isInstanceOf(IOException.class);
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> policy.open(ROOT, deadline())).isInstanceOf(IOException.class); }
        finally { Thread.interrupted(); }
        assertThat(calls).hasValue(2);
    }
    @Test void 解析失败不返回缓存许可且元数据诊断不展开ACL() {
        var policy = new WindowsWorkspaceAclPolicy((p,d) -> {throw new IOException("固定基础设施失败");});
        assertThatThrownBy(() -> policy.open(ROOT, deadline())).isInstanceOf(IOException.class);
        assertThat(trusted().toString()).doesNotContain(OWNER, SYSTEM, ADMINISTRATORS);
    }
}
