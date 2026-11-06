package com.cmagent.localclient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 本机客户端的有界文件操作；当前仅供客户端内部调用，尚未连接远程入口。
 *
 * <p>路径按本地操作系统账号权限解析，不以 Server 或项目目录作为根。调用者必须在调用前
 * 验证设备、原主体、本轮授权和具体操作；此类本身不构成授权或操作系统沙箱。</p>
 */
final class LocalFileOperations {
    /** 防止一次工具调用无界占用客户端内存。 */
    static final int MAX_BYTES = 4 * 1024 * 1024;
    /** 单次目录扫描的条目预算；超出时明确返回不完整状态。 */
    private static final int MAX_DIRECTORY_ENTRIES = 100_000;

    /**
     * 读取本机目录的有界条目页。
     *
     * <p>按名称排序并使用排他游标。并发创建或删除文件可能改变后续页；扫描达到预算时返回
     * 不完整状态且不生成续页游标，不能把结果当作完整目录。</p>
     *
     * @param directory 用户授权的本机绝对目录
     * @param after 上一页最后一个名称；第一页可为 {@code null}
     * @param limit 每页最多返回的条目数量，范围为 1 至 500
     * @return 名称、类型和完整性状态组成的目录页
     * @throws IOException 目录不可访问
     */
    DirectoryPage list(Path directory, String after, int limit) throws IOException {
        requireAbsolute(directory);
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("目录条目上限无效");
        if (!Files.isDirectory(directory)) throw new IOException("本地目录不存在或不可访问");
        TreeMap<String, Path> selected = new TreeMap<>();
        int scanned = 0;
        boolean incomplete = false;
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                if (++scanned > MAX_DIRECTORY_ENTRIES) {
                    incomplete = true;
                    break;
                }
                String name = entry.getFileName().toString();
                if (after != null && name.compareTo(after) <= 0) continue;
                selected.put(name, entry);
                if (selected.size() > limit + 1) selected.pollLastEntry();
            }
        }
        boolean more = selected.size() > limit;
        if (more) selected.pollLastEntry();
        List<DirectoryEntry> entries = new ArrayList<>(selected.size());
        for (var entry : selected.entrySet()) {
            Path path = entry.getValue();
            EntryType type = Files.isSymbolicLink(path) ? EntryType.SYMLINK
                    : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? EntryType.DIRECTORY
                    : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? EntryType.FILE : EntryType.OTHER;
            entries.add(new DirectoryEntry(entry.getKey(), type));
        }
        String next = !incomplete && more ? selected.lastKey() : null;
        return new DirectoryPage(entries, next, incomplete);
    }

    /**
     * 读取普通文件的有界内容；允许用户显式选择指向普通文件的符号链接。
     *
     * @param path 用户授权的本机绝对路径
     * @param offset 从文件起点计算的非负字节偏移
     * @param limit 本次最多返回的字节数
     * @return 文件字节及是否尚有未返回内容
     * @throws IOException 文件不可读或不是普通文件
     */
    FileContent read(Path path, long offset, int limit) throws IOException {
        requireAbsolute(path);
        requireLimit(limit);
        if (offset < 0) {
            throw new IllegalArgumentException("文件偏移无效");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("本地文件不存在、不可读或不是普通文件");
        }
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(limit + 1);
            input.position(offset);
            while (buffer.hasRemaining()) {
                if (input.read(buffer) <= 0) break;
            }
            byte[] received = Arrays.copyOf(buffer.array(), buffer.position());
            return new FileContent(Arrays.copyOf(received, Math.min(received.length, limit)), received.length > limit);
        }
    }

    /**
     * 创建新文件，不覆盖已经存在的文件或符号链接。
     *
     * @param path 用户授权的本机绝对路径
     * @param content 不超过固定上限的文件正文
     * @throws IOException 创建或写入失败；调用方不能假定失败前没有产生部分内容
     */
    void create(Path path, byte[] content) throws IOException {
        requireAbsolute(path);
        requireContent(content);
        Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /**
     * 覆盖已有普通文件，不创建不存在的目标。
     *
     * <p>本方法不会保护同时由其他程序修改的文件；失败也可能发生在写入开始之后，调用方
     * 必须记录结果未知并要求核对，不能对同一调用自动重放。</p>
     *
     * @param path 用户授权的本机绝对路径
     * @param content 不超过固定上限的完整新正文
     * @throws IOException 目标不可写或写入失败
     */
    void replace(Path path, byte[] content) throws IOException {
        requireAbsolute(path);
        requireContent(content);
        if (!Files.isRegularFile(path)) {
            throw new IOException("本地目标不是可替换的普通文件");
        }
        Files.write(path, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * 在期望长度处追加一个有界片段，以便分块写入超过单次上限的文件。
     *
     * <p>文件锁只协调同样遵守该锁的进程，不保证其他程序不会并发修改；写入中断后的状态
     * 必须由调用方核对，不能自动重放片段。</p>
     *
     * @param path 用户授权的本机绝对路径
     * @param expectedSize 调用者预期的当前文件长度，必须为非负数
     * @param content 不超过固定上限的片段
     * @throws IOException 目标不存在、长度变化或写入失败
     */
    void append(Path path, long expectedSize, byte[] content) throws IOException {
        requireAbsolute(path);
        requireContent(content);
        if (expectedSize < 0) {
            throw new IllegalArgumentException("文件预期长度无效");
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("本地目标不是可写入的普通文件");
        }
        try (FileChannel output = FileChannel.open(path, StandardOpenOption.WRITE);
             var ignored = output.lock()) {
            if (output.size() != expectedSize) {
                throw new IOException("本地文件长度已变化，请重新核对");
            }
            output.position(expectedSize);
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
        }
    }

    /**
     * 删除指定文件或空目录，不递归删除目录内容。
     *
     * @param path 用户授权的本机绝对路径
     * @throws IOException 删除失败或目标不存在
     */
    void delete(Path path) throws IOException {
        requireAbsolute(path);
        // 删除符号链接时删除链接本身，避免把对链接的单次确认扩大为删除目标。
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("本地目标不存在");
        }
        Files.delete(path);
    }

    private static void requireAbsolute(Path path) {
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException("本地路径必须是绝对路径");
        }
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_BYTES) {
            throw new IllegalArgumentException("文件读取上限无效");
        }
    }

    private static void requireContent(byte[] content) {
        Objects.requireNonNull(content, "文件正文不能为空");
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("文件正文超过单次操作上限");
        }
    }

    /**
     * 一次文件读取结果。
     *
     * @param bytes 本次读取内容，构造和访问时均复制
     * @param truncated 为 {@code true} 时表示还有内容未返回
     */
    record FileContent(byte[] bytes, boolean truncated) {
        FileContent {
            bytes = Objects.requireNonNull(bytes, "读取内容不能为空").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** 目录条目的本机类型；不读取符号链接目标。 */
    enum EntryType {
        /** 普通文件。 */ FILE,
        /** 目录。 */ DIRECTORY,
        /** 符号链接。 */ SYMLINK,
        /** 已消失、不可识别或其他类型的条目。 */ OTHER
    }

    /**
     * 一条目录元数据。
     *
     * @param name 本机目录中的原始名称，不应被当作可信 HTML
     * @param type 不跟随链接得到的条目类型
     */
    record DirectoryEntry(String name, EntryType type) { }

    /**
     * 一页目录扫描结果。
     *
     * @param entries 本次返回的不可变条目列表
     * @param nextName 下一页使用的排他名称游标；没有续页时为 {@code null}
     * @param incomplete 达到扫描预算、不能确定目录全貌时为 {@code true}
     */
    record DirectoryPage(List<DirectoryEntry> entries, String nextName, boolean incomplete) {
        DirectoryPage { entries = List.copyOf(entries); }
    }
}
