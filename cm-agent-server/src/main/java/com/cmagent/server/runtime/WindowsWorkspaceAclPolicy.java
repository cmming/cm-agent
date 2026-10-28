package com.cmagent.server.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * 仅服务器登记资料使用的Windows权限策略；不复用到上传密文、Skill产物或Harness目录。
 * 身份只来自原生安全描述符SID，不能以账户显示名或HTTP参数扩展信任集合。
 */
final class WindowsWorkspaceAclPolicy {
    /** Windows操作系统账户的稳定身份。 */
    static final String SYSTEM = "S-1-5-18";
    /** 本机内置管理员组；不包含域中同名组。 */
    static final String ADMINISTRATORS = "S-1-5-32-544";
    /** 标准允许ACE。 */
    static final int ALLOW = 0;
    /** 标准拒绝ACE；实际是否允许读取仍由OS决定。 */
    static final int DENY = 1;
    /** 每次打开读取范围时建立新检查，不缓存授权结果。 */
    private final Reader reader;

    WindowsWorkspaceAclPolicy(Reader reader) { this.reader = Objects.requireNonNull(reader); }

    /**
     * 读取受信根的身份作为本轮固定基线；子文件所有者不能自行扩大信任集。
     * @param root 已通过宿主根/链接/本地文件系统校验的资料根
     * @param deadline 本轮单调时钟截止时间
     * @return 只保存本轮SID和ACL指纹的检查上下文，不持有原生资源
     */
    Access open(Path root, long deadline) throws IOException {
        budget(deadline);
        var descriptor = reader.read(root, deadline);
        if (descriptor.ownerSid() == null || !descriptor.ownerSid().startsWith("S-1-"))
            throw new WorkspaceContextFiles.Invalid("资料权限缺少可信所有者");
        var access = new Access(descriptor.ownerSid(), deadline);
        access.accept(root, descriptor);
        return access;
    }

    /** 检查与原生调用共享原读取预算；超时后结果不得送入模型。 */
    static void budget(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new Unavailable(Reason.DEADLINE);
    }

    /** 固定的基础设施诊断分类；文案不拼接主机路径、SID或底层异常。 */
    enum Reason {
        /** 进程不能取得本地对象的安全描述符。 */
        READ_DESCRIPTOR("Windows资料权限读取失败，请检查服务账户读取安全描述符的权限"),
        /** 描述符或其指针/长度不能安全解析。 */
        INVALID_DESCRIPTOR("Windows资料权限结构无法安全解析，请联系管理员核对文件系统权限"),
        /** 本轮安全元数据超过固定资源硬界。 */
        RESOURCE_LIMIT("Windows资料权限元数据超过安全预算，请简化登记目录的权限项"),
        /** 原生SID不能安全转换为稳定身份。 */
        IDENTITY_RESOLUTION("Windows资料权限身份无法核验，请联系管理员检查系统身份解析组件"),
        /** Win32分配的资源没有按约定释放。 */
        RESOURCE_RELEASE("Windows资料权限资源释放失败，请联系管理员检查原生组件"),
        /** JNA或Windows系统函数映射不可用。 */
        NATIVE_UNAVAILABLE("Windows原生权限读取组件不可用，请检查Server的JNA运行时依赖"),
        /** 同步调用返回后发现预算耗尽或当前任务已中断。 */
        DEADLINE("Windows资料权限读取超时或已中断，请检查本地存储响应后重新发起运行");
        /** 可以向当前可信请求显示的固定中文原因。 */
        private final String message;
        Reason(String message) { this.message = message; }
    }
    /** 只接受固定枚举，不把任意原生消息提升为可向前端返回的安全文本。 */
    static final class Unavailable extends IOException {
        /** 与同号日志关联的稳定分类，外层errorCode继续兼容旧协议。 */
        private final Reason reason;
        Unavailable(Reason reason) { super(reason.message); this.reason = reason; }
        Reason reason() { return reason; }
    }

    /**
     * 原生适配点便于纯策略测试；实现须自行关闭内存且不返回原始异常消息。
     * @param path 服务端验证后的本地路径
     * @param deadline 本轮截止时间
     * @return 不含路径或正文的不可变安全描述符
     */
    @FunctionalInterface interface Reader { Descriptor read(Path path, long deadline) throws IOException; }

    /** 原生DACL状态，不能把NULL和空列表混为安全。 */
    enum Dacl {
        /** 存在可枚举的DACL，内容还需校验。 */ PRESENT,
        /** NULL DACL会放开访问，必须拒绝。 */ NULL_DACL,
        /** 未提供DACL也不能证明资料私有。 */ ABSENT
    }

    /**
     * 原生ACE的最小投影，不保存账户显示名。
     * @param type 原生ACE类型，只接受标准允许/拒绝
     * @param flags 原生继承等标志，不据此扩大权限
     * @param mask 原生访问掩码
     * @param sid 稳定SID；未知类型可为空，由策略拒绝
     */
    record Ace(int type, int flags, int mask, String sid) {
        /** 不让测试失败/诊断意外展开完整主体SID。 */
        @Override public String toString() { return "WindowsWorkspaceAce[type=" + type + ", flags=" + flags + "]"; }
    }

    /**
     * 由原生读取器创建，不包含资料路径/正文或可变原生指针。
     * @param ownerSid 当前对象所有者SID
     * @param dacl DACL状态
     * @param entries 有界ACE快照
     * @param fingerprint 原安全描述符SHA256，用于本轮读前读后比较
     */
    record Descriptor(String ownerSid, Dacl dacl, List<Ace> entries, String fingerprint) {
        Descriptor { entries = List.copyOf(entries); }
        /** 日志中不展开所有者或权限列表，避免误用record默认输出。 */
        @Override public String toString() { return "WindowsWorkspaceDescriptor[受控元数据]"; }
    }

    /** 单次读取/恢复持有的检查范围；不跨Run共享，也不持有本地句柄。 */
    final class Access {
        /** 受信根所有者及两个系统SID构成的固定集合。 */
        private final Set<String> trusted;
        /** 与内容读取相同的截止时间。 */
        private final long deadline;
        /** 只保存本轮已检查路径的指纹；不扫描其他文件。 */
        private final Map<Path, String> checked = new LinkedHashMap<>();

        private Access(String owner, long deadline) {
            this.trusted = Set.copyOf(new HashSet<>(List.of(owner, SYSTEM, ADMINISTRATORS)));
            this.deadline = deadline;
        }
        void verify(Path path) throws IOException {
            budget(deadline); accept(path, reader.read(path, deadline));
        }
        private void accept(Path path, Descriptor value) throws IOException {
            budget(deadline);
            if (value.fingerprint() == null || value.fingerprint().isBlank()) throw new Unavailable(Reason.INVALID_DESCRIPTOR);
            if (!trusted.contains(value.ownerSid())) throw new WorkspaceContextFiles.Invalid("资料对象所有者不在受信范围");
            if (value.dacl() != Dacl.PRESENT || value.entries().isEmpty())
                throw new WorkspaceContextFiles.Invalid("资料权限必须包含明确的受信访问限制");
            for (var ace : value.entries()) {
                if (ace.type() != ALLOW && ace.type() != DENY || ace.sid() == null || !ace.sid().startsWith("S-1-"))
                    throw new WorkspaceContextFiles.Invalid("资料包含无法安全识别的权限项");
                // 不模拟整个Windows访问检查，也不依赖DENY抵消不可信ALLOW来放行。
                if (ace.type() == ALLOW && !trusted.contains(ace.sid()))
                    throw new WorkspaceContextFiles.Invalid("资料权限包含不可信主体的允许项");
            }
            String before = checked.putIfAbsent(path, value.fingerprint());
            if (before != null && !before.equals(value.fingerprint())) throw new WorkspaceContextFiles.Invalid("资料权限在读取期间发生变化");
        }
        /** 交付原快照前重查全部选中路径，不能只检查首次读取时的ACL。 */
        void finish() throws IOException {
            for (var path : List.copyOf(checked.keySet())) verify(path);
        }
    }
}
