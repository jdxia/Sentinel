package com.alibaba.csp.sentinel.demo.spring.cloud.web;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行 SpringCloudSentinelDemoApplication.main，Program arguments 填：
 * {@code --spring.profiles.active=examples}，然后访问 http://127.0.0.1:18080/examples/qps。
 * 无需启动 Nacos 或 Dashboard；想观察 Dashboard，再加
 * {@code --spring.cloud.sentinel.transport.dashboard=127.0.0.1:8678}。
 *
 * <p>每个方法的注释都给出可直接复制到终端的 curl 命令。涉及 QPS 的实验要快速请求，
 * 手工点浏览器往往达不到阈值。自动验证：从仓库根目录运行
 * {@code python3 sentinel-demo/sentinel-demo-spring-cloud/scripts/test-examples.py}，
 * 验证前重启应用并停止其他请求，以免已有流量影响计数和熔断状态。</p>
 *
 * <p>正常请求返回 200；流控返回 429；业务异常降级和熔断返回 503，但响应正文不同。
 * 注解依赖 Spring AOP 代理：本类内直接调用另一个注解方法不会经过代理。
 * blockHandler 参数与原方法一致，并在最后追加 BlockException；fallback 最后追加 Throwable。</p>
 */
@RestController
@Profile("examples")
@RequestMapping(value = "/examples", produces = MediaType.TEXT_PLAIN_VALUE)
public class SentinelExamplesController {

    public static final String QPS_RESOURCE = "cloud-example-qps";
    public static final String CONCURRENCY_RESOURCE = "cloud-example-concurrency";
    public static final String CIRCUIT_RESOURCE = "cloud-example-circuit";
    public static final String HOT_RESOURCE = "cloud-example-hot-product";
    public static final String MANUAL_RESOURCE = "cloud-example-manual";

    /**
     * QPS 限流：每秒阈值 2，超出时立即拒绝，不排队。
     * <pre>
     * curl -i http://127.0.0.1:18080/examples/qps
     * for i in {1..10}; do curl -s -w ' HTTP %{http_code}\n' http://127.0.0.1:18080/examples/qps; done
     * </pre>
     * 快速请求能看到 200 和 429 / FlowException；跨统计窗口时通过数可能不同，不保证整轮只有 2 个 200。
     */
    @GetMapping("/qps")
    @SentinelResource(value = QPS_RESOURCE, blockHandler = "handleFlowBlocked")
    public ResponseEntity<String> qps() {
        return ResponseEntity.ok("QPS passed");
    }

    /**
     * 并发限制：最多 1 个请求正在执行，用固定 500ms 耗时模拟业务占用。
     * <pre>{@code
     * for i in {1..5}; do curl -s -w ' HTTP %{http_code}\n' http://127.0.0.1:18080/examples/concurrency & done; wait
     * }</pre>
     * 必须并发请求：一个返回 200，其余重叠请求返回 429；串行 curl 一般全部通过。
     */
    @GetMapping("/concurrency")
    @SentinelResource(value = CONCURRENCY_RESOURCE, blockHandler = "handleFlowBlocked",
        exceptionsToIgnore = InterruptedException.class)
    public ResponseEntity<String> concurrency() throws InterruptedException {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
        return ResponseEntity.ok("Concurrency passed after 500ms");
    }

    /**
     * 异常数熔断：10 秒统计周期内，至少 2 次请求且异常数 > 1，熔断 5 秒。
     * <pre>
     * curl -i 'http://127.0.0.1:18080/examples/circuit?fail=true'
     * curl -i 'http://127.0.0.1:18080/examples/circuit?fail=true'
     * curl -i 'http://127.0.0.1:18080/examples/circuit?fail=false'
     * sleep 6
     * curl -i 'http://127.0.0.1:18080/examples/circuit?fail=false'
     * </pre>
     * 从刚启动的应用开始快速执行：前两次 503 / Business fallback，第三次 503 / Circuit blocked。
     * 前两次是方法抛出的业务异常，切面先记录异常再执行 fallback；第三次被熔断器挡住，方法不会执行。
     * 等待后放行一个半开探测请求：fail=false 成功后关闭熔断器；fail=true 则再次熔断。
     */
    @GetMapping("/circuit")
    @SentinelResource(value = CIRCUIT_RESOURCE, blockHandler = "handleCircuitBlocked",
        fallback = "handleBusinessFailure")
    public ResponseEntity<String> circuit(@RequestParam(defaultValue = "false") boolean fail) {
        if (fail) {
            throw new IllegalStateException("Simulated downstream failure");
        }
        return ResponseEntity.ok("Circuit passed");
    }

    /**
     * 热点参数：第 0 个方法参数 productId 的每个值分别享有 1 秒内 2 次的额度。
     * <pre>
     * for i in {1..10}; do curl -s -w ' HTTP %{http_code}\n' 'http://127.0.0.1:18080/examples/hot?productId=100'; done
     * curl -i 'http://127.0.0.1:18080/examples/hot?productId=200'
     * </pre>
     * 商品 100 高频请求出现 429 / ParamFlowException；此时商品 200 仍可通过。
     * 注解切面将方法参数传给 SphU.entry；只有 URL 拦截器埋点不能直接完成本例的参数限流。
     */
    @GetMapping("/hot")
    @SentinelResource(value = HOT_RESOURCE, blockHandler = "handleHotBlocked")
    public ResponseEntity<String> hot(@RequestParam(defaultValue = "100") long productId) {
        return ResponseEntity.ok("Hot product passed: " + productId);
    }

    /**
     * 手动埋点：只保护 try 内的代码，每秒阈值 2，不依赖 SentinelResource 注解。
     * <pre>
     * for i in {1..10}; do curl -s -w ' HTTP %{http_code}\n' http://127.0.0.1:18080/examples/manual; done
     * </pre>
     * 快速请求出现 200 和 429 / FlowException。Entry 必须在同一线程中按后进先出顺序退出，
     * try-with-resources 自动调用 exit。若添加可能失败的业务，需在 Entry 退出前用
     * Tracer.traceEntry(exception, entry) 记录业务异常；手动 API 不会像注解切面那样自动记录。
     */
    @GetMapping("/manual")
    public ResponseEntity<String> manual() {
        try (Entry entry = SphU.entry(MANUAL_RESOURCE, EntryType.OUT)) {
            return ResponseEntity.ok("Manual entry passed");
        } catch (BlockException exception) {
            return handleFlowBlocked(exception);
        }
    }

    public ResponseEntity<String> handleFlowBlocked(BlockException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body("Flow blocked: " + exception.getClass().getSimpleName());
    }

    public ResponseEntity<String> handleHotBlocked(long productId, BlockException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body("Hot product blocked: " + productId + ", " + exception.getClass().getSimpleName());
    }

    public ResponseEntity<String> handleCircuitBlocked(boolean fail, BlockException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body("Circuit blocked: " + exception.getClass().getSimpleName());
    }

    public ResponseEntity<String> handleBusinessFailure(boolean fail, Throwable exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body("Business fallback: " + exception.getMessage());
    }
}
