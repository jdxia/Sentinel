/*
 * Copyright 1999-2022 Alibaba Group Holding Ltd.
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

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreaker;
import com.alibaba.csp.sentinel.spi.Spi;

import java.util.List;

/**
 * <p>A {@link ProcessorSlot} dedicates to universal default circuit breaker.</p>
 *
 * @author wuwen
 * @since 2.0.0
 */
@Spi(order = Constants.ORDER_DEFAULT_CIRCUIT_BREAKER_SLOT)
public class DefaultCircuitBreakerSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        /**
         * 这个和 DegradeSlot 区别是
         * 这个熔断slot只能 api设置, dashboard是不走这个的
         *
         * dashboard是走的 DegradeSlot
         *
         * DefaultCircuitBreakerSlot 是给框架/中间件团队在 SDK 层预埋全局兜底熔断用的预留扩展点（比如公司内部封装的 starter 想让所有资源默认都有熔断保护）, 还可以设置一些指定资源排除的
         * 这个适合设置全局的
         */

        // 往下
        performChecking(context, resourceWrapper);

        /**
         * 下一个slot是 {@link DegradeSlot}
         */
        fireEntry(context, resourceWrapper, node, count, prioritized, args);
    }

    private void performChecking(Context context, ResourceWrapper r) throws BlockException {
        // If user has set a degrade rule for the resource, the default rule will not be activated

        /**
         * 如果是 这个规则管理器 就跳过, 这个是 DegradeSlot 的, 不是这个的
         *
         * 如果是 DegradeSlot规则加载器设置的, 那全局的不生效
         */
        if (DegradeRuleManager.hasConfig(r.getName())) {
            return;
        }

        /**
         * 找出所有的熔断器
         * 可以看 {@link CircuitBreaker} 里面的注释
         */
        List<CircuitBreaker> circuitBreakers = DefaultCircuitBreakerRuleManager.getDefaultCircuitBreakers(r.getName());

        if (circuitBreakers == null || circuitBreakers.isEmpty()) {
            return;
        }

        for (CircuitBreaker cb : circuitBreakers) {
            if (!cb.tryPass(context)) {
                /**
                 * 熔断降级的异常
                 */
                throw new DegradeException(cb.getRule().getLimitApp(), cb.getRule());
            }
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper r, int count, Object... args) {
        Entry curEntry = context.getCurEntry();
        if (curEntry.getBlockError() != null) {
            fireExit(context, r, count, args);
            return;
        }

        if (DegradeRuleManager.hasConfig(r.getName())) {
            fireExit(context, r, count, args);
            return;
        }

        List<CircuitBreaker> circuitBreakers = DefaultCircuitBreakerRuleManager.getDefaultCircuitBreakers(r.getName());

        if (circuitBreakers == null || circuitBreakers.isEmpty()) {
            fireExit(context, r, count, args);
            return;
        }

        if (curEntry.getBlockError() == null) {
            // passed request
            for (CircuitBreaker circuitBreaker : circuitBreakers) {
                circuitBreaker.onRequestComplete(context);
            }
        }

        fireExit(context, r, count, args);
    }
}
