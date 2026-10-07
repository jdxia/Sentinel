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
package com.alibaba.csp.sentinel.slots.statistic;

import java.util.Collection;

import com.alibaba.csp.sentinel.metric.extension.callback.MetricEntryCallback;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotEntryCallback;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotExitCallback;
import com.alibaba.csp.sentinel.slots.block.authority.AuthoritySlot;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.spi.Spi;
import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * <p>
 * A processor slot that dedicates to real time statistics.
 * When entering this slot, we need to separately count the following
 * information:
 * <ul>
 * <li>{@link ClusterNode}: total statistics of a cluster node of the resource ID.</li>
 * <li>Origin node: statistics of a cluster node from different callers/origins.</li>
 * <li>{@link DefaultNode}: statistics for specific resource name in the specific context.</li>
 * <li>Finally, the sum statistics of all entrances.</li>
 * </ul>
 * </p>
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
@Spi(order = Constants.ORDER_STATISTIC_SLOT)
public class StatisticSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    /**
     * 统计slot, 用于记录、统计不同纬度的 runtime 指标监控信息
     *
     * 进入时，先执行后续规则检查，再记录通过量和在途调用数。
     * 被规则拦截时，记录 block；正常放行的调用在退出时记录 RT、完成量和异常量。
     * StatisticSlot 负责组织统计，数据实际保存在 Node 中
     */

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        try {
            // Do some checking.
            /**
             * 先调用下一个slot
             * {@link AuthoritySlot}
             */
            fireEntry(context, resourceWrapper, node, count, prioritized, args);

            /**
             * 如果被流控就走不到下面
             */

            // Request passed, add thread count and pass count.
            // 线程数新增1个, 增加 1 个已进入、尚未退出的调用
            node.increaseThreadNum();
            // QPS, 增加 count 个通过量，普通调用一般是 1，批量申请可以大于 1
            node.addPassRequest(count);

            if (context.getCurEntry().getOriginNode() != null) {
                // Add count for origin node.
                context.getCurEntry().getOriginNode().increaseThreadNum();
                context.getCurEntry().getOriginNode().addPassRequest(count);
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseThreadNum();
                Constants.ENTRY_NODE.addPassRequest(count);
            }

            // Handle pass event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                /**
                 * Dashboard 展示的数据：MetricTimerListener（sentinel-core）定时从滑动窗口采样 → MetricWriter 写 {app}-metrics.log 文件 → sentinel-transport 的 SendMetricCommandHandler 响应 Dashboard 的 /metric 拉取请求
                 * 不是 MetricEntryCallback#onPass
                 *
                 * 第三方扩展（自定义指标上报）用的 SPI 钩子, 自己实现 MetricExtension，把 pass/block/RT 等事件推到自家的监控系统
                 * {@link MetricEntryCallback#onPass(Context, ResourceWrapper, DefaultNode, int, Object...)}
                 *
                 * 热点参数维度的并发线程数 +1，按参数值存进 ParameterMetric.threadCountMap
                 * 注意, 热点参数的 exit要带参数, 不然参数那边减不掉
                 * {@link com.alibaba.csp.sentinel.slots.statistic.ParamFlowStatisticEntryCallback#onPass(com.alibaba.csp.sentinel.context.Context, com.alibaba.csp.sentinel.slotchain.ResourceWrapper, com.alibaba.csp.sentinel.node.DefaultNode, int, java.lang.Object...)}
                 */
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (PriorityWaitException ex) {
            /**
             * FlowSlot 的特例：prioritized 请求占用下一周期令牌后放行
             *
             * 只加线程数，不算 pass
             */

            node.increaseThreadNum();
            if (context.getCurEntry().getOriginNode() != null) {
                // Add count for origin node.
                context.getCurEntry().getOriginNode().increaseThreadNum();
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseThreadNum();
            }
            // Handle pass event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (BlockException e) {

            /**
             * 被规则拦截
             * setBlockError(e) + increaseBlockQps(count)，然后 throw e 重新抛出
             * 这边是异常标记的生产方
             */

            // Blocked, set block exception to current entry.
            context.getCurEntry().setBlockError(e);

            // Add block count.
            node.increaseBlockQps(count);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseBlockQps(count);
            }

            if (resourceWrapper.getEntryType() == EntryType.IN) {
                // Add count for global inbound entry node for global statistics.
                Constants.ENTRY_NODE.increaseBlockQps(count);
            }

            // Handle block event with registered entry callback handlers.
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onBlocked(e, context, resourceWrapper, node, count, args);
            }

            throw e;
        } catch (Throwable e) {
            // Unexpected internal error, set error to current entry.
            context.getCurEntry().setError(e);

            throw e;
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        Node node = context.getCurNode();

        // 只统计"放行过"的请求
        if (context.getCurEntry().getBlockError() == null) {
            // Calculate response time (use completeStatTime as the time of completion).
            long completeStatTime = TimeUtil.currentTimeMillis();
            context.getCurEntry().setCompleteTimestamp(completeStatTime);
            long rt = completeStatTime - context.getCurEntry().getCreateTimestamp();

            // 取出异常, 读 Tracer.trace 打的标记
            Throwable error = context.getCurEntry().getError();

            // error != null → increaseExceptionQps
            // Record response time and success count.
            recordCompleteFor(node, count, rt, error);
            recordCompleteFor(context.getCurEntry().getOriginNode(), count, rt, error);
            if (resourceWrapper.getEntryType() == EntryType.IN) {
                recordCompleteFor(Constants.ENTRY_NODE, count, rt, error);
            }
        }

        /**
         * 这边是异常标记的消费方
         */

        // Handle exit event with registered exit callback handlers.
        Collection<ProcessorSlotExitCallback> exitCallbacks = StatisticSlotCallbackRegistry.getExitCallbacks();
        for (ProcessorSlotExitCallback handler : exitCallbacks) {
            handler.onExit(context, resourceWrapper, count, args);
        }

        // fix bug https://github.com/alibaba/Sentinel/issues/2374
        fireExit(context, resourceWrapper, count, args);
    }

    private void recordCompleteFor(Node node, int batchCount, long rt, Throwable error) {
        if (node == null) {
            return;
        }

        // RT 和成功数：不管有没有异常都记
        node.addRtAndSuccess(rt, batchCount);

        // 收回 entry 时加的线程数
        node.decreaseThreadNum();

        if (error != null && !(error instanceof BlockException)) {
            // 有标记且非 BlockException → 记异常数
            node.increaseExceptionQps(batchCount);
        }
    }
}
