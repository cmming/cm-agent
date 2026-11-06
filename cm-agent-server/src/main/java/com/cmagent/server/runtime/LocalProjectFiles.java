package com.cmagent.server.runtime;

import com.cmagent.core.runtime.LocalProjectPathPolicy;
import com.sun.jna.Platform;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static com.cmagent.server.runtime.LocalProjectFileException.Reason.*;

/**
 * 本地项目有界文件原语，不注册HTTP/模型入口，不代替项目owner、权限与节点校验。
 *
 * <p>调用方只能传受信本机根，不能把客户端路径包装成Root。普通开发源码目录可作为项目，
 * 不复用旧参考资料的私有ACL策略。Windows正文经同句柄最终路径校验；POSIX要求SecureDirectoryStream。
 * 这些边界不是OS沙箱，不承诺消除同一OS账号任意修改所形成的全部文件系统竞态。</p>
 *
 * <p>正文只交受信服务的有界对象，toString不输出正文；宿主适配层负责内容脱敏、
 * 审计、运行版本冻结和摘要分离，不能把本类返回值直接作为Controller响应。</p>
 */
final class LocalProjectFiles {
    /** 单文件物理读取硬界；每次可交付范围另限64KiB。 */
    private static final int MAX_FILE = 1_048_576, MAX_RESULT = 65_536;
    /** 目录枚举硬界，超过不返回截断后伪装完整的列表。 */
    private static final int MAX_ENTRIES = 10_000;
    /** 默认读取期限为协作预算，不能中断内核中的同步I/O。 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** 应用明确登记的运行私有目录，不能被选为项目或从父项目进入。 */
    private final List<Path> privateRoots;

    LocalProjectFiles(List<Path> privateRoots) {
        this.privateRoots = privateRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    /** 仅供受信变更计划捕获完整旧版本；调用方须及时擦除返回数组，禁止直接作Web响应。 */
    byte[] mutationSnapshot(LocalProjectDirectoryTickets.Root root, String relative) throws IOException {
        return readAll(root, relative, System.nanoTime() + READ_TIMEOUT.toNanos());
    }

    /** 新增目标只做元数据检查，不创建隐式父目录；创建本身须由高风险执行器原子完成。 */
    boolean absent(LocalProjectDirectoryTickets.Root root, String relative) throws IOException {
        require(relative, false);
        if (LocalProjectPathPolicy.excluded(relative)) throw new LocalProjectFileException(EXCLUDED);
        int separator = relative.lastIndexOf('/');
        String parent = separator < 0 ? "" : relative.substring(0, separator);
        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        Path directory = checked(root, parent, true, deadline);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new LocalProjectFileException(UNSAFE_FILE);
        Path target = directory.resolve(separator < 0 ? relative : relative.substring(separator + 1));
        if (privateRoots.stream().anyMatch(target::startsWith)) throw new LocalProjectFileException(EXCLUDED);
        try {
            attributes(target);
            return false;
        } catch (NoSuchFileException missing) {
            verifyRoot(root, deadline);
            return true;
        } catch (AccessDeniedException failure) { throw new LocalProjectFileException(ACCESS_DENIED); }
        catch (IOException | RuntimeException failure) { throw new LocalProjectFileException(UNAVAILABLE); }
    }

    /** 空目录变更冻结对象身份而非仅用空正文hash；父目录必须存在，不能隐式扩大写入范围。 */
    String emptyDirectoryVersion(LocalProjectDirectoryTickets.Root root, String relative) throws IOException {
        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        Path directory = checked(root, relative, false, deadline);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new LocalProjectFileException(UNSAFE_FILE);
        Page page = listWithin(root, relative, null, 1, deadline);
        if (!page.items().isEmpty()) throw new LocalProjectFileException(UNSAFE_FILE);
        String identity = directoryIdentity(directory);
        verifyRoot(root, deadline);
        return digest(identity.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从完整受控文件版本取UTF-8字节范围；不会切断码点，nextOffset可继续长行。
     * @param root 受信已登记根，不来自HTTP路径
     * @param relative 无歧义根内文件路径
     * @param offset UTF-8码点边界上的字节偏移
     * @param maxBytes 本次返回上限，1至64KiB
     * @return 完整文件摘要及实际范围；hasMore明确说明尚有内容
     */
    Read read(LocalProjectDirectoryTickets.Root root, String relative, int offset, int maxBytes) throws IOException {
        if (offset < 0 || maxBytes < 1 || maxBytes > MAX_RESULT) throw new LocalProjectFileException(INVALID_PATH);
        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        byte[] bytes = null;
        try {
            bytes = readAll(root, relative, deadline);
            if (offset > bytes.length || offset < bytes.length && continuation(bytes[offset])) throw new LocalProjectFileException(INVALID_PATH);
            int end = Math.min(bytes.length, offset + maxBytes);
            while (end > offset && end < bytes.length && continuation(bytes[end])) end--;
            if (end == offset && offset < bytes.length) throw new LocalProjectFileException(LIMIT_EXCEEDED);
            String text = new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
            return new Read(relative, digest(bytes), bytes.length, offset, end, end < bytes.length, text);
        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    /**
     * 从指定行开始读取，仍以nextOffset精确表示长行被字节预算分段的位置。
     * @param root 受信项目根
     * @param relative 根内文本
     * @param firstLine 从1开始；空文件只接受1
     * @param maxBytes 本次返回字节上限
     * @return 与字节读取相同的明确范围，不把截断长行当成完整行
     */
    Read readLines(LocalProjectDirectoryTickets.Root root, String relative, int firstLine, int maxBytes) throws IOException {
        if (firstLine < 1 || maxBytes < 1 || maxBytes > MAX_RESULT) throw new LocalProjectFileException(INVALID_PATH);
        byte[] bytes = readAll(root, relative, System.nanoTime() + READ_TIMEOUT.toNanos());
        try {
            int offset = 0, line = 1;
            while (line < firstLine && offset < bytes.length) if (bytes[offset++] == '\n') line++;
            if (line != firstLine || offset == bytes.length && firstLine > 1) throw new LocalProjectFileException(INVALID_PATH);
            int end = Math.min(bytes.length, offset + maxBytes);
            while (end > offset && end < bytes.length && continuation(bytes[end])) end--;
            if (end == offset && offset < bytes.length) throw new LocalProjectFileException(LIMIT_EXCEEDED);
            return new Read(relative, digest(bytes), bytes.length, offset, end, end < bytes.length,
                    new String(bytes, offset, end - offset, StandardCharsets.UTF_8));
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    /**
     * 分页列举单个目录，不扫描子树；敏感/不支持名称只显示阻止状态，不能当成可读文件。
     * @param root 受信项目根
     * @param relative 空字符串表示根，其他为根内目录
     * @param afterName 上页不透明游标，首页为空；仅决定本目录排序位置，不参与文件寻址
     * @param limit 本页1至200条
     */
    Page list(LocalProjectDirectoryTickets.Root root, String relative, String afterName, int limit) throws IOException {
        return listWithin(root, relative, afterName, limit, System.nanoTime() + READ_TIMEOUT.toNanos());
    }

    private Page listWithin(LocalProjectDirectoryTickets.Root root, String relative, String afterName, int limit, long deadline) throws IOException {
        if (limit < 1 || limit > 200) throw new LocalProjectFileException(INVALID_PATH);
        String after = afterName == null ? null : cursorName(afterName);
        try {
            Path directory = checked(root, relative, true, deadline);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new LocalProjectFileException(UNSAFE_FILE);
            String before = directoryIdentity(directory);
            var entries = new ArrayList<Listed>();
            try (var stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    budget(deadline);
                    if (entries.size() >= MAX_ENTRIES) throw new LocalProjectFileException(LIMIT_EXCEEDED);
                    String name = child.getFileName().toString();
                    String path = relative.isEmpty() ? name : relative + "/" + name;
                    BasicFileAttributes attributes = attributes(child);
                    boolean valid = valid(path);
                    boolean excluded = !valid || LocalProjectPathPolicy.excluded(path)
                            || privateRoots.stream().anyMatch(child.toAbsolutePath().normalize()::startsWith);
                    Kind kind = excluded || attributes.isSymbolicLink() || attributes.isOther() ? Kind.BLOCKED
                            : attributes.isDirectory() ? Kind.DIRECTORY : attributes.isRegularFile() ? Kind.FILE : Kind.BLOCKED;
                    entries.add(new Listed(name, new Entry(label(name), valid ? path : "", kind, attributes.size(),
                            kind == Kind.FILE && LocalProjectPathPolicy.textCandidate(path))));
                }
            }
            if (!before.equals(directoryIdentity(directory))) throw new LocalProjectFileException(FILE_CHANGED);
            verifyRoot(root, deadline);
            entries.sort(Comparator.comparing(Listed::rawName));
            var selected = entries.stream().filter(entry -> after == null || entry.rawName().compareTo(after) > 0).limit(limit).toList();
            String cursor = selected.size() == limit && entries.stream().anyMatch(entry -> entry.rawName().compareTo(selected.getLast().rawName()) > 0)
                    ? Base64.getUrlEncoder().withoutPadding().encodeToString(selected.getLast().rawName().getBytes(StandardCharsets.UTF_8)) : null;
            return new Page(selected.stream().map(Listed::view).toList(), cursor);
        } catch (LocalProjectFileException failure) { throw failure; }
        catch (NoSuchFileException failure) { throw new LocalProjectFileException(NOT_FOUND); }
        catch (AccessDeniedException failure) { throw new LocalProjectFileException(ACCESS_DENIED); }
        catch (IOException | RuntimeException failure) { throw new LocalProjectFileException(UNAVAILABLE); }
    }

    /**
     * 有界路径/正文纯文本搜索，不调用shell或执行正则；返回部分结果时显式标记partial。
     * @param root 受信项目根
     * @param query 1至256字符的字面量
     * @param content 是否检索文本正文；false只匹配相对路径
     * @return 最多100个命中，最多扫描10000项/8MiB正文/5秒；不可读文件不伪装已覆盖
     */
    Search search(LocalProjectDirectoryTickets.Root root, String query, boolean content) throws IOException {
        return search(root, query, content, java.util.function.UnaryOperator.identity());
    }

    /** 宿主在全文上执行脱敏后再检索/截取片段，避免命中和片段跨界泄露秘密字段。 */
    Search search(LocalProjectDirectoryTickets.Root root, String query, boolean content, java.util.function.UnaryOperator<String> textFilter) throws IOException {
        if (query == null || query.isBlank() || query.length() > 256) throw new LocalProjectFileException(INVALID_PATH);
        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        var pending = new ArrayDeque<String>();
        pending.add("");
        var matches = new ArrayList<Match>();
        int scanned = 0, skipped = 0, consumed = 0;
        boolean partial = false;
        outer: while (!pending.isEmpty()) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) { partial = true; break; }
            String relative = pending.removeFirst();
            Page page;
            String cursor = null;
            do {
                try { page = listWithin(root, relative, cursor, 200, deadline); }
                catch (LocalProjectFileException failure) {
                    if (failure.reason() == TIMEOUT || failure.reason() == LIMIT_EXCEEDED) { partial = true; break outer; }
                    throw failure;
                }
                for (Entry entry : page.items()) {
                    if (++scanned > MAX_ENTRIES) { partial = true; break outer; }
                    if (entry.kind() == Kind.BLOCKED) { skipped++; continue; }
                    if (entry.kind() == Kind.DIRECTORY) {
                        if (entry.path().split("/").length < 32) pending.addLast(entry.path());
                        else { skipped++; partial = true; }
                    }
                    if (entry.path().contains(query)) matches.add(new Match(entry.path(), false, ""));
                    else if (content && entry.readable()) {
                        if (entry.sizeBytes() > MAX_FILE || entry.sizeBytes() > 8_388_608 - consumed) { skipped++; partial = true; continue; }
                        byte[] bytes = null;
                        try {
                            bytes = readAll(root, entry.path(), deadline, Math.min(MAX_FILE, 8_388_608 - consumed));
                            consumed += bytes.length;
                            String text = textFilter.apply(new String(bytes, StandardCharsets.UTF_8));
                            int index = text.indexOf(query);
                            if (index >= 0) matches.add(new Match(entry.path(), true,
                                    text.substring(Math.max(0, index - 40), Math.min(text.length(), index + query.length() + 40))));
                        } catch (LocalProjectFileException failure) {
                            if (failure.reason() == ROOT_CHANGED) throw failure;
                            if (failure.reason() == TIMEOUT) { partial = true; break outer; }
                            skipped++; partial = true;
                        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
                    }
                    if (matches.size() >= 100) { partial = true; break outer; }
                }
                cursor = page.nextCursor();
            } while (cursor != null);
        }
        verifyRoot(root, System.nanoTime() + Duration.ofSeconds(1).toNanos());
        return new Search(matches, partial, scanned, skipped);
    }

    private byte[] readAll(LocalProjectDirectoryTickets.Root root, String relative, long deadline) throws IOException {
        return readAll(root, relative, deadline, MAX_FILE);
    }

    private byte[] readAll(LocalProjectDirectoryTickets.Root root, String relative, long deadline, int physicalLimit) throws IOException {
        if (physicalLimit < 1 || physicalLimit > MAX_FILE) throw new LocalProjectFileException(LIMIT_EXCEEDED);
        require(relative, false);
        if (!LocalProjectPathPolicy.textCandidate(relative)) throw new LocalProjectFileException(EXCLUDED);
        byte[] bytes = null;
        try {
            Path file = checked(root, relative, false, deadline);
            if (!attributes(file).isRegularFile()) throw new LocalProjectFileException(UNSAFE_FILE);
            if (Platform.isWindows()) bytes = new WindowsLocalProjectTextReader().read(file, physicalLimit, deadline);
            else {
                // POSIX显式拒绝普通硬链接；安全目录句柄另负责路径归属，不能用字符串前缀替代。
                if (((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
                    throw new LocalProjectFileException(UNSAFE_FILE);
                bytes = readSecure(root, relative, deadline, physicalLimit);
                if (((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
                    throw new LocalProjectFileException(FILE_CHANGED);
            }
            var decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            if (decoded.codePoints().anyMatch(value -> Character.isISOControl(value) && value != 9 && value != 10 && value != 13))
                throw new LocalProjectFileException(INVALID_TEXT);
            verifyRoot(root, deadline);
            byte[] result = bytes;
            bytes = null;
            return result;
        } catch (LocalProjectFileException failure) { throw failure; }
        catch (java.nio.charset.CharacterCodingException failure) { throw new LocalProjectFileException(INVALID_TEXT); }
        catch (NoSuchFileException failure) { throw new LocalProjectFileException(NOT_FOUND); }
        catch (AccessDeniedException failure) { throw new LocalProjectFileException(ACCESS_DENIED); }
        catch (IOException | RuntimeException failure) { throw new LocalProjectFileException(UNAVAILABLE); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    private Path checked(LocalProjectDirectoryTickets.Root root, String relative, boolean allowRoot, long deadline) throws IOException {
        require(relative, allowRoot);
        if (!relative.isEmpty() && LocalProjectPathPolicy.excluded(relative)) throw new LocalProjectFileException(EXCLUDED);
        verifyRoot(root, deadline);
        Path selected = root.directory();
        if (relative.isEmpty()) return selected;
        String[] parts = relative.split("/");
        for (int i = 0; i < parts.length; i++) {
            selected = selected.resolve(parts[i]);
            if (privateRoots.stream().anyMatch(selected::startsWith)) throw new LocalProjectFileException(EXCLUDED);
            var attributes = attributes(selected);
            if (attributes.isSymbolicLink() || attributes.isOther() || i < parts.length - 1 && !attributes.isDirectory()
                    || !selected.toRealPath().equals(selected.toAbsolutePath().normalize()))
                throw new LocalProjectFileException(UNSAFE_FILE);
            budget(deadline);
        }
        return selected;
    }

    private void verifyRoot(LocalProjectDirectoryTickets.Root root, long deadline) throws IOException {
        budget(deadline);
        Path directory = root.directory();
        if (!directory.isAbsolute() || directory.getParent() == null || directory.toString().startsWith("\\\\")
                || directory.equals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
                || privateRoots.stream().anyMatch(directory::startsWith))
            throw new LocalProjectFileException(EXCLUDED);
        for (Path component : directory)
            if (LocalProjectPathPolicy.privateComponent(component.toString())) throw new LocalProjectFileException(EXCLUDED);
        if (directory.startsWith(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()))
            throw new LocalProjectFileException(EXCLUDED);
        String system = System.getenv("SystemRoot");
        if (Platform.isWindows() && system != null && directory.startsWith(Path.of(system)))
            throw new LocalProjectFileException(EXCLUDED);
        if (Platform.isWindows()) WindowsLocalProjectTextReader.requireLocalDrive(directory);
        try {
            if (!attributes(directory).isDirectory() || !directory.equals(directory.toRealPath())
                    || !root.identity().equals(directoryIdentity(directory))) throw new LocalProjectFileException(ROOT_CHANGED);
            String type = Files.getFileStore(directory).type().toLowerCase(Locale.ROOT);
            if (Set.of("nfs", "nfs4", "cifs", "smbfs", "fuse.sshfs").contains(type)) throw new LocalProjectFileException(EXCLUDED);
        } catch (LocalProjectFileException failure) { throw failure; }
        catch (IOException | RuntimeException failure) { throw new LocalProjectFileException(ROOT_CHANGED); }
        budget(deadline);
    }

    private static String directoryIdentity(Path directory) throws IOException {
        if (Platform.isWindows()) return WindowsLocalProjectDirectoryIdentity.read(directory);
        var value = attributes(directory);
        if (value.fileKey() == null || !value.isDirectory() || value.isSymbolicLink()) throw new LocalProjectFileException(ROOT_CHANGED);
        return value.fileKey().toString();
    }

    /** POSIX必须以已打开的安全目录逐层定位，不退化为字符串前缀检查后直接打开。 */
    private static byte[] readSecure(LocalProjectDirectoryTickets.Root root, String relative, long deadline, int limit) throws IOException {
        try (var stream = Files.newDirectoryStream(root.directory())) {
            if (!(stream instanceof SecureDirectoryStream<Path> secure)) throw new LocalProjectFileException(UNAVAILABLE);
            var opened = secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
            if (opened.fileKey() == null || !opened.fileKey().toString().equals(root.identity())) throw new LocalProjectFileException(ROOT_CHANGED);
            return readSecure(secure, List.of(relative.split("/")), 0, deadline, limit);
        }
    }

    private static byte[] readSecure(SecureDirectoryStream<Path> directory, List<String> parts, int index, long deadline, int limit) throws IOException {
        budget(deadline);
        Path name = Path.of(parts.get(index));
        if (index < parts.size() - 1) {
            try (var next = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) { return readSecure(next, parts, index + 1, deadline, limit); }
        }
        var view = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        var before = view.readAttributes();
        if (!before.isRegularFile() || before.isSymbolicLink()) throw new LocalProjectFileException(UNSAFE_FILE);
        if (before.size() > limit) throw new LocalProjectFileException(LIMIT_EXCEEDED);
        try (var channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            var output = new ByteArrayOutputStream();
            var buffer = ByteBuffer.allocate(4096);
            while (true) {
                budget(deadline);
                buffer.limit(Math.min(buffer.capacity(), limit - output.size() + 1));
                int count = channel.read(buffer);
                if (count < 0) break;
                if (count > limit - output.size()) throw new LocalProjectFileException(LIMIT_EXCEEDED);
                output.write(buffer.array(), 0, count); buffer.clear();
            }
            var after = view.readAttributes();
            if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new LocalProjectFileException(FILE_CHANGED);
            return output.toByteArray();
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
    /** 排序游标只承载叶子名称，不用于路径解析；其他目录的游标最多跳过本目录已有条目。 */
    private static String cursorName(String cursor) throws LocalProjectFileException {
        try {
            if (cursor.length() > 2048) throw new IllegalArgumentException();
            String result = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor))).toString();
            if (result.isEmpty() || result.length() > 255 || result.indexOf('/') >= 0) throw new IllegalArgumentException();
            return result;
        } catch (java.nio.charset.CharacterCodingException | IllegalArgumentException failure) {
            throw new LocalProjectFileException(INVALID_PATH);
        }
    }
    private static String label(String name) {
        var value = new StringBuilder();
        name.codePoints().forEach(point -> {
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT)
                value.append(String.format("\\u%04x", point));
            else value.appendCodePoint(point);
        });
        return value.toString();
    }
    private static void require(String path, boolean root) throws LocalProjectFileException {
        try { LocalProjectPathPolicy.requireRelative(path, root); }
        catch (IllegalArgumentException failure) { throw new LocalProjectFileException(INVALID_PATH); }
    }
    private static boolean valid(String path) {
        try { LocalProjectPathPolicy.requireRelative(path, false); return true; }
        catch (IllegalArgumentException failure) { return false; }
    }
    private static boolean continuation(byte value) { return (value & 0xc0) == 0x80; }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("文件摘要能力不可用"); }
    }
    private static void budget(long deadline) throws LocalProjectFileException { WindowsLocalProjectTextReader.budget(deadline); }

    /** 目录项类型，不把链接或生成目录伪装成正常可打开文件。 */
    enum Kind {
        /** 可以进一步列举的普通目录。 */ DIRECTORY,
        /** 普通文件，是否可读还需类型及字节核验。 */ FILE,
        /** 默认排除、不可信名称或链接等不可进入项目正文的对象。 */ BLOCKED
    }
    /**
     * @param relativePath 根内文件路径
     * @param sha256 完整读取版本摘要，范围截断不改变摘要
     * @param totalBytes 完整文件字节数
     * @param offset 本段起始字节
     * @param nextOffset 下段起点，始终位于UTF-8码点边界
     * @param hasMore 是否尚有正文，不能作为完整文件展示
     * @param content 只交受信服务的正文，不能写普通运行摘要或日志
     */
    record Read(String relativePath, String sha256, int totalBytes, int offset, int nextOffset, boolean hasMore, String content) {
        @Override public String toString() { return "Read[受保护的项目正文]"; }
    }
    /**
     * @param name 不可见字符已转义的显示名称，UI仍须使用纯文本
     * @param path 可作为相对定位的路径，不可信名称为空且kind为BLOCKED
     * @param kind 目录/文件/阻止状态
     * @param sizeBytes 当前元数据大小，不是已读取正文的证明
     * @param readable 是否为文本候选，不授予访问权限
     */
    record Entry(String name, String path, Kind kind, long sizeBytes, boolean readable) {}
    /**
     * @param items 本页有界元数据
     * @param nextCursor 不透明下一页游标，完整结束为空
     */
    record Page(List<Entry> items, String nextCursor) { Page { items = List.copyOf(items); } }
    /**
     * @param rawName 仅当前调用内部排序使用的原始名称，不能交付UI或模型
     * @param view 名称已处理的安全元数据
     */
    private record Listed(String rawName, Entry view) {}
    /**
     * @param relativePath 命中文件的根内位置
     * @param contentMatch 是否来自正文
     * @param excerpt 有界正文片段，仅交受信服务，不能直接复制到普通摘要
     */
    record Match(String relativePath, boolean contentMatch, String excerpt) {
        @Override public String toString() { return "Match[受保护的搜索命中]"; }
    }
    /**
     * @param matches 有界命中，正文与路径访问都仍需授权
     * @param partial 是否因预算或不可读未覆盖可搜范围，默认排除项只计入skipped
     * @param scanned 已检查的条目数
     * @param skipped 默认阻止或不可读条目数
     */
    record Search(List<Match> matches, boolean partial, int scanned, int skipped) {
        Search { matches = List.copyOf(matches); }
        @Override public String toString() { return "Search[受保护的搜索结果]"; }
    }
}

