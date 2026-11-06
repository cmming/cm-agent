package com.cmagent.localclient;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Windows 当前登录用户的通用凭据存储；尚未接入客户端身份流程。
 *
 * <p>只操作带固定前缀和随机 UUID 的本客户端条目，秘密通过原生内存传递，不进入命令行、
 * 环境变量或日志。Windows 将凭据绑定到当前登录用户；这不防止同一用户权限下的其他程序访问。</p>
 */
final class WindowsCredentialStore implements SystemCredentialStore {
    private static final String PREFIX = "CM-Agent/LocalDevice/";
    private static final int GENERIC = 1;
    private static final int LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;
    /** Microsoft CREDENTIALW 文档规定通用凭据数据上限为 5×512 字节。 */
    private static final int MAX_SECRET_BYTES = 2560;
    private final CredentialApi api;

    /** 仅在 Windows 上加载系统库，其他平台应选择各自实现或口令回退。 */
    WindowsCredentialStore() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new IllegalStateException("Windows 凭据存储仅支持 Windows");
        }
        api = Native.load("Advapi32", CredentialApi.class);
    }

    @Override
    public String create(byte[] secret) throws IOException {
        Objects.requireNonNull(secret, "设备秘密不能为空");
        if (secret.length < 1 || secret.length > MAX_SECRET_BYTES) {
            throw new IllegalArgumentException("设备秘密长度无效");
        }
        String reference = PREFIX + UUID.randomUUID();
        // 随机引用不复用；调用方不提供任意系统凭据名称，避免误覆盖其他应用的条目。
        try (Memory bytes = new Memory(secret.length)) {
            try {
                bytes.write(0, secret, 0, secret.length);
                var credential = new NativeCredential();
                credential.Type = GENERIC;
                credential.TargetName = new WString(reference);
                credential.CredentialBlobSize = secret.length;
                credential.CredentialBlob = bytes;
                credential.Persist = LOCAL_MACHINE;
                credential.UserName = new WString("CM Agent");
                credential.write();
                if (!api.CredWriteW(credential, 0)) throw failure("保存", Native.getLastError());
                return reference;
            } finally {
                bytes.clear();
            }
        }
    }

    @Override
    public Optional<byte[]> read(String reference) throws IOException {
        validateReference(reference);
        PointerByReference pointer = new PointerByReference();
        if (!api.CredReadW(new WString(reference), GENERIC, 0, pointer)) {
            int error = Native.getLastError();
            if (error == ERROR_NOT_FOUND) return Optional.empty();
            throw failure("读取", error);
        }
        Pointer nativeValue = pointer.getValue();
        if (nativeValue == null) throw new IOException("系统凭据存储返回空结果");
        try {
            var credential = new NativeCredential(nativeValue);
            if (credential.CredentialBlob == null || credential.CredentialBlobSize < 1
                    || credential.CredentialBlobSize > MAX_SECRET_BYTES) {
                throw new IOException("系统凭据内容无效");
            }
            return Optional.of(credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize));
        } finally {
            // CredReadW 的返回缓冲区由系统分配，必须使用 CredFree 释放，不能由 JNA 自行释放。
            api.CredFree(nativeValue);
        }
    }

    @Override
    public boolean delete(String reference) throws IOException {
        validateReference(reference);
        if (api.CredDeleteW(new WString(reference), GENERIC, 0)) return true;
        int error = Native.getLastError();
        if (error == ERROR_NOT_FOUND) return false;
        throw failure("删除", error);
    }

    private static void validateReference(String reference) {
        if (reference == null || !reference.startsWith(PREFIX)) {
            throw new IllegalArgumentException("设备凭据引用无效");
        }
        try {
            String suffix = reference.substring(PREFIX.length());
            if (!UUID.fromString(suffix).toString().equals(suffix)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("设备凭据引用无效");
        }
    }

    private static IOException failure(String operation, int error) {
        return new IOException("系统凭据" + operation + "失败，系统错误码：" + error);
    }

    /** 仅声明使用的 Windows 原生接口；成功读取的内存必须交回 CredFree。 */
    interface CredentialApi extends StdCallLibrary {
        boolean CredWriteW(NativeCredential credential, int flags);
        boolean CredReadW(WString target, int type, int flags, PointerByReference credential);
        boolean CredDeleteW(WString target, int type, int flags);
        void CredFree(Pointer credential);
    }

    /**
     * Windows CREDENTIALW 的布局；FILETIME 使用两个 DWORD，确保与原生字段顺序一致。
     * Microsoft 文档：https://learn.microsoft.com/windows/win32/api/wincred/ns-wincred-credentialw
     */
    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWrittenLow", "LastWrittenHigh",
            "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName"})
    public static class NativeCredential extends Structure {
        /** 原生标志。 */ public int Flags;
        /** 凭据类别。 */ public int Type;
        /** 固定前缀与随机引用。 */ public WString TargetName;
        /** 可选说明，本实现不填写。 */ public Pointer Comment;
        /** 原生 FILETIME 低位。 */ public int LastWrittenLow;
        /** 原生 FILETIME 高位。 */ public int LastWrittenHigh;
        /** 秘密数据字节数。 */ public int CredentialBlobSize;
        /** 秘密数据的原生内存指针。 */ public Pointer CredentialBlob;
        /** 当前用户在本机跨登录会话持久化方式。 */ public int Persist;
        /** 扩展属性数量，本实现为零。 */ public int AttributeCount;
        /** 扩展属性，本实现不填写。 */ public Pointer Attributes;
        /** 目标别名，本实现不填写。 */ public Pointer TargetAlias;
        /** 标识本客户端的显示用户名。 */ public WString UserName;

        NativeCredential() { }
        NativeCredential(Pointer memory) { super(memory); read(); }
    }
}
