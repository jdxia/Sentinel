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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/** 启动后异步预加载；失败只影响缓存预热，不能阻止 Dashboard 提供监控和按需读写。 */
public class NacosRulePreloader implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosRulePreloader.class);
    private static final List<NacosRuleType<?>> RULE_TYPES = Arrays.asList(NacosRuleType.FLOW,
        NacosRuleType.DEGRADE, NacosRuleType.PARAM_FLOW, NacosRuleType.SYSTEM, NacosRuleType.AUTHORITY,
        NacosRuleType.GW_FLOW, NacosRuleType.GW_API_GROUP);
    private final NacosConfigCatalog catalog;
    private final NacosRuleStore store;
    private final String ruleProfile;
    private final Executor executor;
    private final AtomicBoolean started = new AtomicBoolean();

    public NacosRulePreloader(NacosConfigCatalog catalog, NacosRuleStore store, String ruleProfile, Executor executor) {
        this.catalog = catalog;
        this.store = store;
        this.ruleProfile = ruleProfile;
        this.executor = executor;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (started.compareAndSet(false, true)) {
            try {
                executor.execute(this::preload);
            } catch (RuntimeException e) {
                LOGGER.error("[NacosRule] preload scheduling failed; on-demand access remains available", e);
            }
        }
    }

    void preload() {
        long startedAt = System.nanoTime();
        int success = 0;
        int failed = 0;
        int matched = 0;
        Set<String> apps = new HashSet<>();
        LOGGER.info("[NacosRule] preload begin, ruleProfile={}", ruleProfile);
        try {
            for (String dataId : catalog.listDataIds()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Nacos 规则预加载已被中断");
                }
                for (NacosRuleType<?> type : RULE_TYPES) {
                    String suffix = "-" + ruleProfile + "-" + type.getSuffix();
                    if (!dataId.endsWith(suffix) || dataId.length() <= suffix.length()) {
                        continue;
                    }
                    String app = dataId.substring(0, dataId.length() - suffix.length());
                    matched++;
                    apps.add(app);
                    try {
                        // 与页面查询共用锁和解析器；不采用目录响应中的旧正文，也不伪造在线机器。
                        int count = store.query(type, app, null, null).size();
                        success++;
                        LOGGER.info("[NacosRule] preload item success, app={}, dataId={}, ruleCount={}",
                            app, dataId, count);
                    } catch (NacosException | RuntimeException e) {
                        failed++;
                        LOGGER.error("[NacosRule] preload item failed, app={}, dataId={}; continuing other configs",
                            app, dataId, e);
                    }
                    break;
                }
            }
            LOGGER.info("[NacosRule] preload complete, apps={}, matched={}, success={}, failed={}, elapsedMs={}",
                apps.size(), matched, success, failed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        } catch (RuntimeException e) {
            LOGGER.error("[NacosRule] preload aborted, matched={}, success={}, failed={}, elapsedMs={}; "
                    + "on-demand access remains available", matched, success, failed,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), e);
        }
    }
}
