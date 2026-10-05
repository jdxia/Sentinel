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
     * csp.sentinel.metric.file.total.count 每个资源保留的指标文件数, 默认6
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
     * 本地示例：IDE 的 Program arguments 填 --spring.profiles.active=examples，再运行 main。
     * 手动代码块 /examples/manual、优先级 /examples/priority、批量额度 /examples/batch；
     * curl 和预期结果见
     * {@link com.alibaba.csp.sentinel.demo.spring.cloud.web.SentinelExamplesController}。
     * examples 使用内存规则，不连接 Nacos；不加该参数时仍使用 application.yml 中的 Nacos 规则源。
     */
    public static void main(String[] args) {
        SpringApplication.run(SpringCloudSentinelDemoApplication.class, args);
    }
}
