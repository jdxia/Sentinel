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
package com.alibaba.csp.sentinel;

import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.EntranceNode;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;
import com.alibaba.csp.sentinel.util.VersionUtil;

/**
 * Universal constants of Sentinel.
 *
 * @author qinan.qn
 * @author youji.zj
 * @author jialiang.linjl
 * @author Eric Zhao
 */
public final class Constants {

    public static final String SENTINEL_VERSION = VersionUtil.getVersion("1.8.9");

    public final static int MAX_CONTEXT_NAME_SIZE = 2000;
    public final static int MAX_SLOT_CHAIN_SIZE = 6000;

    public final static String ROOT_ID = "machine-root";
    public final static String CONTEXT_DEFAULT_NAME = "sentinel_default_context";

    /**
     * A virtual resource identifier for total inbound statistics (since 1.5.0).
     */
    public final static String TOTAL_IN_RESOURCE_NAME = "__total_inbound_traffic__";

    /**
     * A virtual resource identifier for cpu usage statistics (since 1.6.1).
     */
    public final static String CPU_USAGE_RESOURCE_NAME = "__cpu_usage__";

    /**
     * A virtual resource identifier for system load statistics (since 1.6.1).
     */
    public final static String SYSTEM_LOAD_RESOURCE_NAME = "__system_load__";

    /**
     * Global ROOT statistic node that represents the universal parent node.
     */
    public final static DefaultNode ROOT = new EntranceNode(new StringResourceWrapper(ROOT_ID, EntryType.IN),
        new ClusterNode(ROOT_ID, ResourceTypeConstants.COMMON));

    /**
     * Global statistic node for inbound traffic. Usually used for {@code SystemRule} checking.
     */
    public final static ClusterNode ENTRY_NODE = new ClusterNode(TOTAL_IN_RESOURCE_NAME, ResourceTypeConstants.COMMON);

    /**
     * The global switch for Sentinel.
     */
    public static volatile boolean ON = true;

    /**
     * Order of default processor slots
     */

    /**
     * 为当前 Context 创建/获取该资源的 DefaultNode（调用树节点）
     * NodeSelectorSlot
     */
    public static final int ORDER_NODE_SELECTOR_SLOT = -10000;

    /**
     * 构建资源的 ClusterNode（跨 Context 的全局聚合统计节点）和按调用来源区分的 origin 节点
     * ClusterBuilderSlot
     */
    public static final int ORDER_CLUSTER_BUILDER_SLOT = -9000;

    /**
     * 把后面 slot 抛出的 BlockException 写入 block 日志（sentinel-block.log）
     * LogSlot
     */
    public static final int ORDER_LOG_SLOT = -8000;

    /**
     * 核心统计入口：记录 pass/block、线程数、RT、success、exception
     * StatisticSlot
     */
    public static final int ORDER_STATISTIC_SLOT = -7000;

    /**
     * 黑白名单授权规则：按来源 origin 校验，不通过抛 AuthorityException
     * AuthoritySlot
     */
    public static final int ORDER_AUTHORITY_SLOT = -6000;

    /**
     * 系统自适应保护（SystemRule）：按入口整体 Load、CPU、平均 RT、QPS、并发线程数做准入
     * SystemSlot
     */
    public static final int ORDER_SYSTEM_SLOT = -5000;

    /**
     * 流量控制（FlowRule）：QPS/并发线程数限流，含快速失败、Warm Up、匀速排队
     * FlowSlot
     */
    public static final int ORDER_FLOW_SLOT = -2000;

    /**
     * 熔断器状态检查（tryPass）：判断熔断器是否 OPEN、放行半开探测请求
     * DefaultCircuitBreakerSlot
     */
    public static final int ORDER_DEFAULT_CIRCUIT_BREAKER_SLOT = -1500;

    /**
     * 熔断降级规则（DegradeRule）：慢调用比例、异常比例、异常数触发熔断
     * DegradeSlot
     */
    public static final int ORDER_DEGRADE_SLOT = -1000;

    private Constants() {}
}
