package com.cmagent.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.bus.*;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每次 Harness 构造独占的空工作区和拒绝型宿主能力。
 *
 * <p>不接受请求中的目录或模型给出的路径。框架 2.0.2 仍会探测 AGENTS.md、查询收件箱及过期任务，
 * 这里只返回合成的缺失/空结果；其他调用记录粘滞失败，即使框架捕获异常也不能继续交付成功。</p>
 */
final class AgentScopeHarnessIsolation implements AutoCloseable {
    /** 本对象唯一持有的随机目录，不保存历史、凭据、转录或 Skill 文件。 */
    private final Path workspace;
    /** 首个越界操作，供事件边界恢复框架可能捕获的拒绝。 */
    private final AtomicReference<RuntimeException> violation = new AtomicReference<>();
    /** 构造器缺失探测之外，所有文件方法拒绝访问。 */
    private final AbstractFilesystem filesystem = new DenyingFilesystem();
    /** 仅提供空收件箱查询，不保存队列、事件或跨会话消息。 */
    private final MessageBus bus = new EmptyMessageBus();
    /** 仅提供空过期查询，工具不得脱离运行门控转入后台执行。 */
    private final AsyncToolRegistry registry = new EmptyAsyncRegistry();

    AgentScopeHarnessIsolation() {
        try {
            Path parent = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            workspace = Files.createTempDirectory(parent, "cm-agent-harness-").toAbsolutePath().normalize();
            try {
                // POSIX 从创建后的第一步收紧权限；Windows 设置所有者专用 ACL，不依赖全局临时目录权限。
                var posix = Files.getFileAttributeView(workspace, PosixFileAttributeView.class);
                if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rwx------"));
                else {
                    var acl = Files.getFileAttributeView(workspace, AclFileAttributeView.class);
                    if (acl == null) throw new IOException("文件系统不支持私有目录权限");
                    acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                            .setPrincipal(Files.getOwner(workspace))
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
                }
            } catch (IOException | RuntimeException failure) {
                Files.deleteIfExists(workspace);
                throw failure;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法创建 Harness 私有工作区", failure);
        }
    }

    Path workspace() { return workspace; }
    AbstractFilesystem filesystem() { return filesystem; }
    MessageBus bus() { return bus; }
    AsyncToolRegistry registry() { return registry; }

    /** 在构造和事件边界检查；框架吞掉原异常时仍传播同一个受控失败。 */
    void check() {
        RuntimeException failure = violation.get();
        if (failure != null) throw failure;
    }

    private RuntimeException denied() {
        violation.compareAndSet(null, new IllegalStateException("Harness 请求了未开放的宿主能力"));
        return violation.get();
    }

    /** 只清理本对象创建的绝对私有目录，不跟随符号链接或递归清理宿主工作区。 */
    @Override public void close() {
        try {
            if (!workspace.isAbsolute() || !workspace.getFileName().toString().startsWith("cm-agent-harness-"))
                throw new IOException("私有工作区归属无效");
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException ignored) {
            // close 幂等；不存在的自有目录不需要再次清理。
        } catch (IOException failure) {
            throw new IllegalStateException("Harness 私有工作区清理失败", failure);
        }
    }

    private final class DenyingFilesystem implements AbstractFilesystem {
        @Override public boolean exists(RuntimeContext context, String path) {
            if ("AGENTS.md".equals(path)) return false;
            throw denied();
        }
        @Override public LsResult ls(RuntimeContext c, String p) { throw denied(); }
        @Override public ReadResult read(RuntimeContext c, String p, int o, int l) { throw denied(); }
        @Override public WriteResult write(RuntimeContext c, String p, String s) { throw denied(); }
        @Override public EditResult edit(RuntimeContext c, String p, String o, String n, boolean a) { throw denied(); }
        @Override public GrepResult grep(RuntimeContext c, String p, String s, String g) { throw denied(); }
        @Override public GlobResult glob(RuntimeContext c, String p, String s) { throw denied(); }
        @Override public List<FileUploadResponse> uploadFiles(RuntimeContext c, List<Map.Entry<String,byte[]>> f) { throw denied(); }
        @Override public List<FileDownloadResponse> downloadFiles(RuntimeContext c, List<String> p) { throw denied(); }
        @Override public WriteResult delete(RuntimeContext c, String p) { throw denied(); }
        @Override public WriteResult move(RuntimeContext c, String f, String t) { throw denied(); }
    }

    private final class EmptyMessageBus implements MessageBus {
        @Override public Mono<List<BusEntry>> inboxDrain(String session, int count) { return Mono.just(List.of()); }
        @Override public Mono<String> queuePush(String k, Map<String,Object> p) { return Mono.error(denied()); }
        @Override public Mono<List<BusEntry>> queueDrain(String k, int n) { return Mono.error(denied()); }
        @Override public Mono<Void> queueDelete(String k) { return Mono.error(denied()); }
        @Override public Mono<Boolean> queuePeek(String k) { return Mono.error(denied()); }
        @Override public Mono<String> logAppend(String k, Map<String,Object> p, int n) { return Mono.error(denied()); }
        @Override public Mono<List<BusEntry>> logRead(String k, String s, int n) { return Mono.error(denied()); }
        @Override public Mono<Void> logTrim(String k) { return Mono.error(denied()); }
        @Override public Mono<Void> publish(String k, Map<String,Object> p) { return Mono.error(denied()); }
        @Override public Flux<Map<String,Object>> subscribe(String k) { return Flux.error(denied()); }
        @Override public void close() { /* 没有线程、文件或外部连接，由隔离对象统一持有生命周期。 */ }
    }

    private final class EmptyAsyncRegistry implements AsyncToolRegistry {
        @Override public Mono<List<AsyncToolRecord>> findStale(String s, Duration ttl) { return Mono.just(List.of()); }
        @Override public Mono<Void> register(AsyncToolRecord r) { return Mono.error(denied()); }
        @Override public Mono<Void> complete(String id, String result) { return Mono.error(denied()); }
        @Override public Mono<Void> fail(String id, String error) { return Mono.error(denied()); }
        @Override public Mono<Void> markTimeout(String id) { return Mono.error(denied()); }
    }
}

