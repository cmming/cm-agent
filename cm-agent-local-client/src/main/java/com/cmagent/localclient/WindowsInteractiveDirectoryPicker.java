package com.cmagent.localclient;

import java.nio.file.Path;
import java.util.Optional;

/** 在 Windows 交互桌面先核对来源及请求，再打开系统目录窗口。 */
final class WindowsInteractiveDirectoryPicker implements ClientDirectoryPicker {
    @Override public Optional<Path> choose(Request request) {
        var picker = new WindowsClientDirectoryPicker();
        return picker.confirm(request) ? picker.choose(request) : Optional.empty();
    }
}
