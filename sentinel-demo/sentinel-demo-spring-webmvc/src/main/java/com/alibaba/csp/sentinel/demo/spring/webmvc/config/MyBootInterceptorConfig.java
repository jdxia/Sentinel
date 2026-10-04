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
package com.alibaba.csp.sentinel.demo.spring.webmvc.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.SentinelWebInterceptor;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.SentinelWebTotalInterceptor;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcConfig;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcTotalConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Config sentinel interceptor
 *
 * @author kaizi2009
 */
@Configuration
public class MyBootInterceptorConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Add Sentinel interceptor
        addSpringMvcInterceptor(registry);
    }

    /**
     * 注意这个和 spring cloud alibaba 的 不一样, 那个是 在 com.alibaba.cloud.sentinel.SentinelWebAutoConfiguration#sentinelWebMvcConfig() 这里集成的
     */
    private void addSpringMvcInterceptor(InterceptorRegistry registry) {
        SentinelWebMvcConfig config = new SentinelWebMvcConfig();

        // Use the default handler.
        /**
         * 这是默认的, 不建议用这个, new DefaultBlockExceptionHandler()
         */
//        config.setBlockExceptionHandler(new DefaultBlockExceptionHandler());
        // 里面设置为null也行, 走默认拦截器
        config.setBlockExceptionHandler((request, response, resourceName, e) -> {
            throw e;
        });

        // Custom configuration if necessary
        config.setHttpMethodSpecify(true);
        // By default web context is true, means that unify web context(i.e. use the default context name),
        // in most scenarios that's enough, and it could reduce the memory footprint.
        // If set it to false, entrance contexts will be separated by different URLs,
        // which is useful to support "chain" relation flow strategy.
        // We can change it and view different result in `Resource Chain` menu of dashboard.
        /**
         * 链路整合, 默认是true, 减少 Context 数量，降低内存占用
         * Context 是 Sentinel 中用于标识调用链路入口的概念。 每个请求进入 Sentinel 时都会创建一个 Context。
         *
         * 链路模式的判断逻辑是：只有当 context.getName() 等于 refResource（配置的入口资源）时，规则才会生效。
         *   当 WebContextUnify = true 时：
         *   - 所有请求的 context name 都是 "sentinel_spring_web_context"
         *   - 如果你配置 refResource = "/api/user"，它永远不会等于 "sentinel_spring_web_context"
         *   - 结果：链路规则永远匹配不上，流控失效！
         */
        config.setWebContextUnify(true);

        /**
         * 来源获取是从这个来拿的
         */
        config.setOriginParser(request -> request.getHeader("S-user"));

        // Add sentinel interceptor
        registry.addInterceptor(new SentinelWebInterceptor(config)).addPathPatterns("/**");
    }

    private void addSpringMvcTotalInterceptor(InterceptorRegistry registry) {
        //Config
        SentinelWebMvcTotalConfig config = new SentinelWebMvcTotalConfig();

        //Custom configuration if necessary
        config.setRequestAttributeName("my_sentinel_spring_mvc_total_entity_container");
        config.setTotalResourceName("my-spring-mvc-total-url-request");

        //Add sentinel interceptor
        registry.addInterceptor(new SentinelWebTotalInterceptor(config)).addPathPatterns("/**");
    }
}
