package com.cmagent.server.runtime;
import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.config.SkillArtifactProperties;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class SkillArtifactProtocolTest {
    private final SkillArtifactProperties policy=new SkillArtifactProperties();
    private String read(String frames)throws Exception{
        return new SkillArtifactProtocol().read(new ByteArrayInputStream(frames.getBytes(StandardCharsets.UTF_8)),DockerSkillSandboxIntegrationTest.request(),policy,1024,(n,s,in)->assertThat(in.readAllBytes()).hasSize((int)s));
    }
    @Test void 文本和二进制有界流保留边界()throws Exception{
        assertThat(read("CMA1\n{\"kind\":\"text\",\"size\":2}\nok{\"kind\":\"file\",\"name\":\"结果.txt\",\"size\":3}\nabc{\"kind\":\"end\"}\n")).isEqualTo("ok");
    }
    @Test void 非法名称截断尾随数据和重复帧拒绝(){
        String head="CMA1\n{\"kind\":\"text\",\"size\":0}\n";
        for(String bad:new String[]{"wrong",head+"{\"kind\":\"file\",\"name\":\"../x.txt\",\"size\":1}\nx",head+"{\"kind\":\"file\",\"name\":\"x.txt\",\"size\":3}\nx",head+"{\"kind\":\"end\"}\nextra",head+"{\"kind\":\"text\",\"size\":0}\n"})
            assertThatThrownBy(()->read(bad)).isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_ARTIFACT_INVALID));
    }
    @Test void 巨大长度在读取和分配前拒绝(){
        assertThatThrownBy(()->read("CMA1\n{\"kind\":\"text\",\"size\":0}\n{\"kind\":\"file\",\"name\":\"x.txt\",\"size\":9223372036854775807}\n"))
            .isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_ARTIFACT_LIMIT_EXCEEDED));
    }
}
