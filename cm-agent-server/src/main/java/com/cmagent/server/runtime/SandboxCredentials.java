package com.cmagent.server.runtime;

/**
 * 短期解密材料；任何诊断输出必须脱敏，禁止返回 API。
 * @param privateKey SSH 私钥或 TLS 客户端 PKCS8 私钥
 * @param knownHosts 已核实的 SSH known_hosts 文本
 * @param caCertificate TLS 信任 CA
 * @param clientCertificate TLS 客户端证书链
 * @param password SSH密码，只写且不得进入日志、CLI环境或生成文件；旧密文缺失时为空
 */
public record SandboxCredentials(String privateKey, String knownHosts, String caCertificate, String clientCertificate, String password) {
    public SandboxCredentials {
        privateKey = privateKey == null ? "" : privateKey;
        knownHosts = knownHosts == null ? "" : knownHosts;
        caCertificate = caCertificate == null ? "" : caCertificate;
        clientCertificate = clientCertificate == null ? "" : clientCertificate;
        password = password == null ? "" : password;
        // 密码空格与Unicode属于认证内容，不能trim；控制字符会造成输入语义歧义，入口直接拒绝。
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096
                || password.indexOf('\0') >= 0 || password.indexOf('\r') >= 0 || password.indexOf('\n') >= 0)
            throw new IllegalArgumentException("SSH密码超过4096字节或包含不允许的控制字符");
        if (java.util.stream.Stream.of(privateKey,knownHosts,caCertificate,clientCertificate,password)
                .mapToLong(value->value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum()>128*1024)
            throw new IllegalArgumentException("沙箱凭据材料超过上限");
    }
    /** 保留原私钥/TLS调用，Jackson缺失password时同样按空值处理。 */
    public SandboxCredentials(String privateKey,String knownHosts,String caCertificate,String clientCertificate) {
        this(privateKey,knownHosts,caCertificate,clientCertificate,"");
    }
    @Override public String toString() { return "SandboxCredentials[已脱敏]"; }
}
