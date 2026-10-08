package com.cmagent.core.domain;

/** Docker daemon 的传输方式，SSH 不开放任意主机命令。 */
public enum SandboxConnectionMode {
    /** 部署者允许的本地 socket。 */ LOCAL,
    /** 通过校验 host key 的 SSH 连接 daemon。 */ SSH,
    /** 使用客户端证书且校验服务端身份的双向 TLS。 */ TLS
}
