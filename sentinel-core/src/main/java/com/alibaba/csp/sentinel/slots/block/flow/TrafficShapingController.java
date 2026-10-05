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
package com.alibaba.csp.sentinel.slots.block.flow;

import com.alibaba.csp.sentinel.node.Node;

/**
 * A universal interface for traffic shaping controller.
 *
 * @author jialiang.linjl
 */
public interface TrafficShapingController {
    /**
     * 流量整形器, 根据 控制行为来决定选那个子类
     * DefaultController   快速失败
     * WarmUpController    预热/冷启动
     * ThrottlingController  匀速排队（漏桶）
     * WarmUpRateLimiterController   预热 + 排队
     * grade = THREAD（线程数模式）  一律 DefaultController  按并发线程数限
     *
     * 一个容易忽略的点：canPass 不保证不阻塞。返回值只有 true/false，但排队类实现（Throttling / WarmUpRateLimiter）会在内部 sleep/parkNanos 后返回 true；
     * DefaultController 的优先级路径甚至直接抛 PriorityWaitException（表示"等待后放行"，StatisticSlot 会单独 catch，不计入 block）
     *
     */

    /**
     * 核心方法。判断本次请求（占 acquireCount 个令牌）能否通过。返回 true 放行，false 拒绝（由 FlowSlot 抛出 FlowException）
     * node：统计节点，提供 passQps()、previousPassQps()、curThreadNum() 等滑动窗口数据。由 FlowRuleChecker.selectNodeByRequesterAndStrategy 根据 limitApp/strategy 选出（origin 节点、clusterNode 或关联资源节点）——所以限的"是谁"，是选 node 时决定的，控制器只管"怎么限"
     * acquireCount：本次申请的令牌数，一般业务请求是 1，批量场景（如一次消息处理 N 条）会大于 1。
     * prioritized：是否优先级请求，只有 DefaultController + QPS 模式实现了"占用未来令牌"的逻辑。
     *
     * Check whether given resource entry can pass with provided count.
     *
     * @param node resource node
     * @param acquireCount count to acquire
     * @param prioritized whether the request is prioritized
     * @return true if the resource entry can pass; false if it should be blocked
     */
    boolean canPass(Node node, int acquireCount, boolean prioritized);

    /**
     * 等价于 canPass(node, acquireCount, false)，非优先级请求
     *
     * Check whether given resource entry can pass with provided count.
     *
     * @param node resource node
     * @param acquireCount count to acquire
     * @return true if the resource entry can pass; false if it should be blocked
     */
    boolean canPass(Node node, int acquireCount);
}
