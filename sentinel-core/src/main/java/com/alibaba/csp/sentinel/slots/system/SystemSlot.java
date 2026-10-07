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
package com.alibaba.csp.sentinel.slots.system;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.spi.Spi;

/**
 * A {@link ProcessorSlot} that dedicates to {@link SystemRule} checking.
 *
 * @author jialiang.linjl
 * @author leyou
 */
@Spi(order = Constants.ORDER_SYSTEM_SLOT)
public class SystemSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {

        /**
         * Field	说明	默认值
         * highestSystemLoad	load1 触发值，用于触发自适应控制阶段	-1 (不生效)
         * avgRt	所有入口流量的平均响应时间	-1 (不生效)
         * maxThread	入口流量的最大并发数	-1 (不生效)
         * qps	所有入口资源的 QPS	-1 (不生效)
         * highestCpuUsage	当前系统的 CPU 使用率（0.0-1.0）	-1 (不生效)
         */


        /**
         * 系统总共的阈值规则
         */
        SystemRuleManager.checkSystem(resourceWrapper, count);

        /**
         * 下一个slot
         * {@link com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowSlot}
         */
        fireEntry(context, resourceWrapper, node, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        fireExit(context, resourceWrapper, count, args);
    }

}
