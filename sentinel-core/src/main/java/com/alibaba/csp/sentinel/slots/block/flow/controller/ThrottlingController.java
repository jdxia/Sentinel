/*
 * Copyright 1999-2022 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * 匀速排队控制器：按固定时间间隔安排请求，削平短时间内的流量突增。
 * 例如每 1000ms 允许 10 次，每次申请 1 个名额，则放行间隔约为 100ms。
 * 排队通过阻塞当前线程实现；预计等待超过上限时，返回 false，由上层执行限流。
 *
 * @author Eric Zhao
 * @author jialiang.linjl
 * @since 2.0
 */
public class ThrottlingController implements TrafficShapingController {

    // Refactored from legacy RateLimitController of Sentinel 1.x.

    private static final long MS_TO_NS_OFFSET = TimeUnit.MILLISECONDS.toNanos(1);

    private final int maxQueueingTimeMs;
    // count 表示此时间段内允许通过的数量；默认时间段为 1000ms。
    private final int statDurationMs;

    private final double count;
    private final boolean useNanoSeconds;

    // 已安排到的最后放行时间，可能在未来；单位随 useNanoSeconds 选择毫秒或纳秒。
    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    public ThrottlingController(int queueingTimeoutMs, double maxCountPerStat) {
        this(queueingTimeoutMs, maxCountPerStat, 1000);
    }

    public ThrottlingController(int queueingTimeoutMs, double maxCountPerStat, int statDurationMs) {
        AssertUtil.assertTrue(statDurationMs > 0, "statDurationMs should be positive");
        AssertUtil.assertTrue(maxCountPerStat >= 0, "maxCountPerStat should be >= 0");
        AssertUtil.assertTrue(queueingTimeoutMs >= 0, "queueingTimeoutMs should be >= 0");
        this.maxQueueingTimeMs = queueingTimeoutMs;
        this.count = maxCountPerStat;
        this.statDurationMs = statDurationMs;

        /**
         * 间隔难以用整数毫秒表示时，切换到纳秒计算，减少时间取整带来的误差。
         *
         * 用毫秒计算等待时间，还是用纳秒计算等待时间
         *
         * 时间段除以允许数量，不能整除时，用纳秒减少误差。
         * 平均每毫秒允许通过的数量超过 1 时，间隔小于 1ms，也用纳秒。
         */
        if (maxCountPerStat > 0) {
            this.useNanoSeconds = statDurationMs % Math.round(maxCountPerStat) != 0 || maxCountPerStat / statDurationMs > 1;
        } else {
            this.useNanoSeconds = false;
        }
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    private boolean checkPassUsingNanoSeconds(int acquireCount, double maxCountPerStat) {
        // 与毫秒分支的排队逻辑相同，这里统一使用纳秒作为时间单位。
        final long maxQueueingTimeNs = maxQueueingTimeMs * MS_TO_NS_OFFSET;
        long currentTime = System.nanoTime();
        // Calculate the interval between every two requests.
        final long costTimeNs = Math.round(1.0d * MS_TO_NS_OFFSET * statDurationMs * acquireCount / maxCountPerStat);

        // Expected pass time of this request.
        long expectedTime = costTimeNs + latestPassedTime.get();

        if (expectedTime <= currentTime) {
            // Contention may exist here, but it's okay.
            latestPassedTime.set(currentTime);
            return true;
        } else {
            final long curNanos = System.nanoTime();
            // Calculate the time to wait.
            long waitTime = costTimeNs + latestPassedTime.get() - curNanos;
            if (waitTime > maxQueueingTimeNs) {
                return false;
            }

            // 原子地预约放行时间；并发请求可能已抢先预约，因此需要再次检查等待上限。
            long oldTime = latestPassedTime.addAndGet(costTimeNs);
            waitTime = oldTime - curNanos;
            if (waitTime > maxQueueingTimeNs) {
                // 预约后发现等待过长，退回本次占用的时间间隔。
                latestPassedTime.addAndGet(-costTimeNs);
                return false;
            }
            // in race condition waitTime may <= 0
            if (waitTime > 0) {
                sleepNanos(waitTime);
            }
            return true;
        }
    }

    private boolean checkPassUsingCachedMs(int acquireCount, double maxCountPerStat) {
        long currentTime = TimeUtil.currentTimeMillis();
        // 本次申请占用的时间间隔 = 时间段 × 申请数量 / 允许数量，例如 1000 × 1 / 10 = 100ms。
        long costTime = Math.round(1.0d * statDurationMs * acquireCount / maxCountPerStat);

        // 接在最后一个已安排的请求后面，算出本次预计放行时间。
        long expectedTime = costTime + latestPassedTime.get();

        if (expectedTime <= currentTime) {
            // 已到放行时间，立即通过并从当前时间重新计时；此快速路径容忍并发竞争。
            latestPassedTime.set(currentTime);
            return true;
        } else {
            // 先估算需要等多久，超过排队上限就直接拒绝。
            long waitTime = costTime + latestPassedTime.get() - TimeUtil.currentTimeMillis();
            if (waitTime > maxQueueingTimeMs) {
                return false;
            }

            // 原子地向后推进时间，预约本次放行位置；oldTime 实际是更新后的时间。
            long oldTime = latestPassedTime.addAndGet(costTime);
            waitTime = oldTime - TimeUtil.currentTimeMillis();
            // 估算和预约之间可能有其他请求插入，必须按实际预约结果再次检查。
            if (waitTime > maxQueueingTimeMs) {
                // 等待过长，退回本次占用的时间间隔，并拒绝请求。
                latestPassedTime.addAndGet(-costTime);
                return false;
            }
            // 时间已过去或发生并发竞争时，可能无需再等；否则阻塞当前线程后放行。
            if (waitTime > 0) {
                sleepMs(waitTime);
            }
            return true;
        }
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        // 阅读入口：本实现依靠预约时间控制速率，不读取 node 的统计值，也不区分 prioritized。
        // Pass when acquire count is less or equal than 0.
        if (acquireCount <= 0) {
            return true;
        }
        // Reject when count is less or equal than 0.
        // Otherwise, the costTime will be max of long and waitTime will overflow in some cases.
        if (count <= 0) {
            return false;
        }
        if (useNanoSeconds) {
            // 用纳秒计算，适合放行间隔很小、需要更细时间单位的情况。
            return checkPassUsingNanoSeconds(acquireCount, this.count);
        } else {
            // 用毫秒计算，适合放行间隔能用整数毫秒表示的情况。
            return checkPassUsingCachedMs(acquireCount, this.count);
        }
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    private void sleepNanos(long ns) {
        LockSupport.parkNanos(ns);
    }

}
