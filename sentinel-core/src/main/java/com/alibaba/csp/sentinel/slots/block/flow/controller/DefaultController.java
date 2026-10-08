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
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.node.OccupyTimeoutProperty;
import com.alibaba.csp.sentinel.node.StatisticNode;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * Default throttling controller (immediately reject strategy).
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
public class DefaultController implements TrafficShapingController {

    private static final int DEFAULT_AVG_USED_TOKENS = 0;

    private double count;
    private int grade;

    public DefaultController(double count, int grade) {
        this.count = count;
        this.grade = grade;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        /**
         * 这边有优先级
         *
         * web dashboard配置, 走AbstractSentinelInterceptor#preHandle 这个的话, 限流都是不优先的
         */


        /**
         * QPS 模式取 node.passQps()，线程模式取 curThreadNum()
         */
        int curCount = avgUsedTokens(node);

        /**
         * curCount 是当前的总数
         * acquireCount 是要申请的
         * 如果大于设置的总数
         */
        if (curCount + acquireCount > count) {

            /**
             * 这个类是 直接拒绝策略
             * QPS + 显示api调用优先级限流
             *
             * 优先级限流 借的是这个资源的未来配额，影响该资源的所有后续请求，不分调用方、不分 context
             */
            if (prioritized && grade == RuleConstant.FLOW_GRADE_QPS) {
                long currentTime;
                long waitInMs;
                currentTime = TimeUtil.currentTimeMillis();

                /**
                 * 算"要等多久" {@link StatisticNode#tryOccupyNext(long, int, double)}
                 *
                 * 它从窗口里最早的 bucket 往现在扫描：假设把本请求记到未来的某个 bucket，
                 * 扣掉届时将滑出窗口的老 bucket 的量（- windowPass），窗口总量是否仍 ≤ maxCount。
                 * 找到第一个装得下的未来窗口，返回"等到那个窗口需要多少毫秒"（waitInMs）；
                 * 装不下或超过 occupyTimeout，就返回 500（= 借不到，回到 DefaultController.java 的判断，走拒绝）
                 */
                waitInMs = node.tryOccupyNext(currentTime, acquireCount, count);

                /**
                 * 写"借据"
                 */

                /**
                 * waitInMs 500 是 occupyTimeout 的默认值，是借用的上限（等待超过它就失败、直接拒绝），不是返回值
                 * 成功借用的 waitInMs 一定是 (0, 500) 区间内的某个格子对齐值；返回值恰好等于 500 就表示失败
                 *
                 * 这种sleep
                 */
                if (waitInMs < OccupyTimeoutProperty.getOccupyTimeout()) {
                    // 预扣未来窗口配额
                    node.addWaitingRequest(currentTime + waitInMs, acquireCount);
                    // 记一笔"借来的通过量"
                    node.addOccupiedPass(acquireCount);
                    // 睡到那个未来窗口
                    sleep(waitInMs);

                    /**
                     * 通知上层：等完会放行
                     * PriorityWaitException 不是失败：它沿 chain 冒泡，被排在 FlowSlot 前面的 StatisticSlot 捕获吞掉 ——只加线程数、不记 pass（因为 pass 已预扣到未来窗口，避免重复计数），然后业务代码正常执行。业务方无感知，最多觉得这次调用慢了几百毫秒
                     */
                    // PriorityWaitException indicates that the request will pass after waiting for {@link @waitInMs}.
                    throw new PriorityWaitException(waitInMs);
                }
            }

            // 要抛异常
            return false;
        }

        // 没超过
        return true;
    }

    private int avgUsedTokens(Node node) {
        if (node == null) {
            return DEFAULT_AVG_USED_TOKENS;
        }
        /**
         * 看按线程数来还是QPS来
         */
        return grade == RuleConstant.FLOW_GRADE_THREAD ? node.curThreadNum() : (int)(node.passQps());
    }

    private void sleep(long timeMillis) {
        try {
            Thread.sleep(timeMillis);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }
}
