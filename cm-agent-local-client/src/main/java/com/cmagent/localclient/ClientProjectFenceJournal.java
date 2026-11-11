package com.cmagent.localclient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 客户端项目 Run 代次的追加式本机见证；当前组件尚未接入设备协议。
 *
 * <p>调用方只能使用认证后的服务端任务和已验证设备根构造键。初始化存储是显式操作，
 * 正常读取绝不自动创建丢失文件；不完整记录、格式错误或代次跳跃均拒绝。跨进程文件锁
 * 覆盖代次核验与同步动作，防止本机另一进程在动作开始前确认更高代次。记录摘要只检测
 * 意外损坏，不能证明文件未被恶意修改或整体回滚，完整记录被截断也无法单独检测；接入前
 * 必须验证服务端代次并建立可信初始化及持久化协议。它不能停止已启动的外部进程，
 * 也不能代替服务器隔离、设备握手和结果对账。</p>
 */
final class ClientProjectFenceJournal {
    private static final byte[] HEADER = "CMAGENT-FENCE-1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /** 同 JVM 的文件锁重叠不会阻塞，先用进程内锁串行化，再取得跨进程锁。 */
    private static final Object JVM_LOCK = new Object();
    private final Path file;

    ClientProjectFenceJournal(Path file) {
        if (file == null || !file.isAbsolute()) throw new IllegalArgumentException("代次见证路径必须为绝对路径");
        this.file = file.normalize();
    }

    /**
     * 仅供可信安装或迁移流程在受保护私有目录中显式建立一次空见证；不能用来修复丢失的既有见证。
     * @throws IOException 文件已存在或无法安全落盘时拒绝
     */
    void initializeStore() throws IOException {
        synchronized (JVM_LOCK) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                writeFully(channel, ByteBuffer.wrap(HEADER));
                channel.force(true);
            }
        }
    }

    /** 同一新 Run 可幂等确认首代；已有恢复代次不能重置为执行阶段。 */
    void initializeRun(Key key) throws IOException {
        initializeRun(key, true);
    }

    /** 已获服务端确认的 Run 不允许在本机见证丢失后自动重建首代。 */
    void initializeRun(Key key, boolean allowCreate) throws IOException {
        locked((channel, epochs) -> {
            State current = epochs.get(key);
            if (Objects.equals(current, new State(1, "EXECUTING"))) return null;
            if (current != null || !allowCreate) throw new IOException("运行代次见证缺失或已变化");
            append(channel, key, 1, "EXECUTING");
            return null;
        });
    }

    /**
     * 在确认恢复代次之前强制落盘；只接受当前代次的下一代或同一次恢复的重复确认，
     * 不支持缺失见证、跳代或把恢复阶段改回执行阶段。
     * @param key 与可信设备及项目根绑定的运行标识
     * @param previous 上一持久代次
     * @param next 受认证恢复请求中的下一代次
     * @throws IOException 见证缺失、损坏、过期或无法持久化时拒绝确认
     */
    void advance(Key key, long previous, long next) throws IOException {
        locked((channel, epochs) -> {
            if (previous < 1 || previous == Long.MAX_VALUE || next != previous + 1) {
                throw new IOException("运行恢复代次不匹配");
            }
            State current = epochs.get(key);
            // 确认响应丢失时允许同一恢复代次重读落盘见证；不重复写入或重新授予执行权。
            if (Objects.equals(current, new State(next, "RECOVERING"))) return null;
            if (current == null || current.epoch() != previous) throw new IOException("运行恢复代次不匹配");
            append(channel, key, next, "RECOVERING");
            return null;
        });
    }

    /**
     * 在文件锁内核验并执行同步动作；回调不能在返回后再启动受保护副作用。
     * @param key 已核验的设备、根与 Run
     * @param epoch 任务绑定的执行代次
     * @param action 在持锁期间同步执行的动作
     * @return 动作结果
     * @param <T> 动作返回类型
     * @throws IOException 见证缺失、旧代次或动作失败时拒绝
     */
    <T> T execute(Key key, long epoch, Action<T> action) throws IOException {
        Objects.requireNonNull(action);
        return locked((channel, epochs) -> {
            if (epoch < 1 || !Objects.equals(epochs.get(key), new State(epoch, "EXECUTING"))) {
                throw new IOException("运行执行代次已失效");
            }
            return action.run();
        });
    }

    /**
     * 在恢复代次下执行调用方固定的状态查询或停止，不授权文件写入或新命令启动。
     * @param key 已核验的设备、根与 Run
     * @param epoch 设备已持久确认的恢复代次
     * @param action 调用方提供的受限同步对账动作
     * @return 动作结果
     * @param <T> 动作返回类型
     * @throws IOException 见证缺失、阶段不符或动作失败时拒绝
     */
    <T> T recover(Key key, long epoch, Action<T> action) throws IOException {
        Objects.requireNonNull(action);
        return locked((channel, epochs) -> {
            if (epoch < 1 || !Objects.equals(epochs.get(key), new State(epoch, "RECOVERING"))) {
                throw new IOException("运行恢复代次已失效");
            }
            return action.run();
        });
    }

    private <T> T locked(LockedAction<T> action) throws IOException {
        synchronized (JVM_LOCK) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("运行代次见证缺失或不安全");
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = channel.lock()) {
                long size = channel.size();
                if (size < HEADER.length || size > MAX_BYTES) throw new IOException("运行代次见证损坏");
                ByteBuffer contents = ByteBuffer.allocate((int) size);
                while (contents.hasRemaining()) if (channel.read(contents) < 0) throw new IOException("运行代次见证截断");
                return action.run(channel, parse(contents.array()));
            }
        }
    }

    private Map<Key, State> parse(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.US_ASCII);
        if (!text.startsWith(new String(HEADER, StandardCharsets.US_ASCII)) || !text.endsWith("\n")) {
            throw new IOException("运行代次见证损坏");
        }
        Map<Key, State> values = new HashMap<>();
        String body = text.substring(HEADER.length);
        if (body.isEmpty()) return values;
        try {
            for (String line : body.split("\n")) {
                String[] fields = line.split("\\|", -1);
                if (fields.length != 8) throw new IllegalArgumentException();
                String content = String.join("|", java.util.Arrays.copyOf(fields, 7));
                if (!digest(content).equals(fields[7])) throw new IllegalArgumentException();
                Key key = new Key(UUID.fromString(fields[0]), UUID.fromString(fields[1]),
                        UUID.fromString(fields[2]), UUID.fromString(fields[3]), fields[4]);
                long value = Long.parseLong(fields[5]);
                String phase = fields[6];
                State prior = values.put(key, new State(value, phase));
                if (value < 1 || prior == null && value != 1
                        || prior == null && !phase.equals("EXECUTING")
                        || prior != null && (prior.epoch() == Long.MAX_VALUE || value != prior.epoch() + 1
                                || !phase.equals("RECOVERING"))) {
                    throw new IllegalArgumentException();
                }
            }
            return values;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("运行代次见证损坏");
        }
    }

    private void append(FileChannel channel, Key key, long epoch, String phase) throws IOException {
        Objects.requireNonNull(key);
        String value = key.tenantId + "|" + key.deviceId + "|" + key.rootId + "|" + key.runId
                + "|" + key.serverDigest + "|" + epoch + "|" + phase;
        byte[] bytes = (value + "|" + digest(value) + "\n").getBytes(StandardCharsets.US_ASCII);
        if (channel.size() + bytes.length > MAX_BYTES) throw new IOException("运行代次见证已满");
        channel.position(channel.size());
        writeFully(channel, ByteBuffer.wrap(bytes));
        // 回报新代次前必须将记录刷到存储；失败不能向 Server 假报确认。
        channel.force(true);
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * 代次作用域，均由已认证设备和持久项目来源确定。
     * @param tenantId 配对租户
     * @param deviceId 配对设备
     * @param rootId 已登记项目根
     * @param runId 原 Run
     * @param serverDigest 配对服务端身份的 SHA-256 摘要
     */
    record Key(UUID tenantId, UUID deviceId, UUID rootId, UUID runId, String serverDigest) {
        Key {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(deviceId);
            Objects.requireNonNull(rootId); Objects.requireNonNull(runId);
            if (serverDigest == null || !serverDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("服务端身份摘要无效");
        }
    }

    /** @param <T> 持锁动作返回类型 */
    @FunctionalInterface interface Action<T> { T run() throws IOException; }
    /** @param epoch 当前见证代次 @param phase 首发执行或受限恢复阶段 */
    private record State(long epoch, String phase) { }
    @FunctionalInterface private interface LockedAction<T> {
        T run(FileChannel channel, Map<Key, State> epochs) throws IOException;
    }
}
