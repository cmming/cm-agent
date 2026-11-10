package com.cmagent.localclient;

import java.nio.file.Path;
import java.util.Optional;

/** 由目标设备当前用户确认项目根的交互选择器。 */
interface ClientDirectoryPicker {
    /**
     * 在交互桌面显示目录选择窗口。
     *
     * @param deviceName 当前设备展示名，供用户核对操作位置
     * @return 用户确认的目录；取消时为空
     */
    Optional<Path> choose(String deviceName);
}
