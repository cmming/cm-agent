package com.cmagent.server.runtime;

import com.cmagent.api.ApiErrorCode;
import com.cmagent.core.domain.SandboxConnectionMode;
import com.cmagent.core.runtime.SkillAccessException;
import com.cmagent.server.config.SkillProperties;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;

/** 沙箱专用目标策略：私网执行机须明确登记，不放宽业务 HTTP 工具的公网策略。 */
@Component
public class SandboxTargetPolicy {
    /** host:port 白名单来自部署，租户或模型不能修改。 */
    private final SkillProperties properties;
    /** @param properties 可信部署上限 */
    public SandboxTargetPolicy(SkillProperties properties) { this.properties=properties; }
    /**
     * 校验并固定实际目标地址，连接层必须使用返回值以防 DNS 重绑定。
     * @param mode 协议
     * @param host 部署允许的主机
     * @param port 端口
     * @param username SSH 用户
     * @return 固定目标，本地为空
     */
    public InetAddress validate(SandboxConnectionMode mode,String host,int port,String username) {
        if(mode==null || host==null || username==null)throw rejected();
        if(mode==SandboxConnectionMode.LOCAL) {
            if(!host.isEmpty() || port!=0 || !username.isEmpty()) throw rejected();
            return null;
        }
        if(host==null || !host.matches("[a-zA-Z0-9][a-zA-Z0-9.:-]{0,252}") || port<1 || port>65535
            || (mode==SandboxConnectionMode.SSH && (username==null || !username.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}")))
            || (mode==SandboxConnectionMode.TLS && !username.isEmpty())) throw rejected();
        String target=host.toLowerCase(Locale.ROOT)+":"+port;
        if(properties.getSandbox().getAllowedTargets().stream().noneMatch(t->t.equalsIgnoreCase(target))) throw rejected();
        try {
            InetAddress[] addresses=InetAddress.getAllByName(host);
            // 不允许任何解析结果指向元数据/组播/未指定地址；私网和测试回环只允许部署精确登记的目标。
            if(addresses.length==0 || Arrays.stream(addresses).anyMatch(a->a.isAnyLocalAddress() || a.isMulticastAddress()
                || a.isLinkLocalAddress())) throw rejected();
            return addresses[0];
        } catch(UnknownHostException failure) { throw rejected(); }
    }
    private static SkillAccessException rejected() {
        return new SkillAccessException(ApiErrorCode.SKILL_SANDBOX_TARGET_DENIED,"沙箱目标不在部署允许范围或连接参数不合法",UUID.randomUUID().toString(),true);
    }
}
