package com.cmagent.server.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 原生标准管道只用安全Java夹具；不启动用户命令。 */
@EnabledOnOs(OS.WINDOWS)
class LocalProjectCommandOutputTest {
    @TempDir Path temp;
    @Test void 独占捕获分别收到stdout和stderr且自然EOF可退出() throws Exception {
        var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
        try(var running=new WindowsLocalProjectProcess().start(launch("output"),true)){
            long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(running.alive()&&System.nanoTime()<deadline){out.write(running.poll(false));err.write(running.poll(true));Thread.sleep(5);}
            for(int i=0;i<10;i++){out.write(running.poll(false));err.write(running.poll(true));}
            assertThat(running.alive()).isFalse();
            assertThat(running.exitCode()).isZero();
        }
        assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("stdout");
        assertThat(err.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("stderr");
    }
    @Test void 洪泛双管道持续排空不因等待主进程导致死锁() throws Exception {
        long count=0;
        try(var running=new WindowsLocalProjectProcess().start(launch("flood"),true)){
            long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
            while(running.alive()&&System.nanoTime()<deadline){count+=running.poll(false).length+running.poll(true).length;Thread.sleep(1);}
            for(int i=0;i<20;i++)count+=running.poll(false).length+running.poll(true).length;
            assertThat(running.alive()).isFalse();
            assertThat(running.exitCode()).isZero();
        }
        assertThat(count).isGreaterThan(100000);
    }
    private WindowsLocalProjectProcess.Launch launch(String mode)throws Exception{
        String cp=Path.of(LocalProjectProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new WindowsLocalProjectProcess.Launch(Path.of(System.getProperty("java.home"),"bin","java.exe"),
                List.of("-Dfile.encoding=UTF-8","-cp",cp,LocalProjectProcessFixture.class.getName(),mode),temp,
                Map.of("SystemRoot",System.getenv("SystemRoot")));
    }
}

