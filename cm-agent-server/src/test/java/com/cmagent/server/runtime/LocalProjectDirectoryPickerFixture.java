package com.cmagent.server.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 真实OS目录窗口的独立桌面验收入口，仅写调用方指定的测试结果，不注册项目或读取目录正文。
 * 普通测试不会启动窗口；人工/Computer Use须实际选择测试目录或取消，不能以注入返回值冒充通过。
 */
public final class LocalProjectDirectoryPickerFixture {
    private LocalProjectDirectoryPickerFixture() {}

    /**
     * @param args 结果文件、自建测试目录；结果只存选择与匹配布尔值，不记录绝对宿主路径
     */
    public static void main(String[] args) throws Exception {
        var result = new WindowsLocalProjectDirectoryPicker().choose();
        boolean matches = result.isPresent() && result.get().toRealPath().equals(Path.of(args[1]).toRealPath());
        Files.writeString(Path.of(args[0]), "{\"selected\":" + result.isPresent() + ",\"matchesExpected\":" + matches + "}");
    }
}

