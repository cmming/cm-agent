package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.runtime.SkillAccessException;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

/** 无Docker的传输故障测试；回环黑洞模拟服务器不返回SSH握手，不能使用生产主机或凭据。 */
class PasswordSshRelayTest {
    @Test void 握手黑洞有界超时且不泄漏密码()throws Exception{
        try(var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var stopped=new CountDownLatch(1);
            workers.submit(()->{try(var socket=listener.accept()){while(socket.getInputStream().read()!=-1){}}catch(Exception ignored){}finally{stopped.countDown();}});
            String marker=UUID.randomUUID().toString();
            assertThatThrownBy(()->PasswordSshRelay.open("localhost",InetAddress.getLoopbackAddress(),listener.getLocalPort(),"fixture",
                new SandboxCredentials("","测试信任","","",marker),Duration.ofMillis(150)))
                .isInstanceOfSatisfying(SkillAccessException.class,e->{assertThat(e.code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_TIMEOUT);assertThat(e.getMessage()).doesNotContain(marker);});
            assertThat(stopped.await(2,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void 握手中断主动终止网络并恢复中断标记()throws Exception{
        try(var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var workers=Executors.newVirtualThreadPerTaskExecutor()){
            var connected=new CountDownLatch(1);var stopped=new CountDownLatch(1);
            workers.submit(()->{try(var socket=listener.accept()){connected.countDown();while(socket.getInputStream().read()!=-1){}}catch(Exception ignored){}finally{stopped.countDown();}});
            var failure=new AtomicReference<SkillAccessException>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
            Thread caller=Thread.startVirtualThread(()->{
                try{PasswordSshRelay.open("localhost",InetAddress.getLoopbackAddress(),listener.getLocalPort(),"fixture",
                    new SandboxCredentials("","测试信任","","",UUID.randomUUID().toString()),Duration.ofSeconds(8));}
                catch(SkillAccessException e){failure.set(e);interrupted.set(Thread.currentThread().isInterrupted());}
            });
            assertThat(connected.await(2,TimeUnit.SECONDS)).isTrue();caller.interrupt();caller.join(3000);
            assertThat(caller.isAlive()).isFalse();assertThat(failure.get().code()).isEqualTo(ApiErrorCode.SKILL_SANDBOX_TIMEOUT);
            assertThat(interrupted).isTrue();assertThat(stopped.await(2,TimeUnit.SECONDS)).isTrue();
        }
    }
}
