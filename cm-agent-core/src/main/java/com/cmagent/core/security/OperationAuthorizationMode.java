package com.cmagent.core.security;

/**
 * 用户为当前运行选择的操作确认方式。
 *
 * <p>该方式只决定已授权操作是否需要人工确认，不授予租户、设备、工具或操作系统权限。</p>
 */
public enum OperationAuthorizationMode {
    /** 文件修改、联网、命令和其他未知副作用在执行前询问。 */
    ASK_ON_EFFECT,
    /** 服务端识别为高风险或无法判定的操作在执行前询问。 */
    ASK_ON_RISK,
    /** 在本轮有效授权范围内执行已识别的操作，仍遵守更严格的服务端策略。 */
    FULL_ACCESS
}
