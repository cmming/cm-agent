package com.cmagent.server.runtime;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.cmagent.server.runtime.WindowsWorkspaceAclPolicy.*;

/**
 * 使用JNA5.17.0映射最小只读Win32接口，不运行Shell、不按显示名查询身份。
 * GetFileSecurityW写入调用者内存；SID字符串由LocalFree释放，非Windows不触发延迟加载。
 */
final class WindowsSecurityDescriptorReader implements Reader {
    /** 只请求所有者和DACL，不读取SACL，也不请求修改/提升令牌权限。 */
    private static final int SECURITY = 0x00000001 | 0x00000004;
    /** 描述符包含最大64KiB ACL及所有者等头部，分配保持硬界。 */
    private static final int MAX_DESCRIPTOR = 131072;
    /** 权限项数量硬界，正常资料目录远低于此值。 */
    private static final int MAX_ACES = 4096;
    /** Win32首轮查询缓冲区长度的预期错误。 */
    private static final int INSUFFICIENT_BUFFER = 122;
    /** 测试可替换最小原生调用；生产为null并在首次Windows读取时延迟初始化。 */
    private final Advapi overrideAdvapi;
    /** 测试验证LocalFree失败分类；生产不替换系统释放函数。 */
    private final Kernel overrideKernel;

    WindowsSecurityDescriptorReader() { this(null, null); }
    WindowsSecurityDescriptorReader(Advapi advapi, Kernel kernel) {
        this.overrideAdvapi = advapi; this.overrideKernel = kernel;
    }
    private Advapi advapi() { return overrideAdvapi == null ? NativeApi.ADVAPI : overrideAdvapi; }
    private Kernel kernel() { return overrideKernel == null ? NativeApi.KERNEL : overrideKernel; }

    @Override public Descriptor read(Path path, long deadline) throws IOException {
        budget(deadline);
        try {
            var needed = new IntByReference();
            boolean first = advapi().GetFileSecurityW(new WString(path.toString()), SECURITY, null, 0, needed);
            if (first || Native.getLastError() != INSUFFICIENT_BUFFER) throw readFailure(path);
            int size = needed.getValue();
            if (size < 20 || size > MAX_DESCRIPTOR) throw new Unavailable(Reason.RESOURCE_LIMIT);
            // Memory实现Closeable；仅复制不可变投影，任何返回/异常路径均关闭本次缓冲区。
            try (var memory = new Memory(size)) {
                memory.clear(); budget(deadline);
                if (!advapi().GetFileSecurityW(new WString(path.toString()), SECURITY, memory, size, needed)) throw readFailure(path);
                if (needed.getValue() < 20 || needed.getValue() > size || !advapi().IsValidSecurityDescriptor(memory))
                    throw new Unavailable(Reason.INVALID_DESCRIPTOR);
                var owner = new PointerByReference();
                if (!advapi().GetSecurityDescriptorOwner(memory, owner, new IntByReference())) throw new Unavailable(Reason.INVALID_DESCRIPTOR);
                String ownerSid = sid(memory, owner.getValue());
                var present = new IntByReference();var dacl = new PointerByReference();
                if (!advapi().GetSecurityDescriptorDacl(memory, present, dacl, new IntByReference())) throw new Unavailable(Reason.INVALID_DESCRIPTOR);
                var entries = new ArrayList<Ace>();
                Dacl state = present.getValue() == 0 ? Dacl.ABSENT : dacl.getValue() == null ? Dacl.NULL_DACL : Dacl.PRESENT;
                if (state == Dacl.PRESENT) {
                    inside(memory, dacl.getValue(), 8);
                    if (!advapi().IsValidAcl(dacl.getValue())) throw new Unavailable(Reason.INVALID_DESCRIPTOR);
                    // ACL_SIZE_INFORMATION由GetAclInformation填写，不自行推断ACE个数或链表边界。
                    try (var info = new Memory(12)) {
                        info.clear();
                        if (!advapi().GetAclInformation(dacl.getValue(), info, 12, 2)) throw new Unavailable(Reason.INVALID_DESCRIPTOR);
                        int count = info.getInt(0);
                        if (count < 0 || count > MAX_ACES) throw new Unavailable(Reason.RESOURCE_LIMIT);
                        for (int i = 0; i < count; i++) {
                            budget(deadline);var ace = new PointerByReference();
                            if (!advapi().GetAce(dacl.getValue(), i, ace)) throw new Unavailable(Reason.INVALID_DESCRIPTOR);
                            Pointer pointer = ace.getValue();inside(memory, pointer, 4);
                            int type = Byte.toUnsignedInt(pointer.getByte(0)), flags = Byte.toUnsignedInt(pointer.getByte(1));
                            int length = Short.toUnsignedInt(pointer.getShort(2));inside(memory, pointer, length);
                            if (type != ALLOW && type != DENY) throw new WorkspaceContextFiles.Invalid("资料包含不支持的Windows权限项");
                            if (length < 16) throw new Unavailable(Reason.INVALID_DESCRIPTOR);
                            if (16 + 4 * Byte.toUnsignedInt(pointer.getByte(9)) > length) throw new Unavailable(Reason.IDENTITY_RESOLUTION);
                            entries.add(new Ace(type, flags, pointer.getInt(4), sid(memory, pointer.share(8))));
                        }
                    }
                }
                budget(deadline);
                String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(memory.getByteArray(0, needed.getValue())));
                return new Descriptor(ownerSid, state, entries, fingerprint);
            }
        } catch (IOException failure) { throw failure; }
        catch (RuntimeException | LinkageError | java.security.GeneralSecurityException failure) {
            // 原生异常可能包含主机路径或实现细节；最终运行边界只收到固定诊断，不附加原始消息。
            throw new Unavailable(Reason.NATIVE_UNAVAILABLE);
        }
    }

    private String sid(Memory buffer, Pointer pointer) throws IOException {
        inside(buffer, pointer, 8);
        int count = Byte.toUnsignedInt(pointer.getByte(1));
        if (count > 15) throw new Unavailable(Reason.IDENTITY_RESOLUTION);
        inside(buffer, pointer, 8 + 4L * count);
        if (!advapi().IsValidSid(pointer)) throw new Unavailable(Reason.IDENTITY_RESOLUTION);
        var value = new PointerByReference();
        boolean converted = advapi().ConvertSidToStringSidW(pointer, value);
        Pointer text = value.getValue();
        try {
            if (!converted || text == null) throw new Unavailable(Reason.IDENTITY_RESOLUTION);
            return text.getWideString(0);
        }
        finally {
            // ConvertSidToStringSidW使用LocalAlloc，不能用JNA Memory.close或Java GC替代LocalFree。
            if (text != null && kernel().LocalFree(text) != null) throw new Unavailable(Reason.RESOURCE_RELEASE);
        }
    }
    private static void inside(Memory memory, Pointer pointer, long length) throws IOException {
        if (pointer == null || length < 0) throw new Unavailable(Reason.INVALID_DESCRIPTOR);
        long offset = Pointer.nativeValue(pointer) - Pointer.nativeValue(memory);
        if (offset < 0 || offset > memory.size() || length > memory.size() - offset) throw new Unavailable(Reason.INVALID_DESCRIPTOR);
    }
    private static IOException readFailure(Path path) {
        int code = Native.getLastError();
        if (code == 2 || code == 3) return new NoSuchFileException(path.toString());
        return new Unavailable(Reason.READ_DESCRIPTOR);
    }
    /** 只在Windows实际读取时初始化；函数表由JNA按进程生命周期管理，不缓存文件句柄或描述符。Linux不加载系统DLL。 */
    private static final class NativeApi {
        /** 固定Windows系统DLL，不接收用户或配置中的库名。 */
        static final Advapi ADVAPI = Native.load("Advapi32", Advapi.class);
        /** 仅释放Win32分配的SID字符串，不提供其他宿主能力。 */
        static final Kernel KERNEL = Native.load("Kernel32", Kernel.class);
    }
    /** Win32 BOOL为32位，WString保证路径使用Unicode；函数名显式使用W版本。 */
    interface Advapi extends StdCallLibrary {
        boolean GetFileSecurityW(WString path, int information, Pointer buffer, int length, IntByReference needed);
        boolean IsValidSecurityDescriptor(Pointer descriptor);
        boolean GetSecurityDescriptorOwner(Pointer descriptor, PointerByReference owner, IntByReference defaulted);
        boolean GetSecurityDescriptorDacl(Pointer descriptor, IntByReference present, PointerByReference dacl, IntByReference defaulted);
        boolean IsValidAcl(Pointer acl);
        boolean GetAclInformation(Pointer acl, Pointer information, int length, int informationClass);
        boolean GetAce(Pointer acl, int index, PointerByReference ace);
        boolean IsValidSid(Pointer sid);
        boolean ConvertSidToStringSidW(Pointer sid, PointerByReference value);
    }
    /** LocalFree返回NULL表示成功，非NULL表示释放失败。 */
    interface Kernel extends StdCallLibrary { Pointer LocalFree(Pointer value); }
}
