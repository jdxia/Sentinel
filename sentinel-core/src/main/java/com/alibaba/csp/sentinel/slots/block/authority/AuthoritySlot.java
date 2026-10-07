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
package com.alibaba.csp.sentinel.slots.block.authority;

import java.util.List;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.system.SystemSlot;
import com.alibaba.csp.sentinel.spi.Spi;

/**
 * A {@link ProcessorSlot} that dedicates to {@link AuthorityRule} checking.
 *
 * @author leyou
 * @author Eric Zhao
 */
@Spi(order = Constants.ORDER_AUTHORITY_SLOT)
public class AuthoritySlot extends AbstractLinkedProcessorSlot<DefaultNode> {
    /**
     * 根据配置的黑白名单和调用来源信息，来做黑白名单控制
     */

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count, boolean prioritized, Object... args)
        throws Throwable {

        /**
         * resource：资源名，即限流规则的作用对象
         * limitApp：对应的黑名单/白名单，不同 origin 用 , 分隔，如 appA,appB
         * strategy：限制模式，AUTHORITY_WHITE 为白名单模式，AUTHORITY_BLACK 为黑名单模式，默认为白名单模式
         */

        /**
         * 看控制台那边有没有设置 黑白名单
         */
        checkBlackWhiteAuthority(resourceWrapper, context);

        /**
         * 调用下一个slot {@link SystemSlot}
         */
        fireEntry(context, resourceWrapper, node, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        fireExit(context, resourceWrapper, count, args);
    }

    void checkBlackWhiteAuthority(ResourceWrapper resource, Context context) throws AuthorityException {

        // 拿出授权规则
        List<AuthorityRule> rules = AuthorityRuleManager.getRules(resource.getName());

        // 没有就直接返回
        if (rules == null) {
            return;
        }

        for (AuthorityRule rule : rules) {
            // 如果设置了就进行验证, 这边会看请求到来源,  如果请求来源是空, 没设置好, 就直接跳过
            if (!AuthorityRuleChecker.passCheck(rule, context)) {
                throw new AuthorityException(context.getOrigin(), rule);
            }
        }
    }
}
