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
package com.alibaba.csp.sentinel.demo.spring.sc.gateway.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;

/**
 * 在网关本地结束降级请求，避免再次进入故障下游或原 CircuitBreaker 路由。
 */
@RestController
public class GatewayFallbackController {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayFallbackController.class);

    @RequestMapping("/fallback/{*path}")
    public ResponseEntity<Map<String, Object>> fallback(ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        String path = exchange.getRequest().getPath().value();
        // 异常详情只记日志，客户端收到固定的降级响应。
        LOGGER.warn("网关降级，降级路径: {}", path, cause);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "code", HttpStatus.SERVICE_UNAVAILABLE.value(),
            "message", "服务暂不可用，请稍后重试",
            "path", path));
    }
}
