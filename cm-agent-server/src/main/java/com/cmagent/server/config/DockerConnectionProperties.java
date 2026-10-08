package com.cmagent.server.config;

/**
 * 部署默认连接的文件引用；控制台不得提交这些宿主路径。
 * mode 为空只兼容历史环境，远程部署建议迁移为显式模式。
 */
public class DockerConnectionProperties {
    /** LOCAL、SSH或TLS，空值表示历史兼容。 */ private String mode = "";
    /** 默认远程主机。 */ private String host = "";
    /** 默认远程端口。 */ private int port;
    /** SSH 用户。 */ private String username = "";
    /** 私钥文件，仅部署者可配置。 */ private String privateKeyFile = "";
    /** SSH信任材料文件。 */ private String knownHostsFile = "";
    /** TLS信任CA文件。 */ private String caCertificateFile = "";
    /** TLS客户端证书链文件。 */ private String clientCertificateFile = "";
    /** SSH认证方式，旧部署默认KEY，不自动尝试其他认证。 */ private com.cmagent.core.domain.SandboxSshAuthType sshAuthType = com.cmagent.core.domain.SandboxSshAuthType.KEY;
    /** 部署Secret密码文件引用；服务端只读，不生成密码文件，也不传给CLI环境。 */ private String passwordFile = "";
    /** @return SSH认证类型 */ public com.cmagent.core.domain.SandboxSshAuthType getSshAuthType(){return sshAuthType;}
    /** @param v SSH认证类型 */ public void setSshAuthType(com.cmagent.core.domain.SandboxSshAuthType v){sshAuthType=v;}
    /** @return 可信密码文件引用 */ public String getPasswordFile(){return passwordFile;}
    /** @param v 可信密码文件引用 */ public void setPasswordFile(String v){passwordFile=v;}
    /** @return 连接模式 */ public String getMode(){return mode;}
    /** @param v 连接模式 */ public void setMode(String v){mode=v;}
    /** @return 主机 */ public String getHost(){return host;}
    /** @param v 主机 */ public void setHost(String v){host=v;}
    /** @return 端口 */ public int getPort(){return port;}
    /** @param v 端口 */ public void setPort(int v){port=v;}
    /** @return 用户 */ public String getUsername(){return username;}
    /** @param v 用户 */ public void setUsername(String v){username=v;}
    /** @return 私钥引用 */ public String getPrivateKeyFile(){return privateKeyFile;}
    /** @param v 私钥引用 */ public void setPrivateKeyFile(String v){privateKeyFile=v;}
    /** @return SSH信任引用 */ public String getKnownHostsFile(){return knownHostsFile;}
    /** @param v SSH信任引用 */ public void setKnownHostsFile(String v){knownHostsFile=v;}
    /** @return CA引用 */ public String getCaCertificateFile(){return caCertificateFile;}
    /** @param v CA引用 */ public void setCaCertificateFile(String v){caCertificateFile=v;}
    /** @return 证书引用 */ public String getClientCertificateFile(){return clientCertificateFile;}
    /** @param v 证书引用 */ public void setClientCertificateFile(String v){clientCertificateFile=v;}
}
