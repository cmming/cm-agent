package com.cmagent.server.runtime;

import java.io.IOException;

/** 项目文件原语的安全失败分类；最终服务边界分配同号errorId，不附加原始路径异常。 */
final class LocalProjectFileException extends IOException {
    /** 固定原因供上层映射状态/错误码，不携带文件正文或宿主路径。 */
    private final Reason reason;

    LocalProjectFileException(Reason reason) { super(reason.message); this.reason = reason; }
    Reason reason() { return reason; }

    /** 文件访问失败必须明确分类，不能将不可信路径或读取失败当作成功空文件。 */
    enum Reason {
        /** 相对路径或请求范围不合法。 */ INVALID_PATH("项目路径或读取范围不合法"),
        /** 普通文件/目录被移除，不伪装服务整体不可用。 */ NOT_FOUND("项目路径不存在或已被移除，请刷新列表"),
        /** 私有凭据、生成目录或非文本候选未获开放。 */ EXCLUDED("该文件属于默认排除范围或不支持的文本类型"),
        /** 已登记根丢失或被同路径替换。 */ ROOT_CHANGED("项目目录身份已变化，请重新核对项目"),
        /** 链接、设备或非普通文件不能成为正文来源。 */ UNSAFE_FILE("项目文件不是可读取的普通文本或包含链接"),
        /** 文件在读取过程中变化或名称被替换。 */ FILE_CHANGED("项目文件在读取期间发生变化，请重新核对"),
        /** 实际大小或当前请求预算超限。 */ LIMIT_EXCEEDED("项目文件或请求结果超过读取预算"),
        /** 文件正被其他程序持有写入权限。 */ FILE_BUSY("项目文件正被其他程序修改，请稍后核对"),
        /** 文件系统不允许当前进程读取。 */ ACCESS_DENIED("项目文件读取权限不足"),
        /** 编码或控制字符不满足纯文本约束。 */ INVALID_TEXT("项目文件不是受支持的UTF-8文本"),
        /** 请求的协作式执行期限已耗尽或被停止。 */ TIMEOUT("项目文件读取超时或已停止"),
        /** 原生/文件系统能力缺失或资源关闭失败。 */ UNAVAILABLE("项目文件服务暂不可用，请凭错误编号联系管理员");

        /** 可展示的固定中文说明，禁止拼接原始异常消息。 */
        private final String message;
        Reason(String message) { this.message = message; }
    }
}

