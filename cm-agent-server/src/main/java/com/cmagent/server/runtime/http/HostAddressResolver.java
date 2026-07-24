package com.cmagent.server.runtime.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@FunctionalInterface
/** 抽象 DNS 解析，以便 HTTP 目标地址策略可测试并防范解析结果漂移。 */
public interface HostAddressResolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;
}
