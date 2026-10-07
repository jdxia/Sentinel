package com.alibaba.csp.sentinel.demo.spring.cloud.web;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.Env;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@RequestMapping(value = "/examples", produces = MediaType.TEXT_PLAIN_VALUE)
public class SentinelExamplesController {

    private static final Logger logger = LoggerFactory.getLogger(SentinelExamplesController.class);

    public static final String QPS_RESOURCE = "cloud-example-qps";
    public static final String CONCURRENCY_RESOURCE = "cloud-example-concurrency";
    public static final String CIRCUIT_RESOURCE = "cloud-example-circuit";
    public static final String HOT_RESOURCE = "cloud-example-hot-product";
    public static final String MANUAL_RESOURCE = "cloud-example-manual";
    public static final String PRIORITY_RESOURCE = "cloud-example-priority";
    public static final String BATCH_RESOURCE = "cloud-example-batch";

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
            logger.info("===========> method: {}, preSleep", this.getClass().getSimpleName());
            Thread.sleep(500);
            logger.info("===========> method: {}, postSleep", this.getClass().getSimpleName());
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
        logger.info("===========> method: {}, preFail", this.getClass().getSimpleName());
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
        logger.info("===========> method: {}", this.getClass().getSimpleName());
        return ResponseEntity.ok("Hot product passed: " + productId);
    }

    /**
     * 手动埋点：只保护 try 内的代码，每秒阈值 2，不依赖 SentinelResource 注解。
     * <pre>
     * for i in {1..10}; do curl -s -w ' HTTP %{http_code}\n' http://127.0.0.1:18080/examples/manual; done
     * </pre>
     * 快速请求出现 200 和 429 / FlowException。Entry 必须在同一线程中按后进先出顺序退出，
     * close() 会先于外层 catch 执行，所以业务异常的上报放在 try 块内部的内层 catch：
     * 先 Tracer.trace 打标记，close() 里的 exit 才能读取标记并记入异常数，
     * 异常比例/异常数熔断才会触发（注解切面会自动做这件事）。
     */
    @GetMapping("/manual")
    public ResponseEntity<String> manual() {
        logger.info("===========> method: {}, preAction", this.getClass().getSimpleName());
        try (Entry entry = SphU.entry(MANUAL_RESOURCE, EntryType.OUT)) {
            logger.info("===========> method: {}, action === entry", this.getClass().getSimpleName());
            // 将需要保护的业务代码放在这里；申请失败时不会执行这个代码块。
            try {
                return ResponseEntity.ok("Manual entry passed");
            } catch (Throwable exception) {
                // 手动埋点必须显式上报业务异常（注解切面会自动做）：先打标记，
                // 内层 catch 先于 close() 执行，exit 才能读取标记并统计异常数，异常类熔断随之生效。
                Tracer.trace(exception);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Manual business failure: " + exception.getMessage());
            }
        } catch (BlockException exception) {
            return handleFlowBlocked(exception);
        }
    }

    /**
     * 优先级申请：普通请求和优先请求共享每秒 2 份额度，prioritized 默认为 true。
     * <pre>
     * for i in {1..3}; do curl -s -w ' HTTP %{http_code}\n' 'http://127.0.0.1:18080/examples/priority?prioritized=false'; done
     * sleep 0.6
     * curl -i 'http://127.0.0.1:18080/examples/priority?prioritized=true'
     * </pre>
     * 普通请求超限立即返回 429；优先请求在本地 QPS + 快速失败规则下尝试预约后续统计窗口的额度，
     * 等待时间必须小于 OccupyTimeoutProperty 的上限（默认 500ms），无法预约仍返回 429。
     * 响应中的 entryWaitMs 是申请 Entry 的耗时，包含规则检查和可能发生的等待，不含业务耗时。
     * Sph 是接口，通过 Env.sph 调用；只需优先申请 1 份时，也可以用 SphU.entryWithPriority。
     */
    @GetMapping("/priority")
    public ResponseEntity<String> priority(@RequestParam(defaultValue = "true") boolean prioritized) {
        long startNanos = System.nanoTime();
        logger.info("===========> method: {}, preAction", this.getClass().getSimpleName());
        /**
         * 不推荐用 Env.sph
         * 一般不要用 Env.sph 除非是 需要的参数组合 SphU 没包装, 框架集成/测试场景需要面向 Sph 接口编程
         */
        try (Entry entry = Env.sph.entryWithPriority(PRIORITY_RESOURCE, EntryType.OUT, 1, prioritized)) {
            logger.info("===========> method: {}, action === entryWithPriority", this.getClass().getSimpleName());
            long entryWaitMs = (System.nanoTime() - startNanos) / 1_000_000;
            try {
                return ResponseEntity.ok("Priority entry passed: prioritized=" + prioritized
                    + ", entryWaitMs=" + entryWaitMs);
            } catch (Throwable exception) {
                // 手动埋点必须显式上报业务异常（注解切面会自动做）：先打标记，
                // 内层 catch 先于 close() 执行，exit 才能读取标记并统计异常数，异常类熔断随之生效。
                Tracer.trace(exception);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Priority business failure: " + exception.getMessage());
            }
        } catch (BlockException exception) {
            return handleFlowBlocked(exception);
        }
    }

    /**
     * 批量申请：资源每秒阈值 5，每次按 batchCount 消耗额度，而不是每个 HTTP 请求只算 1 次。
     * <pre>
     * curl -i 'http://127.0.0.1:18080/examples/batch?batchCount=3'
     * curl -i 'http://127.0.0.1:18080/examples/batch?batchCount=3'
     * curl -i 'http://127.0.0.1:18080/examples/batch?batchCount=6'
     * </pre>
     * 空闲后快速执行：第一批 3 份通过，第二批因剩余额度不足被整批拒绝；6 份大于阈值，始终拒绝。
     * 申请不会拆分为部分成功，也不会提高规则阈值；业务只在获得整批额度后执行。
     * close() 自动 exit，携带 Entry 保存的 batchCount；exit 是结束统计，
     * 不会把已经消耗的 QPS 额度归还。
     */
    @GetMapping("/batch")
    public ResponseEntity<String> batch(@RequestParam(defaultValue = "3") int batchCount) {

        logger.info("===========> method: {}, preAction", this.getClass().getSimpleName());

        if (batchCount <= 0) {
            return ResponseEntity.badRequest().body("batchCount must be positive");
        }
        try (Entry entry = SphU.entry(BATCH_RESOURCE, EntryType.OUT, batchCount)) {
            logger.info("===========> method: {}, action === SphU", this.getClass().getSimpleName());

            // 在这里处理整批消息或任务；本例只返回数量，不产生外部业务影响。
            try {
                return ResponseEntity.ok("Batch entry passed: batchCount=" + batchCount);
            } catch (Throwable exception) {
                // 手动埋点必须显式上报业务异常（注解切面会自动做）：先打标记，
                // 内层 catch 先于 close() 执行，exit 才能读取标记并统计异常数，异常类熔断随之生效。
                Tracer.trace(exception);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Batch business failure: " + exception.getMessage());
            }
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
