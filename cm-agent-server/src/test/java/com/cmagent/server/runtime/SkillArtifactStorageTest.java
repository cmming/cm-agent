package com.cmagent.server.runtime;
import com.cmagent.core.domain.*;
import com.cmagent.server.config.SkillArtifactProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
class SkillArtifactStorageTest {
    @TempDir Path root;
    static SkillArtifact artifact(String name,int size){
        return new SkillArtifact(UUID.randomUUID(),UUID.randomUUID(),"owner",UUID.randomUUID(),UUID.randomUUID(),RunKind.NORMAL,UUID.randomUUID(),UUID.randomUUID(),"call",name,"application/octet-stream",size,"",SkillArtifactStatus.STAGING,Instant.now(),Instant.now().plusSeconds(60),null,null);
    }
    private SkillArtifactProperties policy(){
        var p=new SkillArtifactProperties();p.setEnabled(true);p.setRootDirectory(root.toString());p.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));return p;
    }
    static byte[] docx()throws IOException{
        var out=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(out)){
            for(String name:List.of("[Content_Types].xml","word/","word/document.xml")){
                zip.putNextEntry(new ZipEntry(name));if(!name.endsWith("/"))zip.write((name.startsWith("word")?"<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>测试</w:t></w:r></w:p></w:body></w:document>":"<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>").getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
        }return out.toByteArray();
    }
    @Test void 独立加密重启读取和摘要校验()throws Exception{
        byte[] bytes=docx();var a=artifact("结果.docx",bytes.length);var storage=new FileSystemSkillArtifactStorage(policy());
        String hash=storage.write(a,new ByteArrayInputStream(bytes));a=a.change(SkillArtifactStatus.READY,hash,a.expiresAt(),null,null);
        assertThat(new FileSystemSkillArtifactStorage(policy()).readVerified(a)).isEqualTo(bytes);
        Path object=root.resolve(a.tenantId().toString()).resolve(a.id()+".gcm");
        assertThat(Files.readAllBytes(object)).isNotEqualTo(bytes).hasSize(bytes.length+32);
        byte[] encrypted=Files.readAllBytes(object);encrypted[encrypted.length-1]^=1;Files.write(object,encrypted);
        SkillArtifact saved=a;assertThatThrownBy(()->storage.readVerified(saved)).isInstanceOf(IOException.class);
    }
    @Test void 实际长度和伪装文档不发布()throws Exception{
        var storage=new FileSystemSkillArtifactStorage(policy());
        assertThatThrownBy(()->storage.write(artifact("x.txt",2),new ByteArrayInputStream(new byte[3]))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->storage.write(artifact("x.docx",3),new ByteArrayInputStream(new byte[3]))).isInstanceOf(FileSystemSkillArtifactStorage.FormatFailure.class);
        try(var files=Files.walk(root)){assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();}
    }
    @Test void 路径逃逸和宏结构拒绝()throws Exception{
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("../word/document.xml"));zip.write(1);zip.closeEntry();}
        assertThatThrownBy(()->FileSystemSkillArtifactStorage.validateFormat("x.docx",bytes.toByteArray())).isInstanceOf(IOException.class);
    }
}
