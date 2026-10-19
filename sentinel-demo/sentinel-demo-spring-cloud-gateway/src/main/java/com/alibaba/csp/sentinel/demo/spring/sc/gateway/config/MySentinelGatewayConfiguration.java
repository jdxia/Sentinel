/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.demo.spring.sc.gateway.config;

import com.alibaba.cloud.sentinel.gateway.ConfigConstants;
import com.alibaba.cloud.sentinel.gateway.scg.SentinelGatewayProperties;
import com.alibaba.cloud.sentinel.gateway.scg.SentinelSCGAutoConfiguration;
import com.alibaba.csp.sentinel.adapter.gateway.common.param.ConfigurableRequestItemParser;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.ServerWebExchangeItemParser;
import io.netty.util.NetUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;

/**
 * 为网关限流提供代理转发的客户端 IP，缺少有效代理头时使用连接地址。
 */
@Configuration(proxyBeanMethods = false)
public class MySentinelGatewayConfiguration {

    private static final String[] CLIENT_IP_HEADERS = {
        "X-Forwarded-For", "Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_CLIENT_IP", "HTTP_X_FORWARDED_FOR"
    };

    /**
     * 目的是把 spring cloud alibaba的这个替换掉 {@link SentinelSCGAutoConfiguration#sentinelGatewayFilter()}
     */
    @Bean
    @ConditionalOnProperty(prefix = ConfigConstants.GATEWAY_PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = true)
    public SentinelGatewayFilter sentinelGatewayFilter(SentinelGatewayProperties properties) {
        ConfigurableRequestItemParser<ServerWebExchange> parser =
            new ConfigurableRequestItemParser<>(new ServerWebExchangeItemParser());
        // 仅适用于可信代理已清理或重写这些头的场景，避免客户端伪造 IP 绕过限流。
        parser.addRemoteAddressExtractor(exchange -> {
            for (String header : CLIENT_IP_HEADERS) {
                String value = exchange.getRequest().getHeaders().getFirst(header);
                if (value == null) {
                    continue;
                }
                for (String candidate : value.split(",")) {
                    String ip = candidate.trim();
                    if (NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip)) {
                        return ip;
                    }
                }
            }
            // 返回 null 交给默认解析器读取连接地址，保留本机回环地址的原始含义。
            return null;
        });
        return new SentinelGatewayFilter(properties.getOrder(), parser);
    }
}
