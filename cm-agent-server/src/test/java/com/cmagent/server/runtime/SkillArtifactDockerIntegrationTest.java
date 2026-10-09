package com.cmagent.server.runtime;
import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.config.SkillSandboxProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** 只在 Rocky 隔离 Docker 中执行，测试固定脚本与退出前的文件协议。 */
@EnabledIfEnvironmentVariable(named="CM_AGENT_TEST_SANDBOX",matches="true")
class SkillArtifactDockerIntegrationTest {
    private SkillSandboxProperties policy(){var p=new SkillSandboxProperties();p.setEnabled(true);var a=p.getArtifacts();a.setEnabled(true);a.setRootDirectory("/tmp/artifact-fixture");a.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));return p;}
    private String execute(SkillSandboxProperties p,String script)throws Exception{
        try(var execution=new DockerSkillSandbox(p).open(DockerSkillSandboxIntegrationTest.request(),Map.of())){return execution.execute(Map.of("scripts/main.py",script),"",(name,size,input)->{byte[] bytes=input.readAllBytes();assertThat(bytes).hasSize((int)size);FileSystemSkillArtifactStorage.validateFormat(name,bytes);});}
    }
    @Test void 固定Python生成合法DOCX并在退出前回传()throws Exception{assertThat(execute(policy(),Files.readString(Path.of("src/test/resources/skill-artifacts/scripts/main.py")))).contains("已生成测试文档");}
    @Test void 符号硬链接和特殊文件拒绝(){
        for(String script:List.of("import os;os.symlink('/etc/passwd','/workspace/output/x.txt')","import os,pathlib;pathlib.Path('/workspace/output/x.txt').write_text('x');os.link('/workspace/output/x.txt','/workspace/output/y.txt')","import os;os.mkfifo('/workspace/output/x.txt')"))
            assertThatThrownBy(()->execute(policy(),script)).isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_ARTIFACT_INVALID));
    }
    @Test void 开启文件时仍遵守脚本超时和输出限制(){
        var p=policy();p.setTimeout(Duration.ofSeconds(2));assertThatThrownBy(()->execute(p,"import time;time.sleep(120)")).isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_TIMEOUT));
        p.setMaxOutputBytes(1024);assertThatThrownBy(()->execute(p,"while True: print('x'*4096,flush=True)")).isInstanceOfSatisfying(SkillAccessException.class,e->assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_LIMIT_EXCEEDED));
    }
}
