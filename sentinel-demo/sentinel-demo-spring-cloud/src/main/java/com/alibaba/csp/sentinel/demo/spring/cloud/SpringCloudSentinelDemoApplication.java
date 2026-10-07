package com.alibaba.csp.sentinel.demo.spring.cloud;

import com.alibaba.cloud.sentinel.SentinelWebAutoConfiguration;
import com.alibaba.cloud.sentinel.SentinelWebMvcConfigurer;
import com.alibaba.cloud.sentinel.custom.context.SentinelApplicationContextInitializer;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.AbstractSentinelInterceptor;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.SentinelWebInterceptor;
import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class SpringCloudSentinelDemoApplication {

    /**
     * 一些配置的key, 在spring cloud alibaba里面写在  spring.cloud.sentinel.*
     * {@link SentinelApplicationContextInitializer} 是在这里设置的
     *
     * csp.sentinel.config.file 指定 Sentinel 原生配置文件路径, 默认读取 classpath 下的 sentinel.properties
     * csp.sentinel.app.type  应用类型, 默认 0，普通应用；网关等特定场景可能用不同类型
     * csp.sentinel.heartbeat.client.ip  向 Dashboard 注册的本机 IP
     * csp.sentinel.heartbeat.interval.ms 心跳周期，单位毫秒
     * csp.sentinel.heartbeat.api.path Dashboard 注册接口路径, 使用官方 Dashboard 时不改，默认 /registry/machine
     *
     * csp.sentinel.log.dir Sentinel 日志和本地指标文件目录, 默认 ${user.home}/logs/csp/
     * csp.sentinel.log.level Sentinel 内部日志等级, 默认 INFO
     * csp.sentinel.metric.file.single.size 单个指标文件最大字节数 默认 52428800，即 50 MiB
     * csp.sentinel.metric.file.total.count 每个资源保留的指标文件数, 默认6, 直接决定 dashboard 能回看多久
     * csp.sentinel.metric.flush.interval 指标落盘任务周期，单位秒, 1；小于等于 0 时不启动该定时任务
     */

    /**
     * 自动装配是在 spring cloud alibaba {@link SentinelWebAutoConfiguration#sentinelWebMvcConfig()} 这里集成的, 这个是 spring cloud alibaba才会, boot不会
     * 拦截器的顺序是 {@link SentinelWebMvcConfigurer}  默认顺序是 Ordered.HIGHEST_PRECEDENCE（Integer.MIN_VALUE）最优先的位置
     *
     * 拦截器是 {@link SentinelWebInterceptor}
     * 1. 核心看继承的 {@link AbstractSentinelInterceptor#preHandle(HttpServletRequest, HttpServletResponse, Object)}
     * 2. 还有后置的 {@link AbstractSentinelInterceptor#afterCompletion(HttpServletRequest, HttpServletResponse, Object, Exception)}
     * 3. 异步servlet的后置 {@link AbstractSentinelInterceptor#afterConcurrentHandlingStarted(HttpServletRequest, HttpServletResponse, Object)}
     *
     * 切面是 {@link SentinelResourceAspect}
     */

    /**
     * ~/logs/csp/   文件, 写入是异步的
     * |
     * | 这2个 跨天历史文件无限累积, 要么定时任务直接删 rm是安全的, 要么 Logger SPI 接管
     * ├── sentinel-record.log.2026-10-07            ← 通用运行日志（JUL）, 初始化过程（[InitExecutor] Found init func）、规则加载/变更（[FlowRuleManager] Flow rules loaded）、metric 文件滚动（[MetricWriter] New metric file created）。排查“Sentinel 为什么没生效”的第一站
     * ├── command-center.log.2026-10-07             ← 8719 服务日志（JUL，transport）, 每次 HTTP 命令处理、异常
     * |
     * ├── sentinel-block.log                        ← 被拦截请求明细（EagleEye）
     * ├── myApp-metrics.log.2026-10-07              ← 监控数据（MetricWriter，dashboard 的数据源）
     * ├── myApp-metrics.log.2026-10-07.idx          ← 它的索引
     * ├── sentinel-cluster.log                      ← 集群限流统计（可选，EagleEye）
     * ├── sentinel-cluster-client.log / sentinel-server.log
     * └── *.lck                                     ← JUL FileHandler 的锁文件
     *
     * ┌────────────────────────────────────┬──────────────────────┬─────────────────────────┬─────────────────────────────────────┬────────────────────────────┐
     * │                文件                   │        清理者           │        触发时机            │                策略                     │        默认占用上限        │
     * ├────────────────────────────────────┼──────────────────────┼─────────────────────────┼─────────────────────────────────────┼────────────────────────────┤
     * │ {app}-metrics.log.N + .idx             │ ✅ MetricWriter 自动  │ 每次滚动（跨天/超50MB）     │ 总数≤6，删最老（含索引）                 │ ~300MB（可配）             │
     * ├────────────────────────────────────┼──────────────────────┼─────────────────────────┼─────────────────────────────────────┼────────────────────────────┤
     * │ sentinel-record.log.*                  │ ⚠️ 半自动             │ 每天零点                   │ 当天 200MB×4 循环覆盖；跨天文件不删       │ 每天最多 800MB，无限累积   │
     * ├────────────────────────────────────┼──────────────────────┼─────────────────────────┼─────────────────────────────────────┼────────────────────────────┤
     * │ command-center.log.*                   │ ⚠️ 半自动             │ 同上                       │ 同上                                    │ 同上                       │
     * ├────────────────────────────────────┼──────────────────────┼─────────────────────────┼─────────────────────────────────────┼────────────────────────────┤
     * │ sentinel-block.log                     │ ✅ EagleEye 自动      │ 写满 300MB                 │ 保留 3 备份                             │ ~1.2GB（仅大量被拦时才涨） │
     * ├────────────────────────────────────┼──────────────────────┼─────────────────────────┼─────────────────────────────────────┼────────────────────────────┤
     * │ cluster*.log / sentinel-server.log     │ ✅ EagleEye 自动      │ 写满 300MB                 │ 保留 3 备份                             │ 仅集群限流模式             │
     * ├────────────────────────────────────┼──────────────────────┼─────────────────────────┼─────────────────────────────────────┼────────────────────────────┤
     * │ *.lck                                  │ JVM 退出时删           │ —                          │ kill -9 可能残留，可手动删              │ 极小                       │
     * └────────────────────────────────────┴──────────────────────┴─────────────────────────┴─────────────────────────────────────┴────────────────────────────┘
     */

    /**
     * http://127.0.0.1:18080/examples/qps 触发下
     */
    public static void main(String[] args) {
        SpringApplication.run(SpringCloudSentinelDemoApplication.class, args);
    }

    /**
     * 注意一个坑的点
     *
     * 先理解 Tracer.trace 到底做了什么
     *
     * 看 Tracer.java:67-76 和 110-116：
     *
     * public static void traceContext(Throwable e, Context context) {
     *     ...
     *     traceEntryInternal(e, context.getCurEntry());   // 拿「当前 context 的当前 entry」
     * }
     *
     * private static void traceEntryInternal(Throwable e, Entry entry) {
     *     if (entry == null) {
     *         return;
     *     }
     *     entry.setError(e);      // 仅仅是打个标记！
     * }
     *
     * Tracer.trace(ex) 本身不产生任何统计，只是 curEntry.setError(ex) 打标记。真正把异常数累加进 Node 的时机在 exit 时（StatisticSlot.java:154-157）：
     *
     * Throwable error = context.getCurEntry().getError();   // exit 时刻才读取标记
     * recordCompleteFor(node, count, rt, error);            // error != null → increaseExceptionQps
     *
     * 所以正确的时序必须是：业务抛异常 → catch 里 Tracer.trace（打标记）→ exit（读标记、统计异常数）。
     *
     * try-with-resources 打乱了这个顺序
     *
     * Java 语言规范（JLS 14.20.3）规定，try-with-resources 编译后等价于：
     *
     * // try (Entry entry = SphU.entry(res, args)) {
     * //     bizCode();
     * // } catch (BizException ex) {
     * //     Tracer.trace(ex);
     * // }
     *
     * Entry entry = SphU.entry(res, args);
     * Throwable primary = null;
     * try {
     *     bizCode();                          // ① 抛出 BizException
     * } catch (Throwable t) {
     *     primary = t;
     *     throw t;
     * } finally {
     *     if (entry != null) {
     *         try { entry.close(); }          // ② 先执行 close（= exit）
     *         catch (Throwable sup) { primary.addSuppressed(sup); }
     *     }
     * }
     * // ③ 然后才轮到用户写的 catch → Tracer.trace(ex)
     *
     * 即执行顺序是：业务异常 → close() → catch 块。close 抢在 catch 之前执行，导致两步连锁失效：
     *
     * 第一失效点（② 中）：close() → CtEntry.exitForContext（CtEntry.java:93-143）：
     * - chain.exit(...) → StatisticSlot.exit 读 getError() —— 此时你还没执行 catch，标记还是 null，异常数、以及退出回调全部执行完毕
     * - context.setCurEntry(parent)（129 行）—— curEntry 被改回父 entry
     * - 若是根 entry 且用的是自动创建的默认 context：ContextUtil.exit()（136 行）→ contextHolder.set(null)（ContextUtil.java:210-215）—— ThreadLocal 里的 context 直接被清空
     *
     * 第二失效点（③ 中）：你的 catch 里 Tracer.trace(ex)：
     * - 默认 context 场景：ContextUtil.getContext() 返回 null → traceContext 里 context == null 直接 return —— 异常彻底丢失
     * - 嵌套调用场景（A 方法 entry 里调 B 方法 entry）：curEntry 已经是父 entry → parent.setError(ex) —— 异常被记到父资源头上，子资源异常数丢失、父资源异常数虚高（如果依赖异常比例熔断 DEGRADE_GRADE_EXCEPTION_RATIO，会错误地熔断父资源）
     * - 退一万步说，即使还能拿到这个 entry：setError 也已经晚了，StatisticSlot.exit 早已执行完，没有人会再去读这个标记
     *
     * 结论：try-with-resources 的 catch 块里调 Tracer.trace(ex)，异常统计 100% 失效。这是语义层面的死结，无法靠保存参数解决，1.8.9 上依然存在。
     */

}
