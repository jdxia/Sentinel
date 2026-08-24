package com.alibaba.csp.sentinel.demo.spring.webmvc.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为标注了 {@code @SentinelResource} 的方法启用 Sentinel Spring AOP 支持。
 *
 * <p>注解本身只描述资源。方法调用经过 Spring 代理时，本切面负责在调用前后
 * 创建和退出对应的 Sentinel Entry。</p>
 */
@Configuration
public class SentinelAspectConfiguration {

    /**
     * 显式注册切面，避免当前手工集成的 Demo 依赖其他框架提供的 Sentinel 自动配置。
     *
     * @return Sentinel 注解切面
     */
    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }
}
