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
package com.alibaba.csp.sentinel.slotchain;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.context.Context;

/**
 * A container of some process and ways of notification when the process is finished.
 *
 * @author qinan.qn
 * @author jialiang.linjl
 * @author leyou(lihao)
 * @author Eric Zhao
 */
public interface ProcessorSlot<T> {

    /**
     * ProcessorSlot<T> (接口)
     * ├── AbstractLinkedProcessorSlot<T>          // 抽象基类：持有 next 指针，实现链表串联
     * │   ├── NodeSelectorSlot、ClusterBuilderSlot、LogSlot、StatisticSlot
     * │   ├── AuthoritySlot、SystemSlot、FlowSlot、DegradeSlot、DefaultCircuitBreakerSlot   (以上在 sentinel-core)
     * │   ├── ParamFlowSlot                        // sentinel-extension/sentinel-parameter-flow-control
     * │   ├── GatewayFlowSlot                      // sentinel-adapter/sentinel-api-gateway-adapter-common
     * │   └── DemoSlot                             // sentinel-demo（示例）
     * └── ProcessorSlotChain (接口) → DefaultProcessorSlotChain    // 链容器，含 first/end 哨兵节点
     *
     * 顺序由 Constants 的 ORDER_* 常量 + SPI 文件控制，DefaultSlotChainBuilder 按 @Spi(order) 升序串链
     *
     * ┌────────┬─────────────────────────────────────────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
     * │  顺序  │                        Slot                               │                                                                             职责                                                                              │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -10000 │ NodeSelectorSlot                                          │ 构建统计节点树：为每个资源在当前 Context 下创建/缓存 DefaultNode，挂到调用链路上                                                                              │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -9000  │ ClusterBuilderSlot                                        │ 为资源创建 ClusterNode（跨 Context 的全局统计节点），并维护 origin（调用来源）维度统计                                                                        │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -8000  │ LogSlot                                                   │ 被拦截时记录 BlockException 日志（RecordLog）                                                                                                                 │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -7000  │ StatisticSlot                                             │ 核心统计：请求通过后记录线程数 +1、pass QPS；exit 时记录 RT 和 success；被拦截/异常时记录 block/exception。它不拦截请求，只做数据记录（依赖前面 slot 已放行） │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -6000  │ AuthoritySlot                                             │ 来源访问控制（黑白名单）：按 AuthorityRule 校验 origin，不通过抛 AuthorityException                                                                           │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -5000  │ SystemSlot                                                │ 系统自适应保护：按 SystemRule 检查整体 Load、CPU、平均 RT、总 QPS、总并发线程数，超标抛 SystemBlockException                                                  │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -3000  │ ParamFlowSlot（需引入 parameter-flow-control 依赖）        │ 热点参数限流：按方法参数值维度做 QPS 控制，抛 ParamFlowException                                                                                              │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -2000  │ FlowSlot                                                  │ 流量控制：按 FlowRule 做 QPS / 并发线程数控制（支持匀速排队、预热冷启动），超标抛 FlowException                                                               │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -1500  │ DefaultCircuitBreakerSlot（@since 2.0.0）                 │ 默认熔断：对未显式配置 DegradeRule 的资源，按 DefaultCircuitBreakerRuleManager 提供兜底熔断能力                                                               │
     * ├────────┼─────────────────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
     * │ -1000  │ DegradeSlot                                               │ 熔断降级：按 DegradeRule 驱动 CircuitBreaker 状态机（慢调用比例 / 异常比例 / 异常数策略），熔断打开时抛 DegradeException                                      │
     * └────────┴─────────────────────────────────────────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
     *
     * 数据准备必须最先：NodeSelectorSlot 和 ClusterBuilderSlot 负责把统计节点挂到 Context 上，后面所有 slot（尤其是规则检查）都从这些节点读运行时数据，所以排最前两位
     * LogSlot 卡在规则检查前面是为了"截获"：它先 fireEntry 放行，在 catch (BlockException e) 里写 EagleEye 日志再重新抛出——排在所有规则 slot 之前，才能捕获到它们抛出的任何阻断异常
     * StatisticSlot 的"先放行后统计"模型, 所以它必须在所有规则 slot 之前，才能统计到"通过/被拒"两种结果
     * 规则检查类的内部排序：授权（能不能访问）→ 系统级兜底（机器整体健康度）→ 单资源限流 → 熔断。全局性检查靠前，资源级检查靠后
     *
     * 最终链的执行流向
     * entry:  NodeSelector → ClusterBuilder → Log → Statistic → Authority → System → ParamFlowSlot → Flow → DefaultCircuitBreakerSlot → Degrade → (业务代码)
     * exit:   也是同样的方向, 同向遍历, 业务 RT 在 StatisticSlot.exit 记录
     * 参考：{@link Constants}
     *
     * 另外两个扩展模块的 slot 通过各自 jar 的 META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot SPI 文件注册（core、parameter-flow-control、api-gateway-adapter-common 各有一份，classpath 上合并去重）：
     * - ParamFlowSlot — 热点参数限流（配合 @SentinelResource 的参数维度规则）
     * - GatewayFlowSlot — 网关流控，针对 API Gateway 路由/API 分组的限流规则，供 Spring Cloud Gateway、Zuul 等适配器使用
     *
     * 链的构建方式
     * DefaultSlotChainBuilder.build()
     * 通过 SpiLoader.of(ProcessorSlot.class).loadInstanceListSorted() 加载 classpath 上所有 SPI 实现并按 order 排序，逐个 addLast 串成链。
     * 因此自定义 slot 只需继承 AbstractLinkedProcessorSlot + @Spi(order=...) + SPI 注册文件即可插入链中（sentinel-demo-slot-spi 有完整示例）
     * 或者看 com.alibaba.csp.sentinel.slots.logger.LogSlot
     * 注意: fireEntry 所有的slot都执行完, 就执行 fireEntry 下面, entry里面的代码, entry里面的代码都执行完, 才执行业务代码
     *
     * 整体设计上前 4 个 slot（NodeSelector → ClusterBuilder → Log → Statistic）负责"统计基础设施"，后面的（Authority → System → ParamFlow → Flow → 熔断）负责"规则判断"，
     * 判断类 slot 依赖统计类 slot 已准备好的 Node 数据
     */

    /**
     * Entrance of this slot.
     *
     * @param context         current {@link Context}
     * @param resourceWrapper current resource
     * @param param           generics parameter, usually is a {@link com.alibaba.csp.sentinel.node.Node}
     * @param count           tokens needed
     * @param prioritized     whether the entry is prioritized
     * @param args            parameters of the original call
     * @throws Throwable blocked exception or unexpected error
     */
    void entry(Context context, ResourceWrapper resourceWrapper, T param, int count, boolean prioritized,
               Object... args) throws Throwable;

    /**
     * Means finish of {@link #entry(Context, ResourceWrapper, Object, int, boolean, Object...)}.
     *
     * @param context         current {@link Context}
     * @param resourceWrapper current resource
     * @param obj             relevant object (e.g. Node)
     * @param count           tokens needed
     * @param prioritized     whether the entry is prioritized
     * @param args            parameters of the original call
     * @throws Throwable blocked exception or unexpected error
     */
    void fireEntry(Context context, ResourceWrapper resourceWrapper, Object obj, int count, boolean prioritized,
                   Object... args) throws Throwable;

    /**
     * Exit of this slot.
     *
     * @param context         current {@link Context}
     * @param resourceWrapper current resource
     * @param count           tokens needed
     * @param args            parameters of the original call
     */
    void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args);

    /**
     * Means finish of {@link #exit(Context, ResourceWrapper, int, Object...)}.
     *
     * @param context         current {@link Context}
     * @param resourceWrapper current resource
     * @param count           tokens needed
     * @param args            parameters of the original call
     */
    void fireExit(Context context, ResourceWrapper resourceWrapper, int count, Object... args);
}
