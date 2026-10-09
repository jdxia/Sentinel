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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.property.SimplePropertyListener;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * <p>
 * Sentinel System Rule makes the inbound traffic and capacity meet. It takes
 * average rt, qps, thread count of incoming requests into account. And it also
 * provides a measurement of system's load, but only available on Linux.
 * </p>
 * <p>
 * rt, qps, thread count is easy to understand. If the incoming requests'
 * rt,qps, thread count exceeds its threshold, the requests will be
 * rejected.however, we use a different method to calculate the load.
 * </p>
 * <p>
 * Consider the system as a pipeline，transitions between constraints result in
 * three different regions (traffic-limited, capacity-limited and danger area)
 * with qualitatively different behavior. When there isn’t enough request in
 * flight to fill the pipe, RTprop determines behavior; otherwise, the system
 * capacity dominates. Constraint lines intersect at inflight = Capacity ×
 * RTprop. Since the pipe is full past this point, the inflight –capacity excess
 * creates a queue, which results in the linear dependence of RTT on inflight
 * traffic and an increase in system load.In danger area, system will stop
 * responding.<br/>
 * Referring to BBR algorithm to learn more.
 * </p>
 * <p>
 * Note that {@link SystemRule} only effect on inbound requests, outbound traffic
 * will not limit by {@link SystemRule}
 * </p>
 *
 * @author jialiang.linjl
 * @author leyou
 */
public final class SystemRuleManager {

    private static volatile double highestSystemLoad = Double.MAX_VALUE;
    /**
     * cpu usage, between [0, 1]
     */
    private static volatile double highestCpuUsage = Double.MAX_VALUE;
    private static volatile double qps = Double.MAX_VALUE;
    private static volatile long maxRt = Long.MAX_VALUE;
    private static volatile long maxThread = Long.MAX_VALUE;
    /**
     * mark whether the threshold are set by user.
     */
    private static volatile boolean highestSystemLoadIsSet = false;
    private static volatile boolean highestCpuUsageIsSet = false;
    private static volatile boolean qpsIsSet = false;
    private static volatile boolean maxRtIsSet = false;
    private static volatile boolean maxThreadIsSet = false;

    private static AtomicBoolean checkSystemStatus = new AtomicBoolean(false);

    private static SystemStatusListener statusListener = null;
    private final static SystemPropertyListener listener = new SystemPropertyListener();
    private static SentinelProperty<List<SystemRule>> currentProperty = new DynamicSentinelProperty<List<SystemRule>>();

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    private final static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
        new NamedThreadFactory("sentinel-system-status-record-task", true));

    static {
        checkSystemStatus.set(false);
        statusListener = new SystemStatusListener();
        // 每隔1s执行一次
        scheduler.scheduleAtFixedRate(statusListener, 0, 1, TimeUnit.SECONDS);
        currentProperty.addListener(listener);
    }

    /**
     * Listen to the {@link SentinelProperty} for {@link SystemRule}s. The property is the source
     * of {@link SystemRule}s. System rules can also be set by {@link #loadRules(List)} directly.
     *
     * @param property the property to listen.
     */
    public static void register2Property(SentinelProperty<List<SystemRule>> property) {
        synchronized (listener) {
            RecordLog.info("[SystemRuleManager] Registering new property to system rule manager");
            currentProperty.removeListener(listener);
            property.addListener(listener);
            currentProperty = property;
        }
    }

    /**
     * Load {@link SystemRule}s, former rules will be replaced.
     *
     * @param rules new rules to load.
     */
    public static void loadRules(List<SystemRule> rules) {
        currentProperty.updateValue(rules);
    }

    /**
     * Get a copy of the rules.
     *
     * @return a new copy of the rules.
     */
    public static List<SystemRule> getRules() {

        List<SystemRule> result = new ArrayList<SystemRule>();
        if (!checkSystemStatus.get()) {
            return result;
        }

        if (highestSystemLoadIsSet) {
            SystemRule loadRule = new SystemRule();
            loadRule.setHighestSystemLoad(highestSystemLoad);
            result.add(loadRule);
        }

        if (highestCpuUsageIsSet) {
            SystemRule rule = new SystemRule();
            rule.setHighestCpuUsage(highestCpuUsage);
            result.add(rule);
        }

        if (maxRtIsSet) {
            SystemRule rtRule = new SystemRule();
            rtRule.setAvgRt(maxRt);
            result.add(rtRule);
        }

        if (maxThreadIsSet) {
            SystemRule threadRule = new SystemRule();
            threadRule.setMaxThread(maxThread);
            result.add(threadRule);
        }

        if (qpsIsSet) {
            SystemRule qpsRule = new SystemRule();
            qpsRule.setQps(qps);
            result.add(qpsRule);
        }

        return result;
    }

    public static double getInboundQpsThreshold() {
        return qps;
    }

    public static long getRtThreshold() {
        return maxRt;
    }

    public static long getMaxThreadThreshold() {
        return maxThread;
    }

    static class SystemPropertyListener extends SimplePropertyListener<List<SystemRule>> {

        @Override
        public synchronized void configUpdate(List<SystemRule> rules) {
            restoreSetting();
            // systemRules = rules;
            if (rules != null && rules.size() >= 1) {
                for (SystemRule rule : rules) {
                    loadSystemConf(rule);
                }
            } else {
                checkSystemStatus.set(false);
            }

            RecordLog.info(String.format("[SystemRuleManager] Current system check status: %s, "
                    + "highestSystemLoad: %e, "
                    + "highestCpuUsage: %e, "
                    + "maxRt: %d, "
                    + "maxThread: %d, "
                    + "maxQps: %e",
                checkSystemStatus.get(),
                highestSystemLoad,
                highestCpuUsage,
                maxRt,
                maxThread,
                qps));
        }

        protected void restoreSetting() {
            checkSystemStatus.set(false);

            // should restore changes
            highestSystemLoad = Double.MAX_VALUE;
            highestCpuUsage = Double.MAX_VALUE;
            maxRt = Long.MAX_VALUE;
            maxThread = Long.MAX_VALUE;
            qps = Double.MAX_VALUE;

            highestSystemLoadIsSet = false;
            highestCpuUsageIsSet = false;
            maxRtIsSet = false;
            maxThreadIsSet = false;
            qpsIsSet = false;
        }

    }

    public static Boolean getCheckSystemStatus() {
        return checkSystemStatus.get();
    }

    public static double getSystemLoadThreshold() {
        return highestSystemLoad;
    }

    public static double getCpuUsageThreshold() {
        return highestCpuUsage;
    }

    public static void loadSystemConf(SystemRule rule) {
        boolean checkStatus = false;
        // Check if it's valid.

        if (rule.getHighestSystemLoad() >= 0) {
            highestSystemLoad = Math.min(highestSystemLoad, rule.getHighestSystemLoad());
            highestSystemLoadIsSet = true;
            checkStatus = true;
        }

        if (rule.getHighestCpuUsage() >= 0) {
            if (rule.getHighestCpuUsage() > 1) {
                RecordLog.warn(String.format("[SystemRuleManager] Ignoring invalid SystemRule: "
                    + "highestCpuUsage %.3f > 1", rule.getHighestCpuUsage()));
            } else {
                highestCpuUsage = Math.min(highestCpuUsage, rule.getHighestCpuUsage());
                highestCpuUsageIsSet = true;
                checkStatus = true;
            }
        }

        if (rule.getAvgRt() >= 0) {
            maxRt = Math.min(maxRt, rule.getAvgRt());
            maxRtIsSet = true;
            checkStatus = true;
        }
        if (rule.getMaxThread() >= 0) {
            maxThread = Math.min(maxThread, rule.getMaxThread());
            maxThreadIsSet = true;
            checkStatus = true;
        }

        if (rule.getQps() >= 0) {
            qps = Math.min(qps, rule.getQps());
            qpsIsSet = true;
            checkStatus = true;
        }

        checkSystemStatus.set(checkStatus);

    }

    /**
     * Apply {@link SystemRule} to the resource. Only inbound traffic will be checked.
     *
     * @param resourceWrapper the resource.
     * @throws BlockException when any system rule's threshold is exceeded.
     */
    public static void checkSystem(ResourceWrapper resourceWrapper, int count) throws BlockException {
        if (resourceWrapper == null) {
            return;
        }
        // Ensure the checking switch is on.
        if (!checkSystemStatus.get()) {
            return;
        }

        // for inbound traffic only
        if (resourceWrapper.getEntryType() != EntryType.IN) {
            return;
        }

        /**
         * 系统总共的QPS 有没有超过
         */
        // total qps
        double currentQps = Constants.ENTRY_NODE.passQps();
        if (currentQps + count > qps) {
            throw new SystemBlockException(resourceWrapper.getName(), "qps");
        }

        /**
         * 实际并不能体现线程
         */
        // total thread
        int currentThread = Constants.ENTRY_NODE.curThreadNum();
        if (currentThread > maxThread) {
            throw new SystemBlockException(resourceWrapper.getName(), "thread");
        }

        /**
         * 平均响应时间限流
         */
        double rt = Constants.ENTRY_NODE.avgRt();
        if (rt > maxRt) {
            throw new SystemBlockException(resourceWrapper.getName(), "rt");
        }

        /**
         * load1 超过阈值后，再进行容量判断
         * Linux/Unix 环境中按需启用
         *
         * Load 参考值是 CPU 核数 × 2.5, 它适合作为实验起点，不能代替容量验证
         *
         * load 是一个“果”，如果根据 load 的情况来调节流量的通过率，那么就始终有延迟性。
         * 也就意味着通过率的任何调整，都会过一段时间才能看到效果。当前通过率是使 load 恶化的一个动作，那么也至少要过 1 秒之后才能观测到；
         * 同理，如果当前通过率调整是让 load 好转的一个动作，也需要 1 秒之后才能继续调整，这样就浪费了系统的处理能力。所以我们看到的曲线，总是会有抖动
         */
        // load. BBR algorithm.
        if (highestSystemLoadIsSet && getCurrentSystemAvgLoad() > highestSystemLoad) {
            /**
             * Load 超过阈值，并不一定拦截。
             * 当前实现还要求入口并发数同时大于 1、大于 maxSuccessQps × minRt / 1000，才会拦截
             *
             * 这个公式是在估算系统顺畅处理请求时，需要维持多少个“正在处理、尚未结束”的请求。
             *
             * 假设一个系统：
             * - 每秒能完成 1000 个请求
             * - 每个请求耗时 20 ms，也就是 0.02 秒
             *
             * 1000 个/秒 × 0.02 秒 = 20 个  超过就拦截
             *
             * 每秒处理多少个 × 每个占用多久 = 同时有多少个还没处理完。
             * 源码里除以 1000，只是把 RT 的单位从毫秒换成秒
             *
             * maxSuccessQps  在近期有效统计桶中，取最大的完成计数，再按桶时长换算为每秒处理量
             * minRt    近期有效统计桶中记录到的最小 RT，单位是毫秒，返回值至少为 1 ms
             * currentThread    当前入口中正在处理、尚未退出的请求数量
             */
            if (!checkBbr(currentThread)) {
                throw new SystemBlockException(resourceWrapper.getName(), "load");
            }
        }

        /**
         * 判断cpu使用率
         * 后台有定时任务, 会不断收集当前请求的使用情况, 每隔1s执行一次
         */
        // cpu usage
        if (highestCpuUsageIsSet && getCurrentCpuUsage() > highestCpuUsage) {
            throw new SystemBlockException(resourceWrapper.getName(), "cpu");
        }
    }

    private static boolean checkBbr(int currentThread) {
        if (currentThread > 1 &&
            currentThread > Constants.ENTRY_NODE.maxSuccessQps() * Constants.ENTRY_NODE.minRt() / 1000) {
            return false;
        }
        return true;
    }

    public static double getCurrentSystemAvgLoad() {
        return statusListener.getSystemAverageLoad();
    }

    public static double getCurrentCpuUsage() {
        return statusListener.getCpuUsage();
    }
}
