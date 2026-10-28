package com.cmagent.server.runtime;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 原始HTTP正文读取器，避免multipart临时落盘；最多四个无队列工作线程，调用线程有总等待期限。
 * 若底层流不响应中断，真实工作线程仍占用池槽，不能提前归还槽而无限创建挂起线程。
 */
@Component
public final class WorkspaceImportBodyReader {
    /** 无排队的固定上限，拒绝时由导入最终边界映射429；不与模型执行池争用。 */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
        var thread = new Thread(runnable, "workspace-import-body"); thread.setDaemon(true); return thread;
    });

    /**
     * @param inputFactory 经认证与owner校验后才获取的HTTP流，工作线程负责关闭
     * @param maxBytes 最多读取的字节数，调用方须包含识别超限所需的一字节
     * @param timeout 调用线程总等待预算
     * @return 有界字节数组，调用方使用后负责清零
     * @throws IOException 原始流异常已去除内部消息
     * @throws TimeoutException 请求体未在期限内完成，不能自动重试写操作
     * @throws RejectedExecutionException 实际读取槽已满，不另建线程或排队
     */
    public byte[] read(InputFactory inputFactory, int maxBytes, Duration timeout) throws IOException, TimeoutException {
        if (maxBytes < 1 || maxBytes > 32769) throw new IllegalArgumentException("导入正文读取边界不合法");
        var future = executor.submit(() -> {
            try (var input = inputFactory.open()) {
                byte[] bytes = input.readNBytes(maxBytes);
                if (Thread.currentThread().isInterrupted()) { Arrays.fill(bytes, (byte) 0); throw new IOException("导入正文读取已取消"); }
                return bytes;
            }
        });
        try { return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException failure) { future.cancel(true); throw failure; }
        catch (InterruptedException failure) { future.cancel(true); Thread.currentThread().interrupt(); throw new IOException("导入正文读取被中断"); }
        catch (ExecutionException failure) { throw new IOException("导入正文未能完整读取"); }
    }
    /** 流只由读取任务打开/关闭，避免请求返回时主线程阻塞在不响应中断的close上。 */
    @FunctionalInterface public interface InputFactory {
        /** @return 当前请求的原始输入流，不能以客户端文件路径替代 */
        InputStream open() throws IOException;
    }
    /** 生命周期由Spring持有，停止应用时中断当前读取；不阻塞等待不可控外部流。 */
    @PreDestroy public void close() { executor.shutdownNow(); }
}
