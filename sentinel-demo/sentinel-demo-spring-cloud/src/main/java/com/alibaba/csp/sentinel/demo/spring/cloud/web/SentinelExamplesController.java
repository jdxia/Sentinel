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
     * http://127.0.0.1:18080/examples/qps
     */
    @GetMapping("/qps")
    @SentinelResource(value = QPS_RESOURCE, blockHandler = "handleFlowBlocked")
    public ResponseEntity<String> qps() {
        return ResponseEntity.ok("QPS passed");
    }

    /**
     * http://127.0.0.1:18080/examples/concurrency
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
     * http://127.0.0.1:18080/examples/circuit?fail=true
     *
     * 注解默认都是 OUT 出站限流, 建议只有入口设置 IN 限流, 避免QPS和线程统计问题
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
     *http://127.0.0.1:18080/examples/hot?productId=100
     *
     * 注解默认都是 OUT 出站限流, 建议只有入口设置 IN 限流, 避免QPS和线程统计问题
     */
    @GetMapping("/hot")
    @SentinelResource(value = HOT_RESOURCE, blockHandler = "handleHotBlocked")
    public ResponseEntity<String> hot(@RequestParam(defaultValue = "100") long productId) {
        logger.info("===========> method: {}", this.getClass().getSimpleName());
        return ResponseEntity.ok("Hot product passed: " + productId);
    }

    /**
     * http://127.0.0.1:18080/examples/manual
     */
    @GetMapping("/manual")
    public ResponseEntity<String> manual() {
        logger.info("===========> method: {}, preAction", this.getClass().getSimpleName());

        // OUT 出站限流, 建议只有入口设置 IN 限流, 避免QPS和线程统计问题
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
     * http://127.0.0.1:18080/examples/priority?prioritized=false
     *
     * http://127.0.0.1:18080/examples/priority?prioritized=true
     */
    @GetMapping("/priority")
    public ResponseEntity<String> priority(@RequestParam(defaultValue = "true") boolean prioritized) {
        long startNanos = System.nanoTime();
        logger.info("===========> method: {}, preAction", this.getClass().getSimpleName());
        /**
         * 不推荐用 Env.sph
         * 一般不要用 Env.sph 除非是 需要的参数组合 SphU 没包装, 框架集成/测试场景需要面向 Sph 接口编程
         *
         * OUT 出站限流, 建议只有入口设置 IN 限流, 避免QPS和线程统计问题
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
     * http://127.0.0.1:18080/examples/batch?batchCount=3
     */
    @GetMapping("/batch")
    public ResponseEntity<String> batch(@RequestParam(defaultValue = "3") int batchCount) {

        logger.info("===========> method: {}, preAction", this.getClass().getSimpleName());

        if (batchCount <= 0) {
            return ResponseEntity.badRequest().body("batchCount must be positive");
        }

        // OUT 出站限流, 建议只有入口设置 IN 限流, 避免QPS和线程统计问题
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
