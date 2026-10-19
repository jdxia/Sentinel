/*
 * Copyright 1999-2026 Alibaba Group Holding Ltd.
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

import java.util.Collections;
import java.util.concurrent.TimeoutException;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.putUriTemplateVariables;

/**
 * 将 Sentinel 熔断器接入 Gateway 的 CircuitBreaker，转发、路径变量和请求体缓存复用父类。
 *
 * 这种也有不好的地方, 降级给原来的服务, 但是不是原来的服务也不知道降级怎么处理
 * 或者filter不配置这个也可以, 就走默认了
 *
 * 默认行为是：请求被 Sentinel 限流或熔断拒绝后，网关直接返回 HTTP 429 和错误信息
 */
@Component
public class CircuitBreakerSentinelFilterFactory extends SpringCloudCircuitBreakerFilterFactory {

    private static final String ORIGINAL_PATH_VARIABLE = "sentinelOriginalPath";

    private final String defaultFallbackUri;

    public CircuitBreakerSentinelFilterFactory(
            @Qualifier("reactiveSentinelCircuitBreakerFactory") ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory,
            ObjectProvider<DispatcherHandler> dispatcherHandlerProvider,
            @Value("${demo.gateway.default-fallback-uri:forward:/{sentinelOriginalPath}/fallback}") String defaultFallbackUri) {
        super(circuitBreakerFactory, dispatcherHandlerProvider);
        this.defaultFallbackUri = defaultFallbackUri;
    }

    @Override
    public GatewayFilter apply(Config config) {
        // 路由专属地址优先；在父类装配前补默认值，使默认降级也能启用请求体缓存。
        if (config.getFallbackUri() == null && StringUtils.hasText(defaultFallbackUri)) {
            config.setFallbackUri(UriComponentsBuilder.fromUriString(defaultFallbackUri).build().toUri());
        }
        GatewayFilter delegate = super.apply(config);
        return (exchange, chain) -> {
            // 官方内部转发带有异常属性；降级请求不能再次被同一熔断器拒绝或重复降级。
            if (exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR) != null) {
                return chain.filter(exchange);
            }
            // 在 RewritePath 前保存原路径，重新路由时仍可匹配原服务；保留路径中的百分号编码。
            String path = exchange.getRequest().getURI().getRawPath();
            putUriTemplateVariables(exchange, Collections.singletonMap(ORIGINAL_PATH_VARIABLE, path.substring(1)));
            return delegate.filter(exchange, chain);
        };
    }

    @Override
    protected Mono<Void> handleErrorWithoutFallback(Throwable throwable, boolean resumeWithoutError) {
        // 即使允许忽略普通异常，超时和熔断拒绝也不能被返回为成功。
        if (throwable instanceof TimeoutException) {
            return Mono.error(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "服务调用超时", throwable));
        }
        if (throwable instanceof DegradeException) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "服务已熔断", throwable));
        }
        return resumeWithoutError ? Mono.empty() : Mono.error(throwable);
    }
}
