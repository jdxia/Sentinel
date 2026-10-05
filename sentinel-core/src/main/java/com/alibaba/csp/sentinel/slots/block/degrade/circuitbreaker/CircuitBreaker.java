/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;

/**
 * <p>Basic <a href="https://martinfowler.com/bliki/CircuitBreaker.html">circuit breaker</a> interface.</p>
 *
 * @author Eric Zhao
 */
public interface CircuitBreaker {
    /**
     * CircuitBreaker (接口)
     *     └── AbstractCircuitBreaker (抽象骨架: 状态机 + CAS 迁移 + 观察者通知)
     *             ├── ExceptionCircuitBreaker     (按异常比例 / 异常数熔断)
     *             └── ResponseTimeCircuitBreaker  (按慢调用比例熔断)
     *
     * 配套角色:
     *     DegradeRule                  规则定义(策略/阈值/时间窗)
     *     DegradeRuleManager           规则管理, 负责 DegradeRule -> CircuitBreaker 的构建与复用
     *     DegradeSlot                  常规熔断 Slot(dashboard / loadRules 走这里)
     *     DefaultCircuitBreakerSlot    全局兜底熔断 Slot(仅 API 设置, 且 DegradeRuleManager 无配置时才生效)
     *     CircuitBreakerStateChangeObserver / EventObserverRegistry   状态变更观察者(接监控告警)
     *
     * CircuitBreaker.java:62-80 三态经典模型：
     *
     *             指标超过阈值
     *   CLOSED ────────────────► OPEN
     *     ▲                        │  等待 timeWindow 秒后, 下一个请求触发
     *     │ 探测请求成功            ▼
     *     └──────────── HALF_OPEN ◄──┘
     *     探测请求失败 ──────────► OPEN (重新计时一个 timeWindow)
     *
     * - CLOSED：全放行，但每个请求完成后都会检查指标是否越限。
     * - OPEN：全拒绝（抛 DegradeException），直到 nextRetryTimestamp。
     * - HALF_OPEN：只放行一个探测请求，由它的结果决定回到 CLOSED 还是 OPEN。
     */

    /**
     * 返回关联的 DegradeRule
     *
     * Get the associated circuit breaking rule.
     *
     * @return associated circuit breaking rule
     */
    DegradeRule getRule();

    /**
     * 请求是否放行；OPEN 且未到恢复时间 → false → 抛 DegradeException
     *
     * Acquires permission of an invocation only if it is available at the time of invoking.
     *
     * @param context context of current invocation
     * @return {@code true} if permission was acquired and {@code false} otherwise
     */
    boolean tryPass(Context context);

    /**
     * 当前状态（OPEN / HALF_OPEN / CLOSED）
     *
     * Get current state of the circuit breaker.
     *
     * @return current state of the circuit breaker
     */
    State currentState();

    /**
     * 调用时机 exit 阶段（请求成功完成后）
     * 记录本次请求指标（RT / 异常），并驱动状态迁移
     *
     * <p>Record a completed request with the context and handle state transformation of the circuit breaker.</p>
     * <p>Called when a <strong>passed</strong> invocation finished.</p>
     *
     * @param context context of current invocation
     */
    void onRequestComplete(Context context);

    /**
     * Circuit breaker state.
     */
    enum State {
        /**
         * In {@code OPEN} state, all requests will be rejected until the next recovery time point.
         */
        OPEN,
        /**
         * In {@code HALF_OPEN} state, the circuit breaker will allow a "probe" invocation.
         * If the invocation is abnormal according to the strategy (e.g. it's slow), the circuit breaker
         * will re-transform to the {@code OPEN} state and wait for the next recovery time point;
         * otherwise the resource will be regarded as "recovered" and the circuit breaker
         * will cease cutting off requests and transform to {@code CLOSED} state.
         */
        HALF_OPEN,
        /**
         * In {@code CLOSED} state, all requests are permitted. When current metric value exceeds the threshold,
         * the circuit breaker will transform to {@code OPEN} state.
         */
        CLOSED
    }
}
