package com.cmagent.core.runtime;

import java.util.concurrent.CompletableFuture;

/**
 * 一次运行独享的协作式停止信号，不包含权限或租户信息。
 *
 * <p>创建者必须先校验可信主体与运行归属再请求停止；执行器持有此对象，停止订阅后仍需调用
 * {@link #awaitQuiescence()} 等待已经进入外部网关的调用退出，不能把发布信号当作执行收口。</p>
 */
public final class RunCancellation {
    /** 仅用于通知事件流取消，不允许订阅者反向取消共享 Future。 */
    private final CompletableFuture<Void> signal = new CompletableFuture<>();
    /** 与调用入场共享锁，定义停止与派发的先后关系。 */
    private boolean requested;
    /** 已入场且尚未退出的治理调用数量，包括忽略线程中断的外部调用。 */
    private int invocations;

    /** 发布一次停止信号；返回是否为首次请求，重复请求无副作用。 */
    public boolean request() {
        synchronized (this) {
            if (requested) return false;
            requested = true;
        }
        signal.complete(null);
        return true;
    }

    /** @return 是否已登记停止，所有执行线程观察同一状态 */
    public synchronized boolean isRequested() {
        return requested;
    }

    /** @return 停止事件的副本；调用方取消或完成副本不能改写本轮权威停止信号 */
    public CompletableFuture<Void> signal() {
        return signal.copy();
    }

    /**
     * 原子检查停止并进入一次治理调用；调用方必须在 finally 或 try-with-resources 中关闭许可。
     * 停止前已入场的调用可能产生外部副作用，停止后新入场则抛出受控异常。
     */
    public synchronized Invocation enter() {
        if (requested) throw new StoppedException();
        invocations++;
        return new Invocation();
    }

    /**
     * 等待所有入场调用实际退出；中断不能用于伪报停止完成，退出时恢复调用线程中断标记。
     * 不响应中断的外部调用会让运行保持停止中，调用方不得在数据库事务内等待。
     */
    public void awaitQuiescence() {
        boolean interrupted = false;
        synchronized (this) {
            while (invocations != 0) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** 单次调用许可；关闭幂等，防止资源清理重复减少计数。 */
    public final class Invocation implements AutoCloseable {
        /** 仅在外层信号锁内读写的许可释放状态。 */
        private boolean closed;

        private Invocation() { }

        @Override
        public void close() {
            synchronized (RunCancellation.this) {
                if (!closed) {
                    closed = true;
                    invocations--;
                    RunCancellation.this.notifyAll();
                }
            }
        }
    }

    /** 停止后的派发拒绝，属于正常用户控制，不能包装成模型故障。 */
    public static final class StoppedException extends RuntimeException {
        public StoppedException() {
            super("本轮运行已请求停止");
        }
    }
}
