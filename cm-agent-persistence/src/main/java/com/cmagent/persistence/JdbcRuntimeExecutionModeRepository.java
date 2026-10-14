package com.cmagent.persistence;

import com.cmagent.core.repository.RuntimeExecutionModeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

/**
 * 持久化全数据集执行模式，并在切换事务内核对排空。
 *
 * <p>模式记录是系统级部署元数据，没有 tenant 字段。跨租户查询只用于检测阻塞，
 * 不返回业务资源；普通请求只能核对已选模式，不能发起切换。运行编排在调用 Runtime 前已持久化
 * RUNNING，故通过模式核对的在途请求会阻塞并发切换，旧模式实例的新请求会明确拒绝。</p>
 */
public final class JdbcRuntimeExecutionModeRepository implements RuntimeExecutionModeRepository {
    /** 只执行具名参数与固定系统查询，不接受客户端表名/模式输入。 */
    private final JdbcClient jdbc;
    /** 模式行锁和排空检查共享短事务，不跨模型执行持有连接。 */
    private final TransactionTemplate transactions;

    /**
     * 创建部署门禁；两个组件必须绑定同一个数据源，否则行锁无法覆盖排空查询和更新。
     * @param jdbc 已完成 V21 迁移的数据源客户端，不能为空
     * @param transactions 同一数据源的短事务模板，初始化入口不能跨模型调用持有事务
     */
    public JdbcRuntimeExecutionModeRepository(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override public void activate(String mode) {
        validate(mode);
        transactions.executeWithoutResult(status -> {
            String current = jdbc.sql("SELECT execution_mode FROM runtime_execution_modes WHERE id=1 FOR UPDATE")
                    .query(String.class).single();
            if (current.equals(mode)) return;
            // 终态使用白名单；未知状态、过期检查点及孤立 TEST 也阻塞，不能静默清除或假定安全。
            boolean occupied = count("SELECT COUNT(*) FROM runs WHERE status NOT IN ('SUCCEEDED','FAILED','DENIED','CANCELLED')") > 0
                    || count("SELECT COUNT(*) FROM skill_trials WHERE status NOT IN ('PASSED','NOT_TRIGGERED','FAILED')") > 0
                    || count("SELECT COUNT(*) FROM runtime_checkpoints") > 0;
            if (occupied) throw new IllegalStateException("切换执行模式前必须排空非终态 Run、TEST 和检查点");
            jdbc.sql("UPDATE runtime_execution_modes SET execution_mode=:mode, updated_at=CURRENT_TIMESTAMP WHERE id=1")
                    .param("mode", mode).update();
        });
    }

    @Override public String currentMode() {
        return jdbc.sql("SELECT execution_mode FROM runtime_execution_modes WHERE id=1").query(String.class).single();
    }

    private long count(String sql) { return jdbc.sql(sql).query(Long.class).single(); }

    /** 仓储同样校验部署值，防止绕过配置绑定写入不支持的模式。 */
    private static void validate(String mode) {
        if (!"react".equals(mode) && !"harness".equals(mode))
            throw new IllegalArgumentException("执行模式只支持 react 或 harness");
    }
}

