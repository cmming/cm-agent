package com.cmagent.core.runtime;
import com.cmagent.core.domain.SkillArtifact;
import java.io.*;
/** 部署者注册的产物字节存储 SPI；对象键只由可信 UUID 构造，不接受宿主路径。 */
public interface SkillArtifactStorage {
    /** 原子持久化并返回明文 SHA-256；失败不得遗留可交付的对象。 */
    String write(SkillArtifact artifact,InputStream input) throws IOException;
    /** 完成全部认证、摘要与长度验证后返回当前请求独占的明文字节。 */
    byte[] readVerified(SkillArtifact artifact) throws IOException;
    /** 幂等删除对象，删除确认前不得释放数据库配额。 */
    void delete(SkillArtifact artifact) throws IOException;
}
