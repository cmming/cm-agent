package com.cmagent.server.runtime;

import com.cmagent.core.runtime.LocalProjectPathPolicy;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import static com.cmagent.server.runtime.LocalProjectFileException.Reason.*;

/**
 * Windows条件文本写入原语，调用方必须先持久化受保护意图/备份并取得精确HIGH批准。
 *
 * <p>没有Spring/HTTP/模型入口，也不将一个boolean当作批准凭据。父目录链在修改期间持有不共享写/删除的
 * 真实目录句柄（移动专用分支允许父目录写共享但不共享删除），普通文件独占打开后再比对正文，原地写入保留已有ACL。创建使用CREATE_NEW，不覆盖已有目标。</p>
 *
 * <p>文件系统与数据库不原子，写入/截断/flush或关闭失败可能留下部分结果；
 * possibleSideEffects=true时必须核对，禁止自动重放或伪称全部回滚。这不是OS沙箱。</p>
 */
final class WindowsLocalProjectFileMutator {
    /** 与项目读取硬界一致，不能创建随后无法核验的超限文本。 */
    private static final int MAX_FILE = 1_048_576;
    /** 本次受信路径与根策略，不从外部传入宿主路径。 */
    private final LocalProjectFiles files;
    /** 仅测试替换故障边界；生产始终延迟加载真实Win32入口。 */
    private final Kernel override;

    WindowsLocalProjectFileMutator(LocalProjectFiles files) { this(files, null); }
    WindowsLocalProjectFileMutator(LocalProjectFiles files, Kernel override) {
        this.files = Objects.requireNonNull(files);
        this.override = override;
    }
    private Kernel kernel() { return override == null ? Api.KERNEL : override; }

    /**
     * @param root 受信本机根
     * @param relative 已批准的新文件路径，父目录必须已存在
     * @param content 已批准的完整UTF-8正文
     * @return 已写入并核对的内容摘要
     */
    Result create(LocalProjectDirectoryTickets.Root root, String relative, byte[] content) throws IOException {
        return write(root, relative, null, content, true);
    }

    /**
     * @param root 受信本机根
     * @param relative 已批准的现有文件
     * @param expectedSha256 与原批准提案相同的旧正文摘要
     * @param content 精确批准的新正文
     * @return 旧/新版本摘要，不包含正文或绝对路径
     */
    Result replace(LocalProjectDirectoryTickets.Root root, String relative, String expectedSha256, byte[] content) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) throw new LocalProjectMutationException(INVALID_PATH, false);
        return write(root, relative, expectedSha256, content, false);
    }

    /** 精确变更提案还冻结原对象及ACL；独占句柄内再次核验，不能只凭同内容重建物通过。 */
    Result replace(LocalProjectDirectoryTickets.Root root,String relative,String expectedSha256,byte[] content,WindowsLocalProjectSecurity.Snapshot expected)throws IOException {
        return write(root,relative,expectedSha256,content,false,expected);
    }

    /** 同卷根内移动原对象，目标不得存在；不复制内容或重建ACL，批准前必须已有精确原摘要。 */
    Result move(LocalProjectDirectoryTickets.Root root,String relative,String targetRelative,String expectedSha256) throws IOException{
        return move(root,relative,targetRelative,expectedSha256,null);
    }

    /** 原对象身份和权限参与批准前置条件；目录内移动仍不得覆盖目标。 */
    Result move(LocalProjectDirectoryTickets.Root root,String relative,String targetRelative,String expectedSha256,WindowsLocalProjectSecurity.Snapshot expected) throws IOException{
        boolean changed=false;Pointer handle=null;byte[] current=null;
        long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        try{
            if(!Platform.isWindows()||Native.POINTER_SIZE!=8)throw new LocalProjectFileException(UNAVAILABLE);
            LocalProjectPathPolicy.requireRelative(relative,false);LocalProjectPathPolicy.requireRelative(targetRelative,false);
            if(!LocalProjectPathPolicy.textCandidate(targetRelative)||!files.absent(root,targetRelative))throw new LocalProjectFileException(FILE_CHANGED);
            current=files.mutationSnapshot(root,relative);
            if(!sha(current).equals(expectedSha256))throw new LocalProjectFileException(FILE_CHANGED);
            Arrays.fill(current,(byte)0);current=null;
            Path source=root.directory().resolve(relative).normalize(),target=root.directory().resolve(targetRelative).normalize();
            try(var sourceLease=WindowsLocalProjectDirectoryLease.acquireForRename(root,source.getParent(),deadline);
                    var targetLease=WindowsLocalProjectDirectoryLease.acquireForRename(root,target.getParent(),deadline);var buffer=new Memory(4096)){
                sourceLease.verify();targetLease.verify();
                handle=kernel().CreateFileW(new WString(source.toString()),0x80010000,0,null,3,0x00200000,null);
                if(handle==null||Pointer.nativeValue(handle)==-1){handle=null;throw new LocalProjectFileException(FILE_BUSY);}
                checkFile(handle,source);
                current=read(handle,buffer,deadline);
                if(!sha(current).equals(expectedSha256))throw new LocalProjectFileException(FILE_CHANGED);
                verifySecurity(source,expected);
                byte[] name=(target.toString()+"\0").getBytes(StandardCharsets.UTF_16LE);
                try(var rename=new Memory(20L+name.length)){
                    rename.clear();rename.setInt(16,name.length-2);rename.write(20,name,0,name.length);
                    // ReplaceIfExists保持FALSE，RootDirectory为空且使用已锁父链内绝对目标。
                    changed=true;
                    if(!kernel().SetFileInformationByHandle(handle,3,rename,(int)rename.size()))throw new LocalProjectFileException(FILE_CHANGED);
                }finally{Arrays.fill(name,(byte)0);}
                checkFile(handle,target);sourceLease.verify();targetLease.verify();
                var permissions=WindowsLocalProjectSecurity.capture(target);
                if(expected!=null&&(!expected.identity().equals(permissions.identity())||!expected.fingerprint().equals(permissions.fingerprint())))
                    throw new LocalProjectFileException(FILE_CHANGED);
                return new Result(expectedSha256,expectedSha256,current.length,permissions.identity(),permissions.fingerprint());
            }
        }catch(LocalProjectFileException failure){throw new LocalProjectMutationException(failure.reason(),changed);}
        catch(IOException|RuntimeException|LinkageError failure){throw new LocalProjectMutationException(UNAVAILABLE,changed);}
        finally{
            if(current!=null)Arrays.fill(current,(byte)0);
            if(handle!=null&&!kernel().CloseHandle(handle))throw new LocalProjectMutationException(UNAVAILABLE,changed);
        }
    }

    /** 单层目录创建；父链必须已存在且被守卫，绝不递归创建或替换已有目录。 */
    WindowsLocalProjectSecurity.Snapshot createDirectory(LocalProjectDirectoryTickets.Root root,String relative)throws IOException{
        boolean changed=false;long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        try{
            if(!Platform.isWindows()||Native.POINTER_SIZE!=8)throw new LocalProjectFileException(UNAVAILABLE);
            if(!files.absent(root,relative))throw new LocalProjectFileException(FILE_CHANGED);
            Path target=root.directory().resolve(relative).normalize();
            try(var lease=WindowsLocalProjectDirectoryLease.acquire(root,target.getParent(),deadline)){
                lease.verify();
                if(!kernel().CreateDirectoryW(new WString(target.toString()),null))throw new LocalProjectFileException(FILE_CHANGED);
                changed=true;lease.verify();
                return WindowsLocalProjectSecurity.capture(target);
            }
        }catch(LocalProjectFileException failure){throw new LocalProjectMutationException(failure.reason(),changed);}
        catch(IOException|RuntimeException|LinkageError failure){throw new LocalProjectMutationException(UNAVAILABLE,changed);}
    }

    /** 在同一独占句柄核验原正文和权限后标记删除；未获可恢复权限快照时拒绝，不递归删除。 */
    void deleteFile(LocalProjectDirectoryTickets.Root root,String relative,String expectedSha256,
            WindowsLocalProjectSecurity.Snapshot security)throws IOException{
        boolean changed=false;Pointer handle=null;byte[] current=null;long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        try{
            if(!Platform.isWindows()||Native.POINTER_SIZE!=8)throw new LocalProjectFileException(UNAVAILABLE);
            if(security==null||!security.restorable())throw new LocalProjectFileException(ACCESS_DENIED);
            current=files.mutationSnapshot(root,relative);
            if(!sha(current).equals(expectedSha256))throw new LocalProjectFileException(FILE_CHANGED);
            Arrays.fill(current,(byte)0);current=null;
            Path path=root.directory().resolve(relative).normalize();
            try(var lease=WindowsLocalProjectDirectoryLease.acquire(root,path.getParent(),deadline);var buffer=new Memory(4096);var disposition=new Memory(4)){
                lease.verify();
                handle=kernel().CreateFileW(new WString(path.toString()),0x80010000,0,null,3,0x00200000,null);
                if(handle==null||Pointer.nativeValue(handle)==-1){handle=null;throw new LocalProjectFileException(FILE_BUSY);}
                checkFile(handle,path);current=read(handle,buffer,deadline);
                var actualSecurity=WindowsLocalProjectSecurity.capture(path);
                if(!sha(current).equals(expectedSha256)||!actualSecurity.fingerprint().equals(security.fingerprint())
                        ||!actualSecurity.identity().equals(security.identity()))
                    throw new LocalProjectFileException(FILE_CHANGED);
                disposition.clear();disposition.setInt(0,1);
                changed=true;
                if(!kernel().SetFileInformationByHandle(handle,4,disposition,4))throw new LocalProjectFileException(UNAVAILABLE);
                lease.verify();
            }
        }catch(LocalProjectFileException failure){throw new LocalProjectMutationException(failure.reason(),changed);}
        catch(IOException|RuntimeException|LinkageError failure){throw new LocalProjectMutationException(UNAVAILABLE,changed);}
        finally{
            if(current!=null)Arrays.fill(current,(byte)0);
            if(handle!=null&&!kernel().CloseHandle(handle))throw new LocalProjectMutationException(UNAVAILABLE,changed);
        }
    }

    /** 仅删除仍是原对象且为空的目录；系统拒绝非空目录，不使用递归删除。 */
    void deleteEmptyDirectory(LocalProjectDirectoryTickets.Root root,String relative,String expectedVersion,
            WindowsLocalProjectSecurity.Snapshot security)throws IOException{
        boolean changed=false;Pointer handle=null;long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        try{
            if(!Platform.isWindows()||Native.POINTER_SIZE!=8)throw new LocalProjectFileException(UNAVAILABLE);
            if(security==null||!security.restorable())throw new LocalProjectFileException(ACCESS_DENIED);
            if(!files.emptyDirectoryVersion(root,relative).equals(expectedVersion))throw new LocalProjectFileException(FILE_CHANGED);
            Path path=root.directory().resolve(relative).normalize();
            try(var lease=WindowsLocalProjectDirectoryLease.acquire(root,path.getParent(),deadline);var tag=new Memory(8);var id=new Memory(24);var disposition=new Memory(4)){
                handle=kernel().CreateFileW(new WString(path.toString()),0x80010000,0,null,3,0x02200000,null);
                if(handle==null||Pointer.nativeValue(handle)==-1){handle=null;throw new LocalProjectFileException(FILE_BUSY);}
                if(!kernel().GetFileInformationByHandleEx(handle,9,tag,8)||!kernel().GetFileInformationByHandleEx(handle,18,id,24)
                        ||(tag.getInt(0)&0x10)==0||(tag.getInt(0)&0x400)!=0)throw new LocalProjectFileException(UNSAFE_FILE);
                String identity=HexFormat.of().formatHex(id.getByteArray(0,24));
                if(!sha(identity.getBytes(StandardCharsets.UTF_8)).equals(expectedVersion))throw new LocalProjectFileException(FILE_CHANGED);
                var actual=WindowsLocalProjectSecurity.capture(path);
                if(!actual.identity().equals(security.identity())||!actual.fingerprint().equals(security.fingerprint()))
                    throw new LocalProjectFileException(FILE_CHANGED);
                lease.verify();disposition.clear();disposition.setInt(0,1);changed=true;
                if(!kernel().SetFileInformationByHandle(handle,4,disposition,4))throw new LocalProjectFileException(FILE_CHANGED);
            }
        }catch(LocalProjectFileException failure){throw new LocalProjectMutationException(failure.reason(),changed);}
        catch(IOException|RuntimeException|LinkageError failure){throw new LocalProjectMutationException(UNAVAILABLE,changed);}
        finally{if(handle!=null&&!kernel().CloseHandle(handle))throw new LocalProjectMutationException(UNAVAILABLE,changed);}
    }

    private Result write(LocalProjectDirectoryTickets.Root root, String relative, String expected, byte[] content, boolean create) throws IOException {
        return write(root,relative,expected,content,create,null);
    }

    private Result write(LocalProjectDirectoryTickets.Root root, String relative, String expected, byte[] content, boolean create,WindowsLocalProjectSecurity.Snapshot expectedSecurity) throws IOException {
        boolean changed = false;
        Pointer handle = null;
        byte[] next = content == null ? null : content.clone();
        byte[] current = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try {
            if (!Platform.isWindows() || Native.POINTER_SIZE != 8) throw new LocalProjectFileException(UNAVAILABLE);
            validate(next);
            LocalProjectPathPolicy.requireRelative(relative, false);
            if (!LocalProjectPathPolicy.textCandidate(relative)) throw new LocalProjectFileException(EXCLUDED);
            if (create) {
                if (!files.absent(root, relative)) throw new LocalProjectFileException(FILE_CHANGED);
            } else {
                current = files.mutationSnapshot(root, relative);
                if (!sha(current).equals(expected)) throw new LocalProjectFileException(FILE_CHANGED);
                Arrays.fill(current, (byte) 0); current = null;
            }
            Path target = root.directory().resolve(relative).normalize();
            try (var lease = WindowsLocalProjectDirectoryLease.acquire(root, target.getParent(), deadline);
                    var buffer = new Memory(4096)) {
                lease.verify();
                handle = kernel().CreateFileW(new WString(target.toString()), 0xc0000000, 0, null,
                        create ? 1 : 3, 0x00200000, null);
                if (handle == null || Pointer.nativeValue(handle) == -1) {
                    handle = null;
                    int error = Native.getLastError();
                    throw new LocalProjectFileException(error == 80 || error == 183 ? FILE_CHANGED
                            : error == 32 ? FILE_BUSY : error == 5 ? ACCESS_DENIED : UNAVAILABLE);
                }
                if (create) changed = true;
                checkFile(handle, target);
                if (!create) {
                    current = read(handle, buffer, deadline);
                    if (!sha(current).equals(expected)) throw new LocalProjectFileException(FILE_CHANGED);
                    verifySecurity(target,expectedSecurity);
                }
                String before = create ? "" : sha(current);
                if (!kernel().SetFilePointerEx(handle, 0, null, 0)) throw new LocalProjectFileException(UNAVAILABLE);
                int offset = 0;
                while (offset < next.length) {
                    WindowsLocalProjectTextReader.budget(deadline);
                    int length = Math.min(4096, next.length - offset);
                    buffer.write(0, next, offset, length);
                    var written = new IntByReference();
                    changed = true;
                    if (!kernel().WriteFile(handle, buffer, length, written, null)
                            || written.getValue() < 1 || written.getValue() > length)
                        throw new LocalProjectFileException(UNAVAILABLE);
                    offset += written.getValue();
                }
                changed = true;
                if (!kernel().SetEndOfFile(handle) || !kernel().FlushFileBuffers(handle)) throw new LocalProjectFileException(UNAVAILABLE);
                checkFile(handle, target);
                lease.verify();
                if (!kernel().SetFilePointerEx(handle, 0, null, 0)) throw new LocalProjectFileException(UNAVAILABLE);
                byte[] actual = read(handle, buffer, deadline);
                try {
                    if (!MessageDigest.isEqual(actual, next)) throw new LocalProjectFileException(FILE_CHANGED);
                } finally { Arrays.fill(actual, (byte) 0); }
                buffer.clear();
                var permissions=WindowsLocalProjectSecurity.capture(target);
                if(expectedSecurity!=null&&(!expectedSecurity.identity().equals(permissions.identity())||!expectedSecurity.fingerprint().equals(permissions.fingerprint())))
                    throw new LocalProjectFileException(FILE_CHANGED);
                return new Result(before, sha(next), next.length,permissions.identity(),permissions.fingerprint());
            }
        } catch (LocalProjectFileException failure) {
            throw new LocalProjectMutationException(failure.reason(), changed);
        } catch (IOException failure) {
            throw new LocalProjectMutationException(failure instanceof java.nio.file.NoSuchFileException ? NOT_FOUND
                    : failure instanceof java.nio.file.AccessDeniedException ? ACCESS_DENIED : UNAVAILABLE, changed);
        } catch (IllegalArgumentException failure) {
            throw new LocalProjectMutationException(INVALID_PATH, changed);
        } catch (RuntimeException | LinkageError failure) {
            throw new LocalProjectMutationException(UNAVAILABLE, changed);
        } finally {
            if (next != null) Arrays.fill(next, (byte) 0);
            if (current != null) Arrays.fill(current, (byte) 0);
            if (handle != null) {
                try {
                    if (!kernel().CloseHandle(handle)) throw new LocalProjectMutationException(UNAVAILABLE, changed);
                } catch (RuntimeException | LinkageError failure) { throw new LocalProjectMutationException(UNAVAILABLE, changed); }
            }
        }
    }

    private static void validate(byte[] bytes) throws LocalProjectFileException {
        if (bytes == null || bytes.length > MAX_FILE) throw new LocalProjectFileException(LIMIT_EXCEEDED);
        try {
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            if (text.codePoints().anyMatch(value -> Character.isISOControl(value) && value != 9 && value != 10 && value != 13))
                throw new LocalProjectFileException(INVALID_TEXT);
        } catch (java.nio.charset.CharacterCodingException failure) { throw new LocalProjectFileException(INVALID_TEXT); }
    }

    /** 独占打开后检查类型/链接数/最终路径，不能把Java预先读到的对象直接当作写入对象。 */
    private void checkFile(Pointer handle, Path expected) throws LocalProjectFileException {
        try (var attributes = new Memory(8); var standard = new Memory(24)) {
            if (kernel().GetFileType(handle) != 1 || !kernel().GetFileInformationByHandleEx(handle, 9, attributes, 8)
                    || !kernel().GetFileInformationByHandleEx(handle, 1, standard, 24))
                throw new LocalProjectFileException(UNAVAILABLE);
            if ((attributes.getInt(0) & (0x10 | 0x400)) != 0 || standard.getInt(16) != 1
                    || standard.getByte(20) != 0 || standard.getByte(21) != 0) throw new LocalProjectFileException(UNSAFE_FILE);
            if (standard.getLong(8) < 0 || standard.getLong(8) > MAX_FILE) throw new LocalProjectFileException(LIMIT_EXCEEDED);
            char[] value = new char[32768];
            int length = kernel().GetFinalPathNameByHandleW(handle, value, value.length, 0);
            if (length < 1 || length >= value.length) throw new LocalProjectFileException(UNAVAILABLE);
            String actual = new String(value, 0, length);
            if (actual.startsWith("\\\\?\\")) actual = actual.substring(4);
            if (!Path.of(actual).toAbsolutePath().normalize().equals(expected)) throw new LocalProjectFileException(FILE_CHANGED);
        }
    }

    private byte[] read(Pointer handle, Memory buffer, long deadline) throws LocalProjectFileException {
        var output = new ByteArrayOutputStream();
        var count = new IntByReference();
        while (true) {
            WindowsLocalProjectTextReader.budget(deadline);
            if (!kernel().ReadFile(handle, buffer, 4096, count, null)) throw new LocalProjectFileException(UNAVAILABLE);
            int length = count.getValue();
            if (length == 0) break;
            if (length < 0 || length > 4096 || length > MAX_FILE - output.size()) throw new LocalProjectFileException(LIMIT_EXCEEDED);
            output.write(buffer.getByteArray(0, length), 0, length);
        }
        return output.toByteArray();
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("变更摘要能力不可用"); }
    }

    /** 在已持有的独占叶句柄期间核对对象/权限；拒绝同内容但不同原生对象或ACL漂移。 */
    private static void verifySecurity(Path path,WindowsLocalProjectSecurity.Snapshot expected)throws IOException {
        if(expected==null)return;
        var actual=WindowsLocalProjectSecurity.capture(path);
        if(!actual.identity().equals(expected.identity())||!actual.fingerprint().equals(expected.fingerprint()))throw new LocalProjectFileException(FILE_CHANGED);
    }

    /**
     * @param beforeSha256 旧正文摘要，创建时为空
     * @param afterSha256 写入后核实的新正文摘要
     * @param sizeBytes 已核实的新字节数
     * @param fileIdentity 原生写入句柄仍持有时捕获的对象标识
     * @param securityFingerprint 已写对象的权限摘要，用于条件撤销拒绝权限漂移
     */
    record Result(String beforeSha256, String afterSha256, int sizeBytes,String fileIdentity,String securityFingerprint) {}

    private static final class Api {
        /** 仅Windows路径且完成参数校验后加载，不提供任意进程/设备权限。 */
        private static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
    }
    interface Kernel extends StdCallLibrary {
        boolean SetFileInformationByHandle(Pointer file,int type,Pointer information,int bytes);
        boolean CreateDirectoryW(WString path,Pointer security);
        Pointer CreateFileW(WString path, int access, int share, Pointer security, int disposition, int flags, Pointer template);
        int GetFileType(Pointer file);
        boolean GetFileInformationByHandleEx(Pointer file, int type, Pointer result, int size);
        int GetFinalPathNameByHandleW(Pointer file, char[] result, int count, int flags);
        boolean SetFilePointerEx(Pointer file, long offset, Pointer result, int origin);
        boolean ReadFile(Pointer file, Pointer result, int bytes, IntByReference count, Pointer overlapped);
        boolean WriteFile(Pointer file, Pointer source, int bytes, IntByReference count, Pointer overlapped);
        boolean SetEndOfFile(Pointer file);
        boolean FlushFileBuffers(Pointer file);
        boolean CloseHandle(Pointer handle);
    }
}

