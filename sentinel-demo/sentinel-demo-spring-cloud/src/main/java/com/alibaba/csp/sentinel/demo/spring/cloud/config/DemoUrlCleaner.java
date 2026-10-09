package com.alibaba.csp.sentinel.demo.spring.cloud.config;

import com.alibaba.csp.sentinel.adapter.web.common.UrlCleaner;
import org.springframework.stereotype.Component;

/**
 * Spring Cloud Alibaba 自动把此 Bean 注入 SentinelWebMvcConfig，无需 WebCallbackManager。
 * 输入是 MVC 匹配模板（如 /url-cleaner/orders/{orderId}），不含 GET: 前缀和查询参数。
 * 当前 Demo 使用根 context-path；配置非空 context-path 后，需要重新验证空字符串的跳过行为。
 */
@Component
public class DemoUrlCleaner implements UrlCleaner {

    @Override
    public String clean(String originUrl) {
        // 跳过此 Web 入口的统计和规则检查；Controller 及其内部的其他 Sentinel 资源仍可执行。
        if ("/url-cleaner/health".equals(originUrl)) {
            return "";
        }
        // 精确匹配模板，避免把订单明细、退款等不同业务误合并到订单查询配额。
        if ("/url-cleaner/orders/{orderId}".equals(originUrl)) {
            return "/url-cleaner/orders/*";
        }
        // 两个不同的接口路径共用同一个商品查询资源和限流配额。
        if ("/url-cleaner/products/{productId}".equals(originUrl)
                || "/url-cleaner/catalog/{productId}".equals(originUrl)) {
            return "/url-cleaner/products/*";
        }
        return originUrl;
    }
}
