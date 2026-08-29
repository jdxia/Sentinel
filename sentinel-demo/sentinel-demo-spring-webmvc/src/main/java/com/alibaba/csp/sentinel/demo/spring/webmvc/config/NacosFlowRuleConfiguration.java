package com.alibaba.csp.sentinel.demo.spring.webmvc.config;

import java.util.List;
import java.util.Properties;

import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.api.PropertyKeyConst;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 从 Nacos 订阅流控规则，并把动态属性注册给 Sentinel。
 *
 * <p>Nacos 配置内容必须是完整的 {@link FlowRule} JSON 数组；配置更新后，
 * {@link NacosDataSource} 会推送并全量替换当前进程中的流控规则。</p>
 */
@Configuration
public class NacosFlowRuleConfiguration {

    /**
     * 在 Spring 容器启动完成前建立监听，避免 Web 服务启动后仍使用代码内的临时规则。
     * Spring 关闭时会调用 {@link NacosDataSource#close()} 释放监听线程和 Nacos 客户端。
     *
     * @param serverAddr Nacos 服务地址
     * @param namespace Nacos 命名空间 ID
     * @param groupId Nacos 配置分组
     * @param dataId Nacos 配置 DataId
     * @return 持续监听 Nacos 的流控规则数据源
     */
    @Bean(destroyMethod = "close")
    public NacosDataSource<List<FlowRule>> nacosFlowRuleDataSource(
            @Value("${sentinel.datasource.nacos.server-addr}") String serverAddr,
            @Value("${sentinel.datasource.nacos.namespace}") String namespace,
            @Value("${sentinel.datasource.nacos.group-id}") String groupId,
            @Value("${sentinel.datasource.nacos.data-id}") String dataId) {
        NacosDataSource<List<FlowRule>> dataSource = new NacosDataSource<>(
                createNacosProperties(serverAddr, namespace), groupId, dataId,
                source -> JSON.parseArray(source, FlowRule.class));
        FlowRuleManager.register2Property(dataSource.getProperty());
        return dataSource;
    }

    private static Properties createNacosProperties(String serverAddr, String namespace) {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
//        properties.setProperty(PropertyKeyConst.USERNAME, "xx");
//        properties.setProperty(PropertyKeyConst.PASSWORD, "xx");
        return properties;
    }
}
