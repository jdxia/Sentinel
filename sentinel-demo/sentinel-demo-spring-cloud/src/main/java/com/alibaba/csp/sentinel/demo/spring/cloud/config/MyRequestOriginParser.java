package com.alibaba.csp.sentinel.demo.spring.cloud.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * 自定义请求来源解析器
 * 如果是  Spring Cloud Alibaba 的 SentinelWebAutoConfiguration（通过 ObjectProvider<RequestOriginParser> 注入）才会自动生效
 *
 * 这个是 Spring Cloud Alibaba 所以会生效
 */
@Component
public class MyRequestOriginParser implements RequestOriginParser {
    @Override
    public String parseOrigin(HttpServletRequest request) {
        return request.getParameter("origin");
    }
}
