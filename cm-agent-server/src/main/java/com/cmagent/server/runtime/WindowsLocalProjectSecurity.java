package com.cmagent.server.runtime;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import com.sun.jna.win32.StdCallLibrary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Windows删除恢复的安全描述符快照；只恢复当前令牌默认owner/group一致的对象。
 * 不启用SeRestorePrivilege、不自动提权；不满足归属时在删除前拒绝，避免删除后才发现无法恢复权限。
 */
final class WindowsLocalProjectSecurity {
    private WindowsLocalProjectSecurity(){}
    /** @param path 受信路径，调用方须先完成根/链接核验 @return 只供加密备份的权限快照 */
    static Snapshot capture(Path path)throws IOException{
        Pointer sd=null,token=null;
        try{
            var descriptor=new PointerByReference();
            if(Api.ADV.GetNamedSecurityInfoW(new WString(path.toString()),1,7,null,null,null,null,descriptor)!=0)
                throw new IOException("无法取得可恢复的文件权限");
            sd=descriptor.getValue();
            if(sd==null||!Api.ADV.IsValidSecurityDescriptor(sd))throw new IOException("文件权限描述符无效");
            int length=Api.ADV.GetSecurityDescriptorLength(sd);
            if(length<20||length>131072)throw new IOException("文件权限描述符超限");
            var opened=new PointerByReference();
            if(!Api.ADV.OpenProcessToken(Api.KERNEL.GetCurrentProcess(),8,opened))throw new IOException("无法核验恢复账号");
            token=opened.getValue();
            var owner=new PointerByReference();var group=new PointerByReference();
            if(!Api.ADV.GetSecurityDescriptorOwner(sd,owner,new IntByReference())
                    ||!Api.ADV.GetSecurityDescriptorGroup(sd,group,new IntByReference()))throw new IOException("无法核验文件权限归属");
            boolean restorable=tokenSidMatches(token,4,owner.getValue())&&tokenSidMatches(token,5,group.getValue());
            return new Snapshot(Base64.getEncoder().encodeToString(sd.getByteArray(0,length)),fingerprint(sd),restorable,identity(path));
        }catch(IOException failure){throw failure;}
        catch(RuntimeException|LinkageError failure){throw new IOException("文件权限快照不可用");}
        finally{
            try{if(token!=null&&!Api.KERNEL.CloseHandle(token))throw new IOException("权限核验资源关闭失败");}
            finally{if(sd!=null&&Api.KERNEL.LocalFree(sd)!=null)throw new IOException("权限快照资源释放失败");}
        }
    }

    /** 创建恢复文件后仅恢复DACL；owner/group必须已相同，不能要求提升令牌或将其他人的文件改为自己所有。 */
    static void restore(Path path,Snapshot snapshot)throws IOException{
        if(!snapshot.restorable())throw new IOException("原文件权限归属无法安全恢复，拒绝自动删除或撤销");
        byte[] raw;
        try{raw=Base64.getDecoder().decode(snapshot.descriptor());}
        catch(IllegalArgumentException failure){throw new IOException("恢复权限快照不可核实");}
        if(raw.length<20||raw.length>131072)throw new IOException("恢复权限快照超限");
        try(var memory=new Memory(raw.length)){
            memory.write(0,raw,0,raw.length);
            if(!Api.ADV.IsValidSecurityDescriptor(memory)||!fingerprint(memory).equals(snapshot.fingerprint()))
                throw new IOException("恢复权限快照完整性失败");
            // self-relative描述符Control位于偏移2；保留原DACL继承保护状态，不自动放宽父级权限。
            int control=Short.toUnsignedInt(memory.getShort(2));
            int flags=4|((control&0x1000)!=0?0x80000000:0x20000000);
            if(!Api.ADV.SetFileSecurityW(new WString(path.toString()),flags,memory))throw new IOException("恢复原文件权限失败");
            var actual=capture(path);
            if(!actual.fingerprint().equals(snapshot.fingerprint()))throw new IOException("恢复后的权限与原状态不符");
        }catch(IOException failure){throw failure;}
        catch(RuntimeException|LinkageError failure){throw new IOException("权限恢复组件不可用");}
        finally{Arrays.fill(raw,(byte)0);}
    }

    private static boolean tokenSidMatches(Pointer token,int type,Pointer sid)throws IOException{
        var length=new IntByReference();
        Api.ADV.GetTokenInformation(token,type,null,0,length);
        if(length.getValue()<8||length.getValue()>65536||sid==null)return false;
        try(var buffer=new Memory(length.getValue())){
            if(!Api.ADV.GetTokenInformation(token,type,buffer,(int)buffer.size(),length))throw new IOException("恢复账号信息不可用");
            return Api.ADV.EqualSid(sid,buffer.getPointer(0));
        }
    }
    /** 同路径重建即使正文相同也不是原操作对象，撤销不能仅凭正文hash覆盖新对象。 */
    static String identity(Path path)throws IOException{
        Pointer handle=Api.KERNEL.CreateFileW(new WString(path.toString()),0x80,7,null,3,0x02200000,null);
        if(handle==null||Pointer.nativeValue(handle)==-1)throw new IOException("文件对象身份不可核实");
        try(var value=new Memory(24);var attributes=new Memory(8)){
            if(!Api.KERNEL.GetFileInformationByHandleEx(handle,18,value,24)
                    ||!Api.KERNEL.GetFileInformationByHandleEx(handle,9,attributes,8)||(attributes.getInt(0)&0x400)!=0)
                throw new IOException("文件对象身份不可核实");
            return HexFormat.of().formatHex(value.getByteArray(0,24));
        }finally{if(!Api.KERNEL.CloseHandle(handle))throw new IOException("文件身份资源关闭失败");}
    }
    /** 不比较自相对偏移/自动继承缓存位，比较owner/group、DACL及保护状态的真实安全语义。 */
    private static String fingerprint(Pointer sd)throws IOException{
        var owner=new PointerByReference();var group=new PointerByReference();var dacl=new PointerByReference();var present=new IntByReference();
        if(!Api.ADV.GetSecurityDescriptorOwner(sd,owner,new IntByReference())
                ||!Api.ADV.GetSecurityDescriptorGroup(sd,group,new IntByReference())
                ||!Api.ADV.GetSecurityDescriptorDacl(sd,present,dacl,new IntByReference()))throw new IOException("权限快照不可核实");
        if(owner.getValue()==null||group.getValue()==null)throw new IOException("权限归属不可核实");
        var bytes=new ByteArrayOutputStream();
        bytes.writeBytes(owner.getValue().getByteArray(0,Api.ADV.GetLengthSid(owner.getValue())));
        bytes.writeBytes(group.getValue().getByteArray(0,Api.ADV.GetLengthSid(group.getValue())));
        bytes.write(present.getValue());bytes.write((Short.toUnsignedInt(sd.getShort(2))&0x1000)!=0?1:0);
        if(dacl.getValue()!=null){
            int size=Short.toUnsignedInt(dacl.getValue().getShort(2));
            if(size<8||size>65535)throw new IOException("权限列表超限");
            bytes.writeBytes(dacl.getValue().getByteArray(0,size));
        }
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IOException("权限摘要能力不可用");}
    }

    /**
     * @param descriptor 原生自相对权限描述符的Base64，仅存受保护信封
     * @param fingerprint 原权限语义摘要
     * @param restorable 是否无需提升权限即可恢复owner/group归属
     * @param identity 原文件对象标识，撤销时不能用同内容重建物替代
     */
    record Snapshot(String descriptor,String fingerprint,boolean restorable,String identity){
        @Override public String toString(){return "Snapshot[受保护的文件权限]";}
    }
    private static final class Api{
        /** 延迟原生映射，不修改账号或令牌权限。 */
        static final Advapi ADV=Native.load("advapi32",Advapi.class);
        /** 仅句柄/任务内存资源管理。 */
        static final Kernel KERNEL=Native.load("kernel32",Kernel.class);
    }
    interface Advapi extends StdCallLibrary{
        int GetNamedSecurityInfoW(WString name,int type,int flags,PointerByReference owner,PointerByReference group,PointerByReference dacl,PointerByReference sacl,PointerByReference descriptor);
        boolean SetFileSecurityW(WString path,int flags,Pointer descriptor);
        boolean IsValidSecurityDescriptor(Pointer value);
        int GetSecurityDescriptorLength(Pointer value);
        boolean GetSecurityDescriptorOwner(Pointer value,PointerByReference owner,IntByReference defaulted);
        boolean GetSecurityDescriptorGroup(Pointer value,PointerByReference group,IntByReference defaulted);
        boolean GetSecurityDescriptorDacl(Pointer value,IntByReference present,PointerByReference dacl,IntByReference defaulted);
        int GetLengthSid(Pointer sid);
        boolean EqualSid(Pointer first,Pointer second);
        boolean OpenProcessToken(Pointer process,int access,PointerByReference token);
        boolean GetTokenInformation(Pointer token,int type,Pointer information,int length,IntByReference returned);
    }
    interface Kernel extends StdCallLibrary{
        Pointer CreateFileW(WString path,int access,int share,Pointer security,int disposition,int flags,Pointer template);
        boolean GetFileInformationByHandleEx(Pointer handle,int type,Pointer result,int size);
        Pointer GetCurrentProcess();
        boolean CloseHandle(Pointer handle);
        Pointer LocalFree(Pointer value);
    }
}

