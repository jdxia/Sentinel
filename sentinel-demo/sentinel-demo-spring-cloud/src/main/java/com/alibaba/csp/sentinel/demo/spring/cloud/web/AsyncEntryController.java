package com.alibaba.csp.sentinel.demo.spring.cloud.web;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.alibaba.csp.sentinel.AsyncEntry;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 将 AsyncEntryDemo 改为 HTTP 示例，沿用 Web 拦截器创建的上下文和 origin 参数。
 *
 * <p>/examples/async?origin=originA 的资源树：</p>
 * <pre>
 * test-top
 * ├─ test-sync
 * └─ test-async
 *    ├─ test-sync-in-async
 *    └─ test-another-async（调用 7 次）
 *       └─ test-another-sync-in-async
 * </pre>
 * <p>在 Dashboard 为 test-another-async 配置 QPS=5、来源 default；
 * 为 test-another-sync-in-async 配置 QPS=4、来源 originB。
 * originA 不匹配第二条规则。规则由现有规则源管理，本例不调用 loadRules 全量覆盖。</p>
 */
@RestController
@RequestMapping(value = "/examples", produces = MediaType.TEXT_PLAIN_VALUE)
public class AsyncEntryController {

    private static final Logger logger = LoggerFactory.getLogger(AsyncEntryController.class);

    // 模拟调用包含阻塞等待，使用独立的有界线程池，避免占用公共 ForkJoinPool。
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(64), new CustomizableThreadFactory("sentinel-async-demo-"),
        new ThreadPoolExecutor.AbortPolicy());

    @GetMapping("/async-direct")
    public CompletableFuture<ResponseEntity<String>> directlyAsync() {
        final AsyncEntry entry;
        try {
            entry = SphU.asyncEntry("test-async-not-nested");
        } catch (BlockException exception) {
            return CompletableFuture.completedFuture(blocked(exception));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                pause(3);
                // 没有嵌套资源，不需要切换到 entry.getAsyncContext()。
                return ResponseEntity.ok("Async result: abc: " + System.currentTimeMillis());
            }, executor).whenComplete((result, error) -> completeEntry(entry, error));
        } catch (RejectedExecutionException exception) {
            completeEntry(entry, exception);
            return CompletableFuture.completedFuture(busy());
        }
    }

    @GetMapping("/async")
    public CompletableFuture<ResponseEntity<String>> asyncThenSync() {
        // Web 拦截器负责 Context 的生命周期，Controller 不重新 enter/exit。
        try (Entry top = SphU.entry("test-top")) {
            return doAsyncThenSync();
        } catch (BlockException exception) {
            return CompletableFuture.completedFuture(blocked(exception));
        }
    }

    private CompletableFuture<ResponseEntity<String>> doAsyncThenSync() {
        final AsyncEntry entry;
        try {
            entry = SphU.asyncEntry("test-async");
        } catch (BlockException exception) {
            return CompletableFuture.completedFuture(blocked(exception));
        }
        final CompletableFuture<ResponseEntity<String>> result;
        try {
            result = CompletableFuture.supplyAsync(() -> {
                pause(3);
                // 回调线程必须恢复异步上下文，嵌套资源才能挂到 test-async 下。
                ContextUtil.runOnContext(entry.getAsyncContext(), () -> {
                    for (int i = 0; i < 7; i++) {
                        anotherAsync();
                    }
                    sync("test-sync-in-async");
                });
                // 与原例一致，子异步任务独立完成，此时可能仍在运行。
                return ResponseEntity.ok("Async callback completed; nested async tasks may still be running");
            }, executor).whenComplete((response, error) -> completeEntry(entry, error));
        } catch (RejectedExecutionException exception) {
            completeEntry(entry, exception);
            return CompletableFuture.completedFuture(busy());
        }
        sync("test-sync");
        return result;
    }

    private void anotherAsync() {
        final AsyncEntry entry;
        try {
            entry = SphU.asyncEntry("test-another-async");
        } catch (BlockException exception) {
            logger.info("Nested async blocked: {}", exception.getClass().getSimpleName());
            return;
        }
        try {
            CompletableFuture.runAsync(() -> {
                ContextUtil.runOnContext(entry.getAsyncContext(), () -> {
                    pause(2);
                    sync("test-another-sync-in-async");
                    logger.info("Async result: 666");
                });
            }, executor).whenComplete((result, error) -> completeEntry(entry, error));
        } catch (RejectedExecutionException exception) {
            // 提交失败时回调不会执行，必须在提交线程释放已申请的 Entry。
            completeEntry(entry, exception);
        }
    }

    private void sync(String resource) {
        try (Entry entry = SphU.entry(resource)) {
            logger.info("Sync resource passed: {}", resource);
        } catch (BlockException exception) {
            logger.info("Sync resource blocked: {}, {}", resource, exception.getClass().getSimpleName());
        }
    }

    private static void completeEntry(AsyncEntry entry, Throwable error) {
        try {
            if (error != null) {
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                // 回调线程没有原请求的当前 Entry，必须显式指定，并在 exit 前记录异常。
                Tracer.traceEntry(cause, entry);
                logger.warn("Async resource failed: {}", entry.getResourceWrapper().getName(), cause);
            }
        } finally {
            entry.exit();
        }
    }

    private static void pause(int seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CompletionException(exception);
        }
    }

    private static ResponseEntity<String> blocked(BlockException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body("Async entry blocked: " + exception.getClass().getSimpleName());
    }

    private static ResponseEntity<String> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Async executor is busy");
    }

    @PreDestroy
    public void shutdown() {
        // 不丢弃已排队的任务，确保它们的完成回调仍会释放 Entry。
        executor.shutdown();
    }
}
