package com.alibaba.csp.sentinel.demo.spring.cloud.web;

import com.alibaba.csp.sentinel.demo.spring.cloud.config.DemoUrlCleaner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * URL 清洗示例：启动本模块 main 后，访问 http://127.0.0.1:18080/url-cleaner 下的接口。
 *
 * <p>处理顺序：请求路径 → MVC 映射模板 → {@link DemoUrlCleaner} → 添加 GET: 前缀 → Sentinel Entry。
 * 清洗只改变 Sentinel 资源名，不重写请求 URL，也不改变传入 Controller 的路径参数。</p>
 *
 * <p>本类不添加 @SentinelResource，以便单独观察 Web 入口资源。跳过 Web 入口不会关闭业务方法中
 * 独立的 @SentinelResource 或 SphU.entry。合并资源后，限流规则必须使用最终资源名，
 * 如 GET:/url-cleaner/products/*；这里的星号是资源名中的普通字符，不是规则匹配表达式。</p>
 */
@RestController
@RequestMapping("/url-cleaner")
public class UrlCleanerController {

    /** /health 正常返回，但不创建此 URL 的 Sentinel 资源。 */
    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /** /orders/1001、/orders/1002 → GET:/url-cleaner/orders/*，共同使用订单查询配额。 */
    @GetMapping("/orders/{orderId}")
    public String order(@PathVariable("orderId") String orderId) {
        return "order:" + orderId;
    }

    /** 保留明细模板，不与订单查询合并：GET:/url-cleaner/orders/{orderId}/items/{itemId}。 */
    @GetMapping("/orders/{orderId}/items/{itemId}")
    public String orderItem(@PathVariable("orderId") String orderId, @PathVariable("itemId") String itemId) {
        return "order:" + orderId + ",item:" + itemId;
    }

    /** /products/101、/catalog/202 → GET:/url-cleaner/products/*，两个路径共享同一配额。 */
    @GetMapping({"/products/{productId}", "/catalog/{productId}"})
    public String product(@PathVariable("productId") String productId) {
        return "product:" + productId;
    }

    /** 不做自定义清洗，MVC 已自动合并 ID：/users/1、/users/2 → GET:/url-cleaner/users/{userId}。 */
    @GetMapping("/users/{userId}")
    public String user(@PathVariable("userId") String userId) {
        return "user:" + userId;
    }
}

