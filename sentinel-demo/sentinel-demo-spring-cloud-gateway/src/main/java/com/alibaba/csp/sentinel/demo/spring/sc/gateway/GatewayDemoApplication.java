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
package com.alibaba.csp.sentinel.demo.spring.sc.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.reactor.SentinelReactorSubscriber;
import com.alibaba.csp.sentinel.demo.spring.sc.gateway.web.GatewayFallbackController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.server.ServerWebExchange;

/**
 * Spring Cloud Gateway 的 Sentinel 接入示例，不预置本地限流、熔断规则或 API 分组。
 *
 * <p>默认启动：由 Dashboard 下发内存规则，网关重启后需重新配置。</p>
 * <p>启动参数 {@code --spring.profiles.active=nacos}：合并 application-nacos.yml，
 * 由 Alibaba Starter 加载并监听网关规则、API 分组和熔断规则；Dashboard 也须启用 nacos profile。</p>
 * <p>config 包中的 MySentinelGatewayConfiguration 注册支持代理头的限流过滤器，异常处理器仍由 SentinelSCGAutoConfiguration 注册，
 * SentinelGatewayBlockHandlerConfig 为规则拒绝和下游网络异常提供中文 JSON 提示。
 * 两种模式的 Dashboard 地址均通过 SENTINEL_DASHBOARD 环境变量配置。</p>
 * <p>路由中的 CircuitBreaker 通过 config 包的 CircuitBreakerSentinelFilterFactory 接入 Sentinel；
 * 默认转发到原下游的“原路径/fallback”，也可通过 fallbackUri 单独指定地址；
 * 显式配置本地 /fallback 时由 {@link GatewayFallbackController} 返回降级响应。</p>
 *
 * @author Eric Zhao
 */
@SpringBootApplication
public class GatewayDemoApplication {

    /**
     * 网关走的是 {@link SentinelGatewayFilter#filter(ServerWebExchange, GatewayFilterChain)}
     * 执行资源进入、异常记录和资源退出的是 {@link SentinelReactorSubscriber} hookOnSubscribe 和 entryWhenSubscribed
     */

    /**
     * 访问
     * http://127.0.0.1:8090/httpbin/get
     * http://127.0.0.1:8090/product/1
     */
    public static void main(String[] args) {
        /**
         * 在 Sentinel 初始化并缓存应用类型前设置，避免网关被注册为普通应用。
         * 修改的是当前 JVM 进程内部的系统属性，不是操作系统的全局环境变量
         *
         * 或者 src/main/resources/sentinel.properties 里面写 csp.sentinel.app.type=11
         */
        System.setProperty("csp.sentinel.app.type", "11");
        SpringApplication.run(GatewayDemoApplication.class, args);
    }
}
