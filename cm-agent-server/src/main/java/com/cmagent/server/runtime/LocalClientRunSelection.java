package com.cmagent.server.runtime;

import com.cmagent.core.security.OperationAuthorizationMode;
import com.cmagent.core.security.OperationCategory;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 浏览器为一次运行提交的设备与能力选择。
 *
 * <p>tenant、主体、Agent、会话和 Run 均不从该对象取得；服务端必须先创建并校验这些可信
 * 资源，再把选择固化为单轮授权。旧请求不携带该对象时保持没有本机客户端能力。</p>
 *
 * @param deviceId 当前用户已配对的活动设备
 * @param mode 本轮操作确认方式
 * @param categories 用户允许的本轮操作类别
 */
public record LocalClientRunSelection(UUID deviceId, OperationAuthorizationMode mode,
                                      Set<OperationCategory> categories) {
    public LocalClientRunSelection {
        Objects.requireNonNull(deviceId, "设备标识不能为空");
        Objects.requireNonNull(mode, "授权方式不能为空");
        categories = Set.copyOf(Objects.requireNonNull(categories, "操作类别不能为空"));
        if (categories.isEmpty() || categories.contains(OperationCategory.UNKNOWN)) {
            throw new IllegalArgumentException("本机操作类别选择无效");
        }
    }
}
