package com.cmagent.localclient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 将可信 HTTPS 服务端代次、本机根身份和持久见证组合为受限握手。
 *
 * <p>设备轮询在服务端返回代次要求时通过本组件同步；调用方必须先显式建立受保护的见证
 * 文件。已被服务端确认的首代若缺失本机见证，不能自动重建。执行时在本机文件锁内重新查询
 * 服务端并核验当前代次，防止在确认恢复后启动旧任务；已启动进程仍需独立状态对账。</p>
 */
final class ClientProjectFenceHandshake {
    private final LocalClientServerClient client;
    private final ClientProjectFenceJournal journal;
    private final RootVerifier roots;

    /** @param client 使用默认可信 TLS 的设备连接 @param roots 当前用户的根登记 @param journal 已显式建立的代次见证 */
    ClientProjectFenceHandshake(LocalClientServerClient client, ClientProjectRootStore roots,
            ClientProjectFenceJournal journal) {
        this(client, (identity, rootId) -> roots.verify(identity, rootId).identity(), journal);
        Objects.requireNonNull(roots);
    }

    /** 同包测试可注入根校验夹具，生产须使用真实根登记。 */
    ClientProjectFenceHandshake(LocalClientServerClient client, RootVerifier roots,
            ClientProjectFenceJournal journal) {
        this.client = Objects.requireNonNull(client);
        this.roots = Objects.requireNonNull(roots);
        this.journal = Objects.requireNonNull(journal);
    }

    /**
     * 从可信服务端读取当前代次，核对根身份并落盘，然后签名确认。
     * @param identity 当前已配对设备身份
     * @param runId 待同步的已固定来源运行
     * @return 已签名确认的服务端快照；仍不表示旧进程已退出
     * @throws IOException 认证、根身份、见证或确认失败时拒绝
     */
    LocalClientServerClient.FenceView synchronize(DeviceIdentityStore.StoredIdentity identity, UUID runId)
            throws IOException {
        var view = current(identity, runId);
        var key = key(identity, view.rootId(), runId);
        verifyRoot(identity, view);
        if ("EXECUTING".equals(view.phase()) && view.epoch() == 1) {
            journal.initializeRun(key, !view.acknowledged());
        } else if ("RECOVERING".equals(view.phase()) && view.epoch() > 1) {
            journal.advance(key, view.epoch() - 1, view.epoch());
        } else {
            throw new IOException("设备运行代次阶段无效");
        }
        try {
            client.acknowledgeProjectFence(identity, runId, view.epoch());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("设备代次确认被中断", interrupted);
        } catch (GeneralSecurityException failure) {
            throw new IOException("设备代次确认签名失败", failure);
        }
        return view;
    }

    /**
     * 在本机锁内重新核验服务端当前执行代次后同步执行受保护动作。
     * @param identity 当前设备身份
     * @param runId 任务固定运行
     * @param rootId 任务固定设备根
     * @param epoch 任务固定代次
     * @param action 持锁期间同步完成的操作，不能延后启动副作用
     * @return 动作结果
     * @param <T> 动作结果类型
     * @throws IOException 任何身份、确认或代次核验失败时拒绝执行
     */
    <T> T execute(DeviceIdentityStore.StoredIdentity identity, UUID runId, UUID rootId, long epoch,
            ClientProjectFenceJournal.Action<T> action) throws IOException {
        var key = key(identity, rootId, runId);
        return journal.execute(key, epoch, () -> {
            var view = current(identity, runId);
            if (!view.rootId().equals(rootId) || view.epoch() != epoch || !"EXECUTING".equals(view.phase())
                    || !view.acknowledged()) throw new IOException("设备执行代次尚未确认或已经失效");
            verifyRoot(identity, view);
            return action.run();
        });
    }

    /**
     * 按服务端固定阶段执行任务；恢复阶段只允许状态查询和停止，不能重放启动或文件变更。
     *
     * @param identity 当前设备身份
     * @param runId 任务固定 Run
     * @param rootId 任务固定根
     * @param epoch 任务固定代次
     * @param phase 执行或恢复阶段
     * @param category 固定任务类别
     * @param action 持锁期间完成的同步动作
     * @return 动作结果
     * @param <T> 结果类型
     * @throws IOException 阶段、来源、确认或本地见证不匹配时拒绝
     */
    <T> T executeTask(DeviceIdentityStore.StoredIdentity identity, UUID runId, UUID rootId,
            long epoch, String phase, String category, ClientProjectFenceJournal.Action<T> action)
            throws IOException {
        if ("EXECUTING".equals(phase)) return execute(identity, runId, rootId, epoch, action);
        if (!"RECOVERING".equals(phase)
                || !("PROJECT_COMMAND_STATUS".equals(category) || "PROJECT_COMMAND_STOP".equals(category))) {
            throw new IOException("恢复代次只允许命令状态和停止对账");
        }
        var key = key(identity, rootId, runId);
        return journal.recover(key, epoch, () -> {
            var view = current(identity, runId);
            if (!view.rootId().equals(rootId) || view.epoch() != epoch || !"RECOVERING".equals(view.phase())
                    || !view.acknowledged()) throw new IOException("设备恢复代次尚未确认或已经失效");
            verifyRoot(identity, view);
            return action.run();
        });
    }

    private LocalClientServerClient.FenceView current(DeviceIdentityStore.StoredIdentity identity, UUID runId)
            throws IOException {
        try {
            return client.currentProjectFence(identity, runId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("设备代次读取被中断", interrupted);
        } catch (GeneralSecurityException failure) {
            throw new IOException("设备代次读取签名失败", failure);
        }
    }

    private void verifyRoot(DeviceIdentityStore.StoredIdentity identity, LocalClientServerClient.FenceView view)
            throws IOException {
        if (!view.rootIdentity().equals(roots.verify(identity, view.rootId())))
            throw new IOException("设备项目根身份已变化");
    }

    private ClientProjectFenceJournal.Key key(DeviceIdentityStore.StoredIdentity identity, UUID rootId, UUID runId)
            throws IOException {
        Objects.requireNonNull(identity); Objects.requireNonNull(rootId); Objects.requireNonNull(runId);
        var metadata = identity.metadata();
        if (metadata.tenantId() == null || metadata.deviceId() == null) throw new IOException("设备尚未配对");
        URI origin = LocalClientServerClient.requireFenceHttps(URI.create(metadata.serverUrl()));
        int port = origin.getPort() == -1 ? 443 : origin.getPort();
        String canonical = "https|" + origin.getHost().toLowerCase(Locale.ROOT) + "|" + port;
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.US_ASCII)));
            return new ClientProjectFenceJournal.Key(metadata.tenantId(), metadata.deviceId(), rootId, runId, digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** @param identity 已配对设备 @param rootId 待验证的根 @return 真实目录对象身份摘要 */
    @FunctionalInterface interface RootVerifier {
        String verify(DeviceIdentityStore.StoredIdentity identity, UUID rootId) throws IOException;
    }
}
