package com.cmagent.server.runtime;
import com.cmagent.core.domain.SkillArtifact;
import com.cmagent.core.runtime.SkillArtifactStorage;
import com.cmagent.server.config.SkillArtifactProperties;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
/** 私有持久卷存储；二进制 AES/GCM 文件包含格式版本和随机 IV，展示名称永不参与磁盘寻址。 */
public final class FileSystemSkillArtifactStorage implements SkillArtifactStorage {
    /** 私有持久目录，禁止位于仓库、构建输出或静态资源目录。 */
    private final Path root;
    /** 独立部署密钥，仅在本对象内存持有。 */
    private final SecretKeySpec key;
    /** 文件格式标记，不包含业务信息。 */
    private static final byte[] MAGIC={'C','M','A','1'};
    /** 加密随机源为每个文件生成独立 IV。 */
    private final SecureRandom random=new SecureRandom();
    public FileSystemSkillArtifactStorage(SkillArtifactProperties properties) {
        properties.validate();
        root=Path.of(properties.getRootDirectory()).toAbsolutePath().normalize();
        key=new SecretKeySpec(Base64.getDecoder().decode(properties.getEncryptionKey()),"AES");
        try {
            if(root.getParent()==null||root.startsWith(Path.of("").toAbsolutePath().normalize())
                ||root.toString().replace('\\','/').matches("(?i).*/(target|static|resources|\\.git)(/.*)?"))
                throw new IOException("目录不可用");
            privateDirectory(root);
            if(!root.toRealPath().equals(root))throw new IOException("目录不可用");
        } catch(IOException e){throw new IllegalStateException("文件产物目录必须为独立私有持久卷");}
    }
    /** 目录由服务账户独占；调用者不能传入路径，此处只接收部署根和可信 UUID 子目录。 */
    private static void privateDirectory(Path path)throws IOException {
        for(Path current=path;current!=null;current=current.getParent())if(Files.isSymbolicLink(current))throw new IOException("目录不可用");
        if(!Files.exists(path)) {
            try{Files.createDirectories(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}
            catch(UnsupportedOperationException e){Files.createDirectories(path);}
        }
        if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("目录不可用");
        if(Files.getFileAttributeView(path,PosixFileAttributeView.class)!=null)
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
        else {
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
            if(acl!=null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
        }
    }
    private Path directory(SkillArtifact a)throws IOException {
        Path directory=root.resolve(a.tenantId().toString());privateDirectory(directory);return directory;
    }
    private Path object(SkillArtifact a)throws IOException {return directory(a).resolve(a.id()+".gcm");}
    private Cipher cipher(int mode,SkillArtifact a,byte[] iv)throws GeneralSecurityException {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,key,new GCMParameterSpec(128,iv));
        cipher.updateAAD((a.tenantId()+":"+a.id()+":"+a.sizeBytes()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return cipher;
    }
    public String write(SkillArtifact a,InputStream input)throws IOException {
        Path target=object(a),stage=target.resolveSibling(a.id()+".stage");
        try {
            byte[] iv=new byte[12];random.nextBytes(iv);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            long count=0;
            try(var raw=Files.newOutputStream(stage,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                try{Files.setPosixFilePermissions(stage,PosixFilePermissions.fromString("rw-------"));}
                catch(UnsupportedOperationException ignored){/* Windows 文件继承独占目录 ACL。 */}
                raw.write(MAGIC);raw.write(iv);
                try(var encrypted=new CipherOutputStream(raw,cipher(Cipher.ENCRYPT_MODE,a,iv))){
                    byte[] chunk=new byte[8192];int n;
                    while((n=input.read(chunk))!=-1){count+=n;if(count>a.sizeBytes())throw new IOException("文件长度不一致");digest.update(chunk,0,n);encrypted.write(chunk,0,n);}
                }
            }
            if(count!=a.sizeBytes())throw new IOException("文件长度不一致");
            String hash=HexFormat.of().formatHex(digest.digest());
            byte[] verified=read(stage,a,hash);
            try{validateFormat(a.filename(),verified);}finally{Arrays.fill(verified,(byte)0);}
            // 发布前刷入文件，避免进程重启后数据库指向尚未落盘的密文。
            try(var channel=java.nio.channels.FileChannel.open(stage,StandardOpenOption.WRITE)){channel.force(true);}
            // 原子移动失败时拒绝交付；不以非原子复制替代，保留崩溃补偿语义。
            Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);
            return hash;
        }catch(GeneralSecurityException e){throw new IOException("文件加密不可用");}
        finally{Files.deleteIfExists(stage);}
    }
    public byte[] readVerified(SkillArtifact a)throws IOException {return read(object(a),a,a.sha256());}
    private byte[] read(Path path,SkillArtifact a,String hash)throws IOException {
        if(a.sizeBytes()>4194304||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(path)!=a.sizeBytes()+32)throw new IOException("文件完整性异常");
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            byte[] all=input.readNBytes(Math.toIntExact(a.sizeBytes()+33));
            if(all.length!=a.sizeBytes()+32||!Arrays.equals(Arrays.copyOf(all,4),MAGIC))throw new IOException("文件完整性异常");
            byte[] bytes=cipher(Cipher.DECRYPT_MODE,a,Arrays.copyOfRange(all,4,16)).doFinal(all,16,all.length-16);
            if(bytes.length!=a.sizeBytes()||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(hash)){
                Arrays.fill(bytes,(byte)0);throw new IOException("文件完整性异常");
            }
            return bytes;
        }catch(GeneralSecurityException e){throw new IOException("文件完整性异常");}
    }
    /** OOXML 只做有界结构与宏拒绝校验，不执行文档、不提取到宿主目录。 */
    static void validateFormat(String name,byte[] bytes)throws IOException {
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".pdf")){
            if(bytes.length<5||!new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new FormatFailure();
        } else if(lower.endsWith(".docx")||lower.endsWith(".pptx")){
            Set<String> names=new HashSet<>();long total=0;int entries=0;
            try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
                ZipEntry entry;byte[] buffer=new byte[8192];
                while((entry=zip.getNextEntry())!=null){
                    String path=entry.isDirectory()&&entry.getName().endsWith("/")?entry.getName().substring(0,entry.getName().length()-1):entry.getName();
                    if(++entries>256||!DockerSkillSandbox.safePath(path)||!names.add(entry.getName())
                        ||entry.getName().toLowerCase(Locale.ROOT).contains("vbaproject"))throw new FormatFailure();
                    int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>16*1024*1024)throw new FormatFailure();}
                }
            }catch(ZipException e){throw new FormatFailure();}
            if(!names.contains("[Content_Types].xml")||!names.contains(lower.endsWith(".docx")?"word/document.xml":"ppt/presentation.xml"))
                throw new FormatFailure();
        }
    }
    /** 区分不可信文档拒绝与基础设施故障，不携带原始文件内容。 */
    static final class FormatFailure extends IOException {FormatFailure(){super("文件格式不合法");}}
    public void delete(SkillArtifact a)throws IOException {
        Files.deleteIfExists(object(a));Files.deleteIfExists(directory(a).resolve(a.id()+".stage"));
    }
}
