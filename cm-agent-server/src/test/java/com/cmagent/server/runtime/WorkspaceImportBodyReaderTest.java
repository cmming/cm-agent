package com.cmagent.server.runtime;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class WorkspaceImportBodyReaderTest {
    @Test void 正文有界且流由读取任务关闭() throws Exception {
        var reader=new WorkspaceImportBodyReader();boolean[] closed={false};
        var input=new ByteArrayInputStream(new byte[100]){@Override public void close(){closed[0]=true;}};
        try {assertThat(reader.read(()->input,3,Duration.ofSeconds(1))).hasSize(3);assertThat(input.available()).isEqualTo(97);assertThat(closed[0]).isTrue();}
        finally {reader.close();}
    }
    @Test void 超时但底层忽略中断时仍保留真实并发占用不无限建线程() throws Exception {
        var reader=new WorkspaceImportBodyReader();var unblock=new CountDownLatch(1);var entered=new CountDownLatch(4);
        try(var callers=Executors.newFixedThreadPool(4)) {
            var results=new ArrayList<Future<?>>();
            for(int i=0;i<4;i++)results.add(callers.submit(()->assertThatThrownBy(()->reader.read(()->new InputStream(){
                @Override public int read(){entered.countDown();while(unblock.getCount()>0)try{unblock.await();}catch(InterruptedException ignored){/* 模拟不响应中断的外部流，槽必须继续占用。 */}return -1;}
            },1,Duration.ofMillis(100))).isInstanceOf(TimeoutException.class)));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();for(var result:results)result.get(2,TimeUnit.SECONDS);
            assertThatThrownBy(()->reader.read(()->new ByteArrayInputStream(new byte[1]),1,Duration.ofSeconds(1))).isInstanceOf(RejectedExecutionException.class);
        }finally {unblock.countDown();reader.close();}
    }
}
