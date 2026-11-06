package com.cmagent.server.runtime;

import com.cmagent.api.PrincipalRef;
import com.sun.jna.Native;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.cmagent.server.runtime.LocalProjectFileException.Reason.*;

/** 只用自建临时根验证底层条件写入；没有绕过真实产品批准，此原语尚未对外装配。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsLocalProjectFileMutatorTest {
    @TempDir Path temp;
    private Path project;
    private LocalProjectDirectoryTickets.Root root;
    private LocalProjectFiles files;
    private WindowsLocalProjectFileMutator writer;

    @BeforeEach void setup() throws Exception {
        project = Files.createDirectory(temp.resolve("project"));
        var principal = new PrincipalRef(UUID.randomUUID(), "owner", "测试", Set.of());
        var tickets = new LocalProjectDirectoryTickets(Clock.systemUTC(), Duration.ofMinutes(2), 1);
        UUID request = UUID.randomUUID();
        var ticket = tickets.issue(principal, request, project);
        root = tickets.consume(principal, request, ticket.id());
        files = new LocalProjectFiles(List.of());
        writer = new WindowsLocalProjectFileMutator(files);
    }

    @Test void 创建并条件替换正文可缩短清空且不改变原权限() throws Exception {
        var created = writer.create(root, "a.txt", bytes("较长的合成原文"));
        assertThat(created.beforeSha256()).isEmpty();
        assertThat(created.afterSha256()).isEqualTo(files.read(root, "a.txt", 0, 1024).sha256());
        var acl = Files.getFileAttributeView(project.resolve("a.txt"), AclFileAttributeView.class);
        var beforeAcl = List.copyOf(acl.getAcl());
        var beforeOwner = acl.getOwner();
        var changed = writer.replace(root, "a.txt", created.afterSha256(), bytes("短文"));
        assertThat(Files.readString(project.resolve("a.txt"))).isEqualTo("短文");
        assertThat(acl.getAcl()).isEqualTo(beforeAcl);
        assertThat(acl.getOwner()).isEqualTo(beforeOwner);
        writer.replace(root, "a.txt", changed.afterSha256(), bytes(""));
        assertThat(Files.size(project.resolve("a.txt"))).isZero();
        Files.move(project.resolve("a.txt"), project.resolve("moved.txt"));
    }

    @Test void 目标存在及旧版本不符均零写入() throws Exception {
        Files.writeString(project.resolve("a.txt"), "外部内容");
        var conflict = catchThrowableOfType(() -> writer.create(root, "a.txt", bytes("覆盖")), LocalProjectMutationException.class);
        assertThat(conflict.possibleSideEffects()).isFalse();
        assertThat(conflict.reason()).isEqualTo(FILE_CHANGED);
        var stale = catchThrowableOfType(() -> writer.replace(root, "a.txt", "a".repeat(64), bytes("覆盖")), LocalProjectMutationException.class);
        assertThat(stale.possibleSideEffects()).isFalse();
        assertThat(Files.readString(project.resolve("a.txt"))).isEqualTo("外部内容");
    }

    @Test void 非法路径编码及私有文件在创建前拒绝() {
        for (String path : List.of("../outside.txt", ".env", "a.png")) {
            var failure = catchThrowableOfType(() -> writer.create(root, path, bytes("合成")), LocalProjectMutationException.class);
            assertThat(failure.possibleSideEffects()).isFalse();
            assertThat(failure).hasNoCause();
        }
        var invalid = catchThrowableOfType(() -> writer.create(root, "a.txt", new byte[]{(byte)0xff}), LocalProjectMutationException.class);
        assertThat(invalid.reason()).isEqualTo(INVALID_TEXT);
        assertThat(project.resolve("a.txt")).doesNotExist();
        assertThat(temp.resolve("outside.txt")).doesNotExist();
    }

    @Test void 硬链接目标不能被原地改写() throws Exception {
        Path outside = Files.writeString(temp.resolve("outside.txt"), "根外内容");
        try { Files.createLink(project.resolve("linked.txt"), outside); }
        catch (UnsupportedOperationException | FileSystemException unsupported) { Assumptions.assumeTrue(false, "不支持普通硬链接"); }
        var failure = catchThrowableOfType(() -> writer.replace(root, "linked.txt", "a".repeat(64), bytes("覆盖")), LocalProjectMutationException.class);
        assertThat(failure.possibleSideEffects()).isFalse();
        assertThat(failure.reason()).isEqualTo(UNSAFE_FILE);
        assertThat(Files.readString(outside)).isEqualTo("根外内容");
    }

    @Test void 中间目录链接不能导致根外文件创建() throws Exception {
        Path outside = Files.createDirectory(temp.resolve("outside"));
        try { Files.createSymbolicLink(project.resolve("alias"), outside); }
        catch (UnsupportedOperationException | FileSystemException unsupported) { Assumptions.assumeTrue(false, "不支持目录符号链接"); }
        var failure = catchThrowableOfType(() -> writer.create(root, "alias/New.java", bytes("class New {}")), LocalProjectMutationException.class);
        assertThat(failure.possibleSideEffects()).isFalse();
        assertThat(outside.resolve("New.java")).doesNotExist();
    }

    @Test void 写占用及只读属性拒绝不会破坏原正文() throws Exception {
        Path file = Files.writeString(project.resolve("a.txt"), "原文");
        String sha = files.read(root, "a.txt", 0, 1024).sha256();
        try (var occupied = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            var failure = catchThrowableOfType(() -> writer.replace(root, "a.txt", sha, bytes("覆盖")), LocalProjectMutationException.class);
            assertThat(failure.possibleSideEffects()).isFalse();
            assertThat(failure.reason()).isEqualTo(FILE_BUSY);
        }
        Files.setAttribute(file, "dos:readonly", true);
        try {
            var failure = catchThrowableOfType(() -> writer.replace(root, "a.txt", sha, bytes("覆盖")), LocalProjectMutationException.class);
            assertThat(failure.possibleSideEffects()).isFalse();
            assertThat(Files.readString(file)).isEqualTo("原文");
        } finally { Files.setAttribute(file, "dos:readonly", false); }
    }

    @Test void Flush失败报告已有副作用不能当作零执行重放() throws Exception {
        var real = Native.load("kernel32", WindowsLocalProjectFileMutator.Kernel.class);
        var api = (WindowsLocalProjectFileMutator.Kernel) Proxy.newProxyInstance(
                WindowsLocalProjectFileMutator.Kernel.class.getClassLoader(),
                new Class<?>[]{WindowsLocalProjectFileMutator.Kernel.class}, (object, method, arguments) -> {
                    if (method.getName().equals("FlushFileBuffers")) return false;
                    try { return method.invoke(real, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        var failedWriter = new WindowsLocalProjectFileMutator(files, api);
        var failure = catchThrowableOfType(() -> failedWriter.create(root, "a.txt", bytes("已写入")), LocalProjectMutationException.class);
        assertThat(failure.possibleSideEffects()).isTrue();
        assertThat(failure.getMessage()).doesNotContain(project.toString(), "已写入");
        assertThat(Files.readString(project.resolve("a.txt"))).isEqualTo("已写入");
        Files.move(project.resolve("a.txt"), project.resolve("closed.txt"));
    }

    @Test void 预先停止会在任何文件创建前失败() {
        Thread.currentThread().interrupt();
        try {
            var failure = catchThrowableOfType(() -> writer.create(root, "a.txt", bytes("合成")), LocalProjectMutationException.class);
            assertThat(failure.reason()).isEqualTo(TIMEOUT);
            assertThat(failure.possibleSideEffects()).isFalse();
            assertThat(project.resolve("a.txt")).doesNotExist();
        } finally { Thread.interrupted(); }
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test void 同句柄移动保留正文与原ACL且目标不能覆盖() throws Exception{
        Files.createDirectory(project.resolve("other"));
        Path source=Files.writeString(project.resolve("a.txt"),"合成移动");
        String sha=files.read(root,"a.txt",0,1024).sha256();
        var security=WindowsLocalProjectSecurity.capture(source);
        var nativeFailure = new java.util.concurrent.atomic.AtomicReference<String>();
        var originalKernel = com.sun.jna.Native.load("kernel32", WindowsLocalProjectFileMutator.Kernel.class);
        var observedKernel = (WindowsLocalProjectFileMutator.Kernel) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WindowsLocalProjectFileMutator.Kernel.class}, (proxy, method, arguments) -> {
                    Object result = method.invoke(originalKernel, arguments);
                    if (result instanceof Boolean success && !success) nativeFailure.set(method.getName() + ":" + com.sun.jna.Native.getLastError());
                    return result;
                });
        try { new WindowsLocalProjectFileMutator(files, observedKernel).move(root,"a.txt","other/b.txt",sha); }
        catch (java.io.IOException failure) { throw new AssertionError("移动的受控原生诊断=" + nativeFailure.get(), failure); }
        assertThat(source).doesNotExist();
        Path target=project.resolve("other/b.txt");
        assertThat(Files.readString(target)).isEqualTo("合成移动");
        var after=WindowsLocalProjectSecurity.capture(target);
        assertThat(after.fingerprint()).isEqualTo(security.fingerprint());
        assertThat(after.identity()).isEqualTo(security.identity());
        Files.writeString(project.resolve("occupied.txt"),"已有");
        assertThatThrownBy(()->writer.move(root,"other/b.txt","occupied.txt",sha)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(project.resolve("occupied.txt"))).isEqualTo("已有");
    }

    @Test void 删除核验原文和权限并可按原权限恢复() throws Exception{
        Path file=Files.writeString(project.resolve("a.txt"),"合成删除");
        String sha=files.read(root,"a.txt",0,1024).sha256();
        var security=WindowsLocalProjectSecurity.capture(file);
        writer.deleteFile(root,"a.txt",sha,security);
        assertThat(file).doesNotExist();
        writer.create(root,"a.txt",new byte[0]);
        WindowsLocalProjectSecurity.restore(file,security);
        writer.replace(root,"a.txt",files.read(root,"a.txt",0,1024).sha256(),bytes("合成删除"));
        assertThat(WindowsLocalProjectSecurity.capture(file).fingerprint()).isEqualTo(security.fingerprint());
        assertThat(Files.readString(file)).isEqualTo("合成删除");
    }
}

