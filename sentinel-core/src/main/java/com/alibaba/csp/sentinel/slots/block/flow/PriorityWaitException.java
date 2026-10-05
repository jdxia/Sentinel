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

/**
 * An exception that marks previous prioritized request has been waiting till now, then should pass.
 *
 * @author jialiang.linjl
 * @since 1.5.0
 */
public class PriorityWaitException extends RuntimeException {
    /**
     * 它表示：一个带优先级的请求已经排队等待完毕，现在可以放行。注意它不是 BlockException 的子类，而是普通 RuntimeException——因为它不代表"被拒绝"，恰恰相反，代表"等到了、放行"。
     * 它是一个用异常做控制流信号的典型设计：跨 slot 链路告诉 StatisticSlot"这个请求是排队等完才放行的，别重复记 pass 指标，按成功处理"，从而与正常放行、BlockException 拒绝两条路径区分开。
     *
     * 这个不代表请求没被处理, 是被处理了
     *
     * 排队太久被拒 是抛 FlowException（BlockException 子类）抛给调用方
     */

    private final long waitInMs;

    public PriorityWaitException(long waitInMs) {
        this.waitInMs = waitInMs;
    }

    public long getWaitInMs() {
        return waitInMs;
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
