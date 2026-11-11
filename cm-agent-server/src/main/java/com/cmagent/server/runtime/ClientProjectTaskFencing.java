package com.cmagent.server.runtime;

import com.cmagent.core.domain.LocalClientDevice;
import com.cmagent.persistence.JdbcClientProjectFenceAcknowledgements;
import com.cmagent.persistence.JdbcClientProjectFencedTaskReader;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 设备项目任务领取前的可信代次门禁。
 *
 * <p>设备必须先对服务端当前代次完成本地持久化并签名确认；未确认时只返回核对要求，不改变
 * 任务状态。确认后由同一数据源事务条件领取带栅栏任务，旧协议领取路径不会看到这些任务。</p>
 */
public final class ClientProjectTaskFencing {
    private final JdbcClientProjectFenceAcknowledgements acknowledgements;
    private final JdbcClientProjectFencedTaskReader reader;
    private final TransactionTemplate transaction;

    /**
     * @param acknowledgements 已验签设备确认仓储
     * @param reader 带栅栏任务领取仓储
     * @param transaction 与两者一致的数据源事务
     */
    public ClientProjectTaskFencing(JdbcClientProjectFenceAcknowledgements acknowledgements,
            JdbcClientProjectFencedTaskReader reader, TransactionTemplate transaction) {
        this.acknowledgements = Objects.requireNonNull(acknowledgements);
        this.reader = Objects.requireNonNull(reader);
        this.transaction = Objects.requireNonNull(transaction);
    }

    /**
     * @param device 已验签设备
     * @return 尚未确认的当前运行代次；没有待确认项时为空
     */
    public Optional<ClientProjectFenceService.FenceView> awaiting(LocalClientDevice device) {
        return acknowledgements.awaitingCurrent(device).map(value -> new ClientProjectFenceService.FenceView(
                value.runId(), value.rootId(), value.rootIdentity(), value.epoch(),
                value.phase().name(), value.acknowledged()));
    }

    /**
     * @param device 已完成当前代次签名确认的验签设备
     * @return 条件领取的带栅栏任务；无候选或竞争失败时为空
     */
    public Optional<JdbcClientProjectFencedTaskReader.Claim> claim(LocalClientDevice device) {
        return Objects.requireNonNull(transaction.execute(status -> reader.claim(device)));
    }

    /**
     * 在同一数据库事务领取任务并执行不含文件 I/O 的响应映射或审计。
     *
     * @param device 已完成当前代次确认的验签设备
     * @param mapper 在任务行锁仍持有期间构造响应
     * @return 成功领取并映射的任务；无候选时为空
     * @param <T> 响应类型
     */
    public <T> Optional<T> claim(LocalClientDevice device,
            Function<JdbcClientProjectFencedTaskReader.Claim, T> mapper) {
        Objects.requireNonNull(mapper);
        return Objects.requireNonNull(transaction.execute(status -> reader.claim(device)
                .map(mapper)));
    }
}
