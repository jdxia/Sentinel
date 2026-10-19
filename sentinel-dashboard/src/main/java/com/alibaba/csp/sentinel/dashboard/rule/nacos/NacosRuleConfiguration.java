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
package com.alibaba.csp.sentinel.dashboard.rule.nacos;

import java.util.Properties;
import java.util.concurrent.Executor;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.NacosConfigService;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.Assert;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** 仅在 nacos profile 创建连接；普通模式保持原来的客户端推送链路。 */
@Configuration
@Profile("nacos")
public class NacosRuleConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosRuleConfiguration.class);

    @Bean(destroyMethod = "shutDown")
    public ConfigService dashboardNacosConfigService(
            @Value("${sentinel.nacos.server-addr}") String serverAddr,
            @Value("${sentinel.nacos.namespace:}") String namespace,
            @Value("${sentinel.nacos.username:}") String username,
            @Value("${sentinel.nacos.password:}") String password) throws NacosException {
        Assert.hasText(serverAddr, "sentinel.nacos.server-addr must not be blank");
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddr);
        properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        properties.setProperty(PropertyKeyConst.USERNAME, username);
        properties.setProperty(PropertyKeyConst.PASSWORD, password);
        LOGGER.info("[NacosRule] mode=nacos, serverAddr={}, namespace={}", serverAddr, namespace);

        /**
         * 创建 {@link NacosConfigService}
         */
        return NacosFactory.createConfigService(properties);
    }

    @Bean
    public NacosRuleStore nacosRuleStore(ConfigService dashboardNacosConfigService,
            @Value("${sentinel.nacos.namespace:}") String namespace,
            @Value("${sentinel.nacos.group-id:SENTINEL_GROUP}") String group,
            @Value("${spring.profiles.active}") String ruleProfile,
            @Value("${sentinel.nacos.timeout-ms:3000}") long timeoutMs) {
        return new NacosRuleStore(dashboardNacosConfigService, namespace, group, ruleProfile, timeoutMs);
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient nacosCatalogHttpClient() {
        // 管理接口带鉴权信息，禁止重定向转发；节点切换由目录扫描显式控制。
        return HttpClients.custom().disableRedirectHandling().disableAutomaticRetries().build();
    }

    @Bean
    public NacosConfigCatalog nacosConfigCatalog(CloseableHttpClient nacosCatalogHttpClient,
            @Value("${sentinel.nacos.server-addr}") String serverAddr,
            @Value("${sentinel.nacos.namespace:}") String namespace,
            @Value("${sentinel.nacos.group-id:SENTINEL_GROUP}") String group,
            @Value("${spring.profiles.active}") String ruleProfile,
            @Value("${sentinel.nacos.username:}") String username,
            @Value("${sentinel.nacos.password:}") String password,
            @Value("${sentinel.nacos.timeout-ms:3000}") int timeoutMs) {
        HttpComponentsClientHttpRequestFactory requests = new HttpComponentsClientHttpRequestFactory(nacosCatalogHttpClient);
        requests.setConnectTimeout(timeoutMs);
        requests.setConnectionRequestTimeout(timeoutMs);
        requests.setReadTimeout(timeoutMs);
        return new NacosConfigCatalog(new RestTemplate(requests), serverAddr, namespace, group, ruleProfile,
            username, password);
    }

    @Bean
    @ConditionalOnProperty(prefix = "sentinel.nacos", name = "preload-enabled", havingValue = "true", matchIfMissing = true)
    public NacosRulePreloader nacosRulePreloader(NacosConfigCatalog catalog, NacosRuleStore store,
            @Value("${spring.profiles.active}") String ruleProfile,
            @Qualifier("applicationTaskExecutor") Executor executor) {
        // 复用 Spring Boot 管理的执行器，避免自行创建缺少关闭流程的后台线程。
        return new NacosRulePreloader(catalog, store, ruleProfile, executor);
    }
}
