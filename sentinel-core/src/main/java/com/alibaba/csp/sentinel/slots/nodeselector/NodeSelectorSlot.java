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
package com.alibaba.csp.sentinel.slots.nodeselector;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.EntranceNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.alibaba.csp.sentinel.spi.Spi;

import java.util.HashMap;
import java.util.Map;

/**
 * </p>
 * This class will try to build the calling traces via
 * <ol>
 * <li>adding a new {@link DefaultNode} if needed as the last child in the context.
 * The context's last node is the current node or the parent node of the context. </li>
 * <li>setting itself to the context current node.</li>
 * </ol>
 * </p>
 *
 * <p>It works as follow:</p>
 * <pre>
 * ContextUtil.enter("entrance1", "appA");
 * Entry nodeA = SphU.entry("nodeA");
 * if (nodeA != null) {
 *     nodeA.exit();
 * }
 * ContextUtil.exit();
 * </pre>
 *
 * Above code will generate the following invocation structure in memory:
 *
 * <pre>
 *
 *              machine-root
 *                  /
 *                 /
 *           EntranceNode1
 *               /
 *              /
 *        DefaultNode(nodeA)- - - - - -> ClusterNode(nodeA);
 * </pre>
 *
 * <p>
 * Here the {@link EntranceNode} represents "entrance1" given by
 * {@code ContextUtil.enter("entrance1", "appA")}.
 * </p>
 * <p>
 * Both DefaultNode(nodeA) and ClusterNode(nodeA) holds statistics of "nodeA", which is given
 * by {@code SphU.entry("nodeA")}
 * </p>
 * <p>
 * The {@link ClusterNode} is uniquely identified by the ResourceId; the {@link DefaultNode}
 * is identified by both the resource id and {@link Context}. In other words, one resource
 * id will generate multiple {@link DefaultNode} for each distinct context, but only one
 * {@link ClusterNode}.
 * </p>
 * <p>
 * the following code shows one resource id in two different context:
 * </p>
 *
 * <pre>
 *    ContextUtil.enter("entrance1", "appA");
 *    Entry nodeA = SphU.entry("nodeA");
 *    if (nodeA != null) {
 *        nodeA.exit();
 *    }
 *    ContextUtil.exit();
 *
 *    ContextUtil.enter("entrance2", "appA");
 *    nodeA = SphU.entry("nodeA");
 *    if (nodeA != null) {
 *        nodeA.exit();
 *    }
 *    ContextUtil.exit();
 * </pre>
 *
 * Above code will generate the following invocation structure in memory:
 *
 * <pre>
 *
 *                  machine-root
 *                  /         \
 *                 /           \
 *         EntranceNode1   EntranceNode2
 *               /               \
 *              /                 \
 *      DefaultNode(nodeA)   DefaultNode(nodeA)
 *             |                    |
 *             +- - - - - - - - - - +- - - - - - -> ClusterNode(nodeA);
 * </pre>
 *
 * <p>
 * As we can see, two {@link DefaultNode} are created for "nodeA" in two context, but only one
 * {@link ClusterNode} is created.
 * </p>
 *
 * <p>
 * We can also check this structure by calling: <br/>
 * {@code curl http://localhost:8719/tree?type=root}
 * </p>
 *
 * @author jialiang.linjl
 * @see EntranceNode
 * @see ContextUtil
 */
@Spi(isSingleton = false, order = Constants.ORDER_NODE_SELECTOR_SLOT)
public class NodeSelectorSlot extends AbstractLinkedProcessorSlot<Object> {

    /**
     * 注意这边 isSingleton = false, 并不是让所有资源共用一个 NodeSelectorSlot 单例
     * 一个资源有多个 DefaultNode
     */

    /**
     * {@link DefaultNode}s of the same resource in different context.
     */
    private volatile Map<String, DefaultNode> map = new HashMap<String, DefaultNode>(10);

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, Object obj, int count, boolean prioritized, Object... args)
        throws Throwable {
        /*
         * It's interesting that we use context name rather resource name as the map key.
         *
         * Remember that same resource({@link ResourceWrapper#equals(Object)}) will share
         * the same {@link ProcessorSlotChain} globally, no matter in which context. So if
         * code goes into {@link #entry(Context, ResourceWrapper, DefaultNode, int, Object...)},
         * the resource name must be same but context name may not.
         *
         * If we use {@link com.alibaba.csp.sentinel.SphU#entry(String resource)} to
         * enter same resource in different context, using context name as map key can
         * distinguish the same resource. In this case, multiple {@link DefaultNode}s will be created
         * of the same resource name, for every distinct context (different context name) each.
         *
         * Consider another question. One resource may have multiple {@link DefaultNode},
         * so what is the fastest way to get total statistics of the same resource?
         * The answer is all {@link DefaultNode}s with same resource name share one
         * {@link ClusterNode}. See {@link ClusterBuilderSlot} for detail.
         *
         * 有趣的是，我们使用上下文名称而不是资源名称作为映射键。
         * 请记住，相同的资源{@link ResourceWrapper#equals(Object)} 将共享
         * 无论在何种上下文中，全局都使用相同的{@link ProcessorSlotChain}。
         * 所以如果代码进入 {@link #entry(Context, ResourceWrapper, DefaultNode, int, Object...)},资源名称必须相同，但上下文名称不能相同。
         * 如果我们使用 {@link com.alibaba.csp.sentinel.SphU#entry(String resource)}
         * 在不同的上下文中输入相同的资源，使用上下文名称作为映射键可以区分同一资源。在这种情况下，将创建多个 {@link DefaultNode},每个不同的上下文（不同的上下文名称）都有相同的资源名称。
         *
         * 考虑另一个问题。一个资源可以具有多个 {@link DefaultNode},那么，获取同一资源的总统计数据的最快方法是什么？
         * 答案是所有具有相同资源名称的 {@link DefaultNode} 共享一个｛@link ClusterNode｝。有关详细信息，请参阅{@link ClusterBuilderSlot}。
         */

        /**
         * 进入这个 entry 时，资源名相同，但 Context 名可能不同
         * 因此，当不同调用进入同一个 NodeSelectorSlot 实例的 entry 方法时，它们的资源名称相同，但上下文名称可能不同。
         * 所以对这个 Slot 来说：
         * 资源这一维：已经确定是 nodeA
         * Context 这一维：还需要区分 entrance1、entrance2
         *
         * 从缓存里面获取, 为啥要 用 context名字, 如果用资源名作为 key，在这条链里查来查去都只会查 "nodeA"：
         * nodeA → 一个 DefaultNode
         * 这样就无法分别统计 "entrance1" 和 "entrance2" 下的 nodeA。
         * 一个资源有多个 DefaultNode
         *
         *
         * context 是整个链条的上下文
         *
         * Context：我这次请求是从“哪条入口链路”进来的。
         * DefaultNode：某个资源在“某条链路”上的统计节点。
         *
         * ClusterNode  = resource
         * DefaultNode  = resource × context
         */
        DefaultNode node = map.get(context.getName());
        if (node == null) {
            synchronized (this) {
                node = map.get(context.getName());
                if (node == null) {
                    // 没有就构造1个
                    // 这个 DefaultNode 继承 StatisticNode 统计节点
                    node = new DefaultNode(resourceWrapper, null);
                    HashMap<String, DefaultNode> cacheMap = new HashMap<String, DefaultNode>(map.size());
                    cacheMap.putAll(map);
                    cacheMap.put(context.getName(), node);
                    map = cacheMap;
                    // Build invocation tree
                    ((DefaultNode) context.getLastNode()).addChild(node);
                }

            }
        }

        /**
         * 设置到上下文
         */
        context.setCurNode(node);

        /**
         * 下个slot是 {@link ClusterBuilderSlot#entry(Context, ResourceWrapper, DefaultNode, int, boolean, Object...)}
         */
        fireEntry(context, resourceWrapper, node, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        fireExit(context, resourceWrapper, count, args);
    }
}
