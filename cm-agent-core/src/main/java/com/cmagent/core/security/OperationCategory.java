package com.cmagent.core.security;

/**
 * 由可信服务端目录确认的操作类别。
 *
 * <p>模型、普通请求和工具名称不能证明操作的副作用；无法确认时必须使用 {@link #UNKNOWN}。</p>
 */
public enum OperationCategory {
    /** 已验证不产生外部副作用的读取或查询。 */
    READ_ONLY,
    /** 创建、修改、移动或删除文件及目录。 */
    FILE_CHANGE,
    /** 由受控网络执行器向外部目标发起的请求。 */
    NETWORK,
    /** 启动可能自行访问文件、网络和其他程序的本机命令。 */
    COMMAND,
    /** 未登记、无法确定或与可信目录冲突的操作。 */
    UNKNOWN
}
