package com.cmagent.localclient;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 由目标设备当前用户确认项目根的交互选择器。 */
interface ClientDirectoryPicker {
    /**
     * 在交互桌面显示目录选择窗口。
     *
     * @param request 已配对服务端来源、设备名、任务标识与服务端截止时间
     * @return 用户确认的目录；取消时为空
     */
    Optional<Path> choose(Request request);

    /**
     * 选择请求的可信展示上下文。
     * @param deviceName 本机配对时记录的设备名
     * @param serverOrigin 本机配对时记录的服务端来源
     * @param taskId 服务端派发的一次性任务编号，供页面和本机核对
     * @param expiresAt 服务端持久化的任务截止时间
     */
    record Request(String deviceName, String serverOrigin, UUID taskId, Instant expiresAt) { }
}
