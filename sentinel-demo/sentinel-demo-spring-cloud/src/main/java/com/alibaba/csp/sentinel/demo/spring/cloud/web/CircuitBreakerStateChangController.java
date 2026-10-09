package com.alibaba.csp.sentinel.demo.spring.cloud.web;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.demo.spring.cloud.SpringCloudSentinelDemoApplication;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.EventObserverRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通过 {@link SpringCloudSentinelDemoApplication#main(String[])} 启动，由现有应用扫描并管理本示例。
 * Starter 自动配置 Sentinel 注解切面，无需手动创建 Entry 或上报异常，也无需启动 Dashboard。
 *
 * <p>请求地址：http://127.0.0.1:18080/examples/circuit-state-change?fail=true</p>
 * <ol>
 *     <li>10 秒内连续请求 fail=true 五次：CLOSED -> OPEN。</li>
 *     <li>立即请求 fail=false：进入 blockHandler，不执行业务。</li>
 *     <li>等待超过 1 秒，请求 fail=true：OPEN -> HALF_OPEN -> OPEN。</li>
 *     <li>再次等待超过 1 秒，请求 fail=false：OPEN -> HALF_OPEN -> CLOSED。</li>
 *     <li>继续请求 fail=false：业务正常通过。</li>
 * </ol>
 */
@RestController
public class CircuitBreakerStateChangController {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerStateChangController.class);

    static final String RESOURCE = "cloud-example-circuit-state-change";
    private static final String OBSERVER_NAME = "cloud-example-circuit-state-change-logging";
    private static final int MIN_REQUEST_AMOUNT = 5;
    private static final int TIME_WINDOW_SECONDS = 1;

    @PostConstruct
    public void initialize() {
        registerStateChangeObserver();

        // 走 dashboard手动配置
//        DegradeRule rule = new DegradeRule(RESOURCE)
//            .setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType())
//            .setCount(0.5)
//            .setMinRequestAmount(MIN_REQUEST_AMOUNT)
//            .setStatIntervalMs(10_000)
//            .setTimeWindow(TIME_WINDOW_SECONDS);
//        // loadRules 是全量替换；仅更新本示例资源，保留其他资源的规则。
//        // shortcut: 规则仅保存在内存中；接入动态规则源时，应改由规则源统一管理。
//        List<DegradeRule> rules = new ArrayList<>(DegradeRuleManager.getRules());
//        rules.removeIf(existing -> RESOURCE.equals(existing.getResource()));
//        rules.add(rule);
//        DegradeRuleManager.loadRules(rules);
    }

    @PreDestroy
    public void removeStateChangeObserver() {
        EventObserverRegistry.getInstance().removeStateChangeObserver(OBSERVER_NAME);
    }

    private static void registerStateChangeObserver() {
        // 注册表是 JVM 全局的；使用唯一名称，并按资源过滤其他熔断器的事件。
        EventObserverRegistry.getInstance().addStateChangeObserver(OBSERVER_NAME,
            (prevState, newState, rule, snapshotValue) -> {
                if (!RESOURCE.equals(rule.getResource())) {
                    return;
                }
                // 回调在请求线程同步执行，应保持轻量，不要在此直接调用远程告警接口。
                // 本例为异常比例策略：进入 OPEN 的快照为异常比例，其余转换的快照为 null。
                logger.info("[状态事件] resource={}, {} -> {}, snapshotValue={}", rule.getResource(), prevState, newState, snapshotValue);
            });
    }

    @GetMapping(value = "/examples/circuit-state-change", produces = MediaType.TEXT_PLAIN_VALUE)
    @SentinelResource(value = RESOURCE, blockHandler = "handleBlocked", fallback = "handleBusinessFailure")
    public ResponseEntity<String> request(@RequestParam(defaultValue = "false") boolean fail) {
        // 由 Spring 代理调用此方法；业务异常交给切面记录，不能在方法内部吞掉。
        if (fail) {
            throw new IllegalStateException("模拟下游调用失败");
        }
        return ResponseEntity.ok("[业务成功] 模拟下游调用成功");
    }

    public ResponseEntity<String> handleBlocked(boolean fail, BlockException exception) {
        // Sentinel 拒绝不属于业务异常，不计入异常比例。
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body("[请求被拒绝] " + exception.getClass().getSimpleName());
    }

    public ResponseEntity<String> handleBusinessFailure(boolean fail, Throwable exception) {
        // 切面已记录业务异常，返回兜底结果不会抹掉该次失败。
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body("[业务失败] " + exception.getMessage());
    }
}
