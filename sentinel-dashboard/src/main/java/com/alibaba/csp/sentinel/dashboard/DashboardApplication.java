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
package com.alibaba.csp.sentinel.dashboard;

import com.alibaba.csp.sentinel.dashboard.rule.nacos.NacosRuleConfiguration;
import com.alibaba.csp.sentinel.init.InitExecutor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sentinel dashboard application.
 *
 * @author Carpenter Lee
 */
@SpringBootApplication
public class DashboardApplication {

    /**
     * 端口设置 8678 { sentinel-dashboard/src/main/resources/application.properties }
     * 访问 http://127.0.0.1:8678/#/dashboard 账密都是 sentinel
     *
     * 账密在 sentinel-dashboard/src/main/resources/application.properties 这个里面修改
     * 或者 -Dsentinel.dashboard.auth.username=sentinel -Dsentinel.dashboard.auth.password=sentinel
     */


    /**
     * 如果要让dashboard支持nacos, 那么
     * 1. pom里面导入依赖  <artifactId>nacos-client</artifactId>
     * 2. 配置文件和环境 --spring.profiles.active=nacos 指向带nacos的, 一个 Dashboard 进程使用同一套 namespace 和 group 管理规则，不同应用通过不同 Data ID 区分
     *
     *
     * 要注意这个问题
     * 例如，Nacos 已有：
     * 规则 A、规则 B
     * Dashboard 刚重启，内存中没有加载它们。用户从簇点链路直接新增规则 C，如果把内存列表直接发布，就可能写成：
     * 规则 C
     * A、B 就丢了。
     *
     */
    public static void main(String[] args) {
        triggerSentinelInit();
        SpringApplication.run(DashboardApplication.class, args);
    }

    private static void triggerSentinelInit() {
        new Thread(() -> InitExecutor.doInit()).start();
    }
}
