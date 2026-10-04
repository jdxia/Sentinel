/*
 * Copyright 1999-2024 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcConfig;

import com.alibaba.csp.sentinel.adapter.web.common.UrlCleaner;
import com.alibaba.csp.sentinel.util.StringUtil;
import jakarta.servlet.http.HttpServletRequest;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Spring Web MVC interceptor that integrates with Sentinel.
 * <p>
 * This will record resource as `${uri}`.
 *
 * @since 1.8.8
 */
public class SentinelWebInterceptor extends AbstractSentinelInterceptor {

    /**
     * 核心看继承的 {@link AbstractSentinelInterceptor#preHandle(HttpServletRequest, HttpServletResponse, Object)}
     */

    private final SentinelWebMvcConfig config;

    public SentinelWebInterceptor() {
        this(new SentinelWebMvcConfig());
    }

    public SentinelWebInterceptor(SentinelWebMvcConfig config) {
        super(config);
        if (config == null) {
            // Use the default config by default.
            this.config = new SentinelWebMvcConfig();
        } else {
            this.config = config;
        }
    }

    @Override
    protected String getResourceName(HttpServletRequest request) {

        /**
         * 从 HttpServletRequest 的 attribute 中取出 Spring MVC 在Handler 匹配阶段写入的"最佳匹配 URL 模板"
         *
         * Spring MVC 处理请求的时序是（DispatcherServlet.doDispatch）：
         * 1. getHandler(request) —— 遍历 HandlerMapping（如 RequestMappingHandlerMapping）找到匹配的 handler
         * 2. 匹配成功时，RequestMappingInfoHandlerMapping 会把命中的 URL 模板写入 request attribute
         *      request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, bestPattern.getPatternString());
         * 3. 之后才执行拦截器链的 preHandle
         * 正因为 preHandle 晚于 handler 解析，Sentinel 拦截器在 SentinelWebInterceptor.java:59 才能读到这个属性
         */
        // Resolve the Spring Web URL pattern from the request attribute.
        Object resourceNameObject = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);


        /**
         * 请求没有命中任何 @RequestMapping
         * 由不设置该属性的 HandlerMapping 处理（如部分静态资源映射）
         */
        if (resourceNameObject == null || !(resourceNameObject instanceof String)) {
            return null;
        }
        String resourceName = (String) resourceNameObject;
        UrlCleaner urlCleaner = config.getUrlCleaner();
        if (urlCleaner != null) {
            resourceName = urlCleaner.clean(resourceName);
        }
        if (config.isContextPathSpecify() && request.getContextPath() != null) {
            resourceName = request.getContextPath() + resourceName;
        }
        if (StringUtil.isNotEmpty(resourceName) && config.isHttpMethodSpecify()) {
            resourceName = request.getMethod().toUpperCase() + ":" + resourceName;
        }
        return resourceName;
    }

    @Override
    protected String getContextName(HttpServletRequest request) {
        if (config.isWebContextUnify()) {
            return super.getContextName(request);
        }

        return getResourceName(request);
    }
}
