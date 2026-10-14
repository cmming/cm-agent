package com.cmagent.server.store;

import com.cmagent.core.repository.RuntimeExecutionModeRepository;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** 仅供本地和测试的进程内模式门禁；memory 重启本身会丢失全部运行状态，不能用于生产。 */
public final class InMemoryRuntimeExecutionModeRepository implements RuntimeExecutionModeRepository {
    /** 系统级排空探测，仅返回是否存在阻塞，不返回其他租户资源。 */
    private final BooleanSupplier occupied;
    /** 与 activate/currentMode 同一实例锁保护当前模式。 */
    private String mode = "react";

    public InMemoryRuntimeExecutionModeRepository(BooleanSupplier occupied) {
        this.occupied = Objects.requireNonNull(occupied);
    }
    @Override public synchronized void activate(String target) {
        if (!"react".equals(target) && !"harness".equals(target))
            throw new IllegalArgumentException("执行模式只支持 react 或 harness");
        if (mode.equals(target)) return;
        if (occupied.getAsBoolean())
            throw new IllegalStateException("切换执行模式前必须排空非终态 Run、TEST 和检查点");
        mode = target;
    }
    @Override public synchronized String currentMode() { return mode; }
}

