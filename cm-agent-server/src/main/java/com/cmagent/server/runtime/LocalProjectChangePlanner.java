package com.cmagent.server.runtime;

import com.cmagent.core.runtime.LocalProjectPathPolicy;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 有界、无文件副作用的变更计划原语；不持久化、不批准、不执行文件修改。
 *
 * <p>调用方必须先完成项目owner/权限/节点校验，并在对外返回前保护正文。计划冻结旧正文摘要、
 * 目录身份及目标不存在条件；真正应用仍须原子核验前置条件、HIGH审批、操作日志和权限/备份恢复。
 * 本类不注册Bean或工具，不能把“计划已生成”当成“操作已执行”。</p>
 */
final class LocalProjectChangePlanner {
    /** 单文件与整批明文（含旧版本）硬界，不允许通过多个动作绕过内存/备份预算。 */
    private static final int MAX_FILE = 1_048_576, MAX_BATCH = 2_097_152, MAX_CHANGES = 20;
    /** 宿主传入的受信文件读取器，不自行接收客户端根路径。 */
    private final LocalProjectFiles files;

    LocalProjectChangePlanner(LocalProjectFiles files) { this.files = Objects.requireNonNull(files); }

    /**
     * 捕获有界旧版本与精确目标，不创建文件、目录、临时文件或备份。
     * @param root 受信本机项目根
     * @param changes 有序且互不重叠的动作，最多20项；依赖新父目录的动作须在该父目录实际创建后另行提案
     * @return 不可变计划，正文只能进入受保护的意图/预览通道
     * @throws IOException 安全路径、版本冲突、类型或预算失败，消息不含宿主路径
     */
    Plan plan(LocalProjectDirectoryTickets.Root root, List<Change> changes) throws IOException {
        if (changes == null || changes.isEmpty() || changes.size() > MAX_CHANGES)
            throw new LocalProjectChangeException(LocalProjectChangeException.Reason.LIMIT);
        List<Change> input = List.copyOf(changes);
        var touched = new ArrayList<String>();
        var prepared = new ArrayList<Delta>();
        int bytes = 0;
        for (Change change : input) {
            path(change.path());
            touch(touched, change.path());
            if (change.target() != null) {
                path(change.target());
                touch(touched, change.target());
            }
            String before = "", after = "", beforeVersion = "";
            WindowsLocalProjectSecurity.Snapshot security=null;
            byte[] snapshot = null;
            try {
                switch (change.kind()) {
                    case CREATE_FILE -> {
                        if (!LocalProjectPathPolicy.textCandidate(change.path()))
                            throw new LocalProjectChangeException(LocalProjectChangeException.Reason.UNSUPPORTED);
                        if (!files.absent(root, change.path()))
                            throw new LocalProjectChangeException(LocalProjectChangeException.Reason.CONFLICT);
                        after = change.content();
                        bytes = budget(bytes, textBytes(after));
                    }
                    case REPLACE_FILE, DELETE_FILE, MOVE_FILE -> {
                        snapshot = files.mutationSnapshot(root, change.path());
                        beforeVersion = sha(snapshot);
                        if (!beforeVersion.equals(change.expectedSha256()))
                            throw new LocalProjectChangeException(LocalProjectChangeException.Reason.CONFLICT);
                        before = new String(snapshot, StandardCharsets.UTF_8);
                        if(com.sun.jna.Platform.isWindows()){
                            security=WindowsLocalProjectSecurity.capture(root.directory().resolve(change.path()));
                            if(change.kind()==Kind.DELETE_FILE&&!security.restorable())
                                throw new IOException("该文件权限归属无法保证恢复，删除前拒绝");
                        }
                        bytes = budget(bytes, snapshot.length);
                        if (change.kind() == Kind.REPLACE_FILE) {
                            after = change.content();
                            bytes = budget(bytes, textBytes(after));
                            if (before.equals(after)) throw new LocalProjectChangeException(LocalProjectChangeException.Reason.NO_CHANGE);
                        }
                        if (change.kind() == Kind.MOVE_FILE) {
                            if (!LocalProjectPathPolicy.textCandidate(change.target()))
                                throw new LocalProjectChangeException(LocalProjectChangeException.Reason.UNSUPPORTED);
                            if (!files.absent(root, change.target()))
                                throw new LocalProjectChangeException(LocalProjectChangeException.Reason.CONFLICT);
                            after = before;
                        }
                    }
                    case CREATE_DIRECTORY -> {
                        if (!files.absent(root, change.path())) throw new LocalProjectChangeException(LocalProjectChangeException.Reason.CONFLICT);
                    }
                    case DELETE_EMPTY_DIRECTORY -> {
                        beforeVersion = files.emptyDirectoryVersion(root, change.path());
                        if(com.sun.jna.Platform.isWindows()){
                            security=WindowsLocalProjectSecurity.capture(root.directory().resolve(change.path()));
                            if(!security.restorable())throw new IOException("目录权限归属无法安全恢复，删除前拒绝");
                        }
                    }
                }
                prepared.add(new Delta(change.kind(), change.path(), change.target(), beforeVersion, before, after,security));
            } catch (LocalProjectFileException | LocalProjectChangeException failure) {
                throw failure;
            } catch (java.nio.file.NoSuchFileException failure) {
                throw new LocalProjectFileException(LocalProjectFileException.Reason.NOT_FOUND);
            } catch (IOException | RuntimeException failure) {
                throw new LocalProjectFileException(LocalProjectFileException.Reason.UNAVAILABLE);
            } finally { if (snapshot != null) Arrays.fill(snapshot, (byte) 0); }
        }
        return new Plan(prepared, fingerprint(prepared), bytes);
    }

    private static void path(String value) throws LocalProjectFileException {
        try {
            LocalProjectPathPolicy.requireRelative(value, false);
            if (LocalProjectPathPolicy.excluded(value)) throw new LocalProjectFileException(LocalProjectFileException.Reason.EXCLUDED);
        } catch (IllegalArgumentException failure) { throw new LocalProjectFileException(LocalProjectFileException.Reason.INVALID_PATH); }
    }

    /** 统一拒绝大小写别名与父子重叠，避免同批动作暗中依赖或覆盖另一项已预览对象。 */
    private static void touch(List<String> touched, String value) throws IOException {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String previous : touched) {
            if (normalized.equals(previous) || normalized.startsWith(previous + "/") || previous.startsWith(normalized + "/"))
                throw new LocalProjectChangeException(LocalProjectChangeException.Reason.OVERLAP);
        }
        touched.add(normalized);
    }

    private static int textBytes(String value) throws IOException {
        if (value == null || value.length() > MAX_FILE) throw new LocalProjectChangeException(LocalProjectChangeException.Reason.LIMIT);
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            if (bytes.remaining() > MAX_FILE) throw new LocalProjectChangeException(LocalProjectChangeException.Reason.LIMIT);
            if (value.codePoints().anyMatch(point -> Character.isISOControl(point)
                    && point != 9 && point != 10 && point != 13)) throw new LocalProjectFileException(LocalProjectFileException.Reason.INVALID_TEXT);
            return bytes.remaining();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new LocalProjectFileException(LocalProjectFileException.Reason.INVALID_TEXT);
        }
    }

    private static int budget(int current, int addition) throws IOException {
        if (addition > MAX_BATCH - current) throw new LocalProjectChangeException(LocalProjectChangeException.Reason.LIMIT);
        return current + addition;
    }

    /** 使用长度前缀而非字符串分隔符，防止内容中的换行/分隔字符造成不同计划的摘要歧义。 */
    private static String fingerprint(List<Delta> changes) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(1);
            output.writeInt(changes.size());
            for (Delta change : changes) {
                for (String value : List.of(change.kind().name(), change.path(), change.target() == null ? "" : change.target(),
                        change.beforeVersion(), change.before(), change.after(),change.security()==null?"":change.security().fingerprint())) {
                    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length);
                    output.write(encoded);
                    Arrays.fill(encoded, (byte) 0);
                }
            }
        }
        byte[] encoded = bytes.toByteArray();
        try { return sha(encoded); }
        finally { Arrays.fill(encoded, (byte) 0); }
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("变更摘要能力不可用"); }
    }

    /** 提案动作不等于已获得执行权限；所有副作用后续均走HIGH审批。 */
    enum Kind {
        /** 原目标必须不存在的文本新建。 */ CREATE_FILE,
        /** 原正文摘要必须匹配的原地编辑。 */ REPLACE_FILE,
        /** 原正文须备份且摘要匹配的普通文件删除。 */ DELETE_FILE,
        /** 源摘要匹配且目标不存在的普通文件移动。 */ MOVE_FILE,
        /** 父目录已存在的单层目录新建。 */ CREATE_DIRECTORY,
        /** 对象身份固定且仍为空的目录删除，不递归删除。 */ DELETE_EMPTY_DIRECTORY
    }

    /**
     * @param kind 精确动作类型
     * @param path 根内源或创建路径
     * @param target 移动的根内目标，其他动作必须为空
     * @param content 创建/替换文本，其他动作必须为空
     * @param expectedSha256 编辑/删除/移动文件的已读正文摘要，其他动作必须为空
     */
    record Change(Kind kind, String path, String target, String content, String expectedSha256) {
        Change {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(path);
            boolean text = kind == Kind.CREATE_FILE || kind == Kind.REPLACE_FILE;
            boolean existing = kind == Kind.REPLACE_FILE || kind == Kind.DELETE_FILE || kind == Kind.MOVE_FILE;
            if ((content != null) != text || (target != null) != (kind == Kind.MOVE_FILE)
                    || existing && (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}"))
                    || !existing && expectedSha256 != null)
                throw new IllegalArgumentException("变更动作字段不合法");
        }
        @Override public String toString() { return "Change[受保护的变更请求]"; }
    }

    /**
     * @param kind 精确动作
     * @param path 根内来源
     * @param target 移动目标，其他动作为空
     * @param beforeVersion 文件正文或空目录身份摘要，新增为空
     * @param before 受保护的旧正文，禁止进入普通摘要或日志
     * @param after 受保护的新正文，禁止进入普通摘要或日志
     * @param security 受保护的原权限快照，删除恢复必须验证，非Windows或新增为空
     */
    record Delta(Kind kind, String path, String target, String beforeVersion, String before, String after,WindowsLocalProjectSecurity.Snapshot security) {
        @Override public String toString() { return "Delta[受保护的变更差异]"; }
    }

    /**
     * @param changes 不可变有序差异，包含受保护正文
     * @param digest 精确计划摘要，仍须由上层绑定tenant/owner/Run/根身份后加密
     * @param retainedBytes 已捕获的前后正文字节预算，不表示实际磁盘写入
     */
    record Plan(List<Delta> changes, String digest, int retainedBytes) {
        Plan { changes = List.copyOf(changes); }
        @Override public String toString() { return "Plan[受保护的变更计划]"; }
    }
}

