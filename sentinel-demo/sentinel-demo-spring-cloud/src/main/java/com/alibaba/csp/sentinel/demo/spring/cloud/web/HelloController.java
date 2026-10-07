package com.alibaba.csp.sentinel.demo.spring.cloud.web;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class HelloController {

    public static final String RESOURCE_NAME = "spring-cloud-hello";

    /**
     * 当 Dashboard 下发的规则允许该资源通过时，返回正常响应。
     *
     * <p>Spring Cloud Alibaba Starter 会自动提供 {@code SentinelResourceAspect}。该切面在调用此方法前
     * 创建 Sentinel Entry，并在调用结束后退出 Entry，因此该方法只保留业务逻辑。在 Dashboard 中配置规则前，
     * 至少调用一次此接口，让 Sentinel 指标记录 {@value #RESOURCE_NAME} 资源。</p>
     *
     * http://127.0.0.1:18080/hello
     *
     * @return Demo 的成功响应
     */
    @GetMapping(value = "/hello", produces = MediaType.TEXT_PLAIN_VALUE)
    @SentinelResource(value = RESOURCE_NAME, blockHandler = "handleBlocked")
    public ResponseEntity<String> hello() {
        return ResponseEntity.ok("Hello from Spring Cloud Alibaba Sentinel");
    }

    /**
     * 只处理 Sentinel 规则拒绝，不在此处隐藏业务异常。
     *
     * <p>Block Handler 的返回类型和原方法参数必须与原方法保持一致，并在参数列表末尾追加一个
     * {@link BlockException} 参数。这里返回 HTTP 429，避免调用方把被拒绝的请求误判为成功的业务响应。</p>
     *
     * @param exception Sentinel 具体的规则拒绝原因
     * @return 限流响应
     */
    public ResponseEntity<String> handleBlocked(BlockException exception) {
        String message = "Blocked by Sentinel: " + exception.getClass().getSimpleName();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(message);
    }
}
