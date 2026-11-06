package com.cmagent.localclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileOperationsTest {
    @TempDir Path directory;
    private final LocalFileOperations operations = new LocalFileOperations();

    @Test
    void 在用户指定的绝对路径创建读取替换和删除文件() throws Exception {
        Path file = directory.resolve("示例.txt");
        operations.create(file, "旧内容".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(operations.read(file, 0, 100).bytes(), StandardCharsets.UTF_8)).isEqualTo("旧内容");
        assertThatThrownBy(() -> operations.create(file, new byte[]{1})).isInstanceOf(java.io.IOException.class);

        operations.replace(file, "新内容".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readString(file)).isEqualTo("新内容");
        operations.delete(file);
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void 读取有界且返回内容不允许通过数组引用修改() throws Exception {
        Path file = directory.resolve("内容.bin");
        Files.write(file, new byte[]{1, 2, 3});
        var result = operations.read(file, 0, 2);
        byte[] copy = result.bytes();
        copy[0] = 9;

        assertThat(result.truncated()).isTrue();
        assertThat(result.bytes()).containsExactly(1, 2);
        assertThat(operations.read(file, 0, 3).truncated()).isFalse();
        assertThat(operations.read(file, 2, 2).bytes()).containsExactly(3);
    }

    @Test
    void 分块追加要求预期长度且冲突时不写入() throws Exception {
        Path file = directory.resolve("分块.bin");
        operations.create(file, new byte[]{1, 2});
        operations.append(file, 2, new byte[]{3, 4});
        assertThatThrownBy(() -> operations.append(file, 2, new byte[]{5}))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3, 4);
    }

    @Test
    void 目录页按名称分页且不把目录误报为文件() throws Exception {
        Files.write(directory.resolve("b.txt"), new byte[]{1});
        Files.createDirectory(directory.resolve("a-dir"));
        Files.write(directory.resolve("c.txt"), new byte[]{2});

        var first = operations.list(directory, null, 2);
        assertThat(first.entries()).extracting(LocalFileOperations.DirectoryEntry::name)
                .containsExactly("a-dir", "b.txt");
        assertThat(first.entries().getFirst().type()).isEqualTo(LocalFileOperations.EntryType.DIRECTORY);
        assertThat(first.nextName()).isEqualTo("b.txt");
        assertThat(first.incomplete()).isFalse();
        var second = operations.list(directory, first.nextName(), 2);
        assertThat(second.entries()).extracting(LocalFileOperations.DirectoryEntry::name).containsExactly("c.txt");
        assertThat(second.nextName()).isNull();
    }

    @Test
    void 拒绝相对路径无效上限和隐式创建替换目标() {
        assertThatThrownBy(() -> operations.read(Path.of("relative"), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.read(directory.resolve("missing"), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.replace(directory.resolve("missing"), new byte[0]))
                .isInstanceOf(java.io.IOException.class);
    }
}
