/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.degrade;

import java.util.List;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreaker;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.ExceptionCircuitBreaker;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.ResponseTimeCircuitBreaker;
import com.alibaba.csp.sentinel.spi.Spi;

/**
 * A {@link ProcessorSlot} dedicates to circuit breaking.
 *
 * @author Carpenter Lee
 * @author Eric Zhao
 */
@Spi(order = Constants.ORDER_DEGRADE_SLOT)
public class DegradeSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        /**
         * 这个是dashboard配置的
         */

        /**
         * Field	说明	默认值
         * resource	资源名，即规则的作用对象
         * grade	熔断策略，支持慢调用比例/异常比例/异常数策略	慢调用比例
         * count	慢调用比例模式下为慢调用临界 RT（超出该值计为慢调用）；异常比例/异常数模式下为对应的阈值
         * timeWindow	熔断时长，单位为 s
         * minRequestAmount	熔断触发的最小请求数，请求数小于该值时即使异常比率超出阈值也不会熔断（1.7.0 引入）	5
         * statIntervalMs	统计时长（单位为 ms），如 60*1000 代表分钟级（1.8.0 引入）	1000 ms
         * slowRatioThreshold	慢调用比例阈值，仅慢调用比例模式有效（1.8.0 引入）
         */

        // 往下
        performChecking(context, resourceWrapper);

        /**
         * 下面就没有了, 到业务代码了
         */
        fireEntry(context, resourceWrapper, node, count, prioritized, args);

        /**
         * 有些slot的代码是写在 fireEntry 这个时候会按照顺序执行
         */
    }

    void performChecking(Context context, ResourceWrapper r) throws BlockException {
        List<CircuitBreaker> circuitBreakers = DegradeRuleManager.getCircuitBreakers(r.getName());
        if (circuitBreakers == null || circuitBreakers.isEmpty()) {
            return;
        }

        /**
         *  可以看 {@link CircuitBreaker} 里面的注释
         *
         */
        for (CircuitBreaker cb : circuitBreakers) {
            if (!cb.tryPass(context)) {
                throw new DegradeException(cb.getRule().getLimitApp(), cb.getRule());
            }
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper r, int count, Object... args) {
        Entry curEntry = context.getCurEntry();

        // 看这个请求有没有抛异常 BlockException
        if (curEntry.getBlockError() != null) {
            fireExit(context, r, count, args);
            return;
        }

        // 获取这个限流对应的熔断规则
        List<CircuitBreaker> circuitBreakers = DegradeRuleManager.getCircuitBreakers(r.getName());

        // 如果没有配置
        if (circuitBreakers == null || circuitBreakers.isEmpty()) {
            fireExit(context, r, count, args);
            return;
        }

        // 如果没有 抛 BlockException, 并且配置了 熔断规则, 走熔断规则的 请求完成处理
        if (curEntry.getBlockError() == null) {
            // passed request
            for (CircuitBreaker circuitBreaker : circuitBreakers) {
                /**
                 * CircuitBreaker (接口)
                 *     └── AbstractCircuitBreaker (抽象骨架: 状态机 + CAS 迁移 + 观察者通知)
                 *             ├── ExceptionCircuitBreaker     (按异常比例 / 异常数熔断)
                 *             └── ResponseTimeCircuitBreaker  (按慢调用比例熔断)
                 *
                 * {@link ExceptionCircuitBreaker#onRequestComplete(Context)}
                 * {@link ResponseTimeCircuitBreaker#onRequestComplete(Context)}
                 */
                circuitBreaker.onRequestComplete(context);
            }
        }

        fireExit(context, r, count, args);
    }
}
