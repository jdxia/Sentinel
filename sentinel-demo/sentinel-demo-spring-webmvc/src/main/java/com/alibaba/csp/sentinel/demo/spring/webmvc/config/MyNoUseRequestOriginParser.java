package com.alibaba.csp.sentinel.demo.spring.webmvc.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * 自定义请求来源解析器
 * 如果是  Spring Cloud Alibaba 的 SentinelWebAutoConfiguration（通过 ObjectProvider<RequestOriginParser> 注入）才会自动生效
 *
 * 但是当前工程的拦截器是手动注入的 {@link MyBootInterceptorConfig#addSpringMvcInterceptor(InterceptorRegistry)}, 里面指定了来源获取
 * 所以这个是不生效的, 如果是  Spring Cloud Alibaba 这个是可以的
 */
@Component
public class MyNoUseRequestOriginParser implements RequestOriginParser {
    @Override
    public String parseOrigin(HttpServletRequest request) {
        return request.getParameter("origin");
    }
}
