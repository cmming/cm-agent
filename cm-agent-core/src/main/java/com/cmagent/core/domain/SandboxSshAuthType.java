package com.cmagent.core.domain;

/** SSH身份认证方式，与SSH/TLS连接协议分开保存；旧端点默认使用私钥。 */
public enum SandboxSshAuthType {
    /** 使用明确配置的私钥，不读取用户SSH agent。 */
    KEY,
    /** 使用远程主机账号密码，仅尝试一次password认证，不回退私钥或交互认证。 */
    PASSWORD
}
