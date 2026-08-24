/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.demo.spring.webmvc;

import java.util.Collections;

import com.alibaba.csp.sentinel.demo.spring.webmvc.controller.MySphUController;
import com.alibaba.csp.sentinel.init.InitExecutor;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * <p>Add the JVM parameter to connect to the dashboard:</p>
 * {@code -Dcsp.sentinel.dashboard.server=127.0.0.1:8080 -Dproject.name=sentinel-demo-spring-webmvc}
 *
 * @author kaizi2009
 */
@SpringBootApplication
public class WebMvcDemoApplication {

    public static void main(String[] args) {
        /**
         * 设置启动参数, 指定 Sentinel 客户端连接的 Dashboard 服务地址
         * -Dcsp.sentinel.dashboard.server=127.0.0.1:8080 -Dproject.name=sentinel-demo-spring-webmvc
         *
         * 这个服务的是 http://127.0.0.1:10001/hello
         */
        System.setProperty("csp.sentinel.dashboard.server", "127.0.0.1:8678");
        System.setProperty("project.name", "sentinel-demo-spring-webmvc");

        // 手动触发 Sentinel 初始化（相当于 sentinel.eager=true 的效果）
        InitExecutor.doInit();

        /**
         * 在 Web 服务开始接收请求前加载规则，避免启动期间出现规则尚未生效的时间窗口。
         * 但是注意 和 Dashboard 那种不兼容会覆盖
         *
         * 看源码的时候可以看下, 其他不要打开
         */
//        initFlowRules();

        SpringApplication.run(WebMvcDemoApplication.class);


    }

    /**
     * 加载 {@link MySphUController} 手工资源使用的本地流控规则。
     *
     * <p>本示例直接把规则加载到当前 JVM 内存，适合本地学习和测试。应用重启时会重新加载这里的规则；
     * 如果后续接入 Dashboard、Nacos 等动态规则来源，需要明确唯一的规则权威来源，避免不同来源相互覆盖。</p>
     *
     * <p>{@link FlowRuleManager#loadRules(java.util.List)} 是全量替换，不是增量追加。如果以后增加其他资源规则，
     * 必须把所有规则放进同一个列表后一次性加载，否则后一次调用会覆盖前一次已经加载的规则。</p>
     */
    private static void initFlowRules() {
        FlowRule rule = new FlowRule();

        // 规则通过资源名与 SphU.entry(...) 建立关联，两边必须使用完全相同的固定资源名。
        rule.setResource(MySphUController.RESOURCE_NAME);

        // 按每秒请求数进行统计和限流；这里限制当前 JVM 实例每秒最多通过一个请求。
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(1);

        /**
         * 假设同一秒内有请求：
         *   来源 app-a：1 个请求
         *   来源 app-b：1 个请求
         *   来源 app-c：1 个请求
         *
         * default 表示不区分调用来源，所有来源共同使用上面的 QPS 配额。
         */
        rule.setLimitApp("default");

        // 超过阈值后立即抛出 BlockException，不排队等待，便于测试 Controller 中的 catch 分支。
        rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);

        // loadRules 会替换当前全部流控规则；当前 Demo 只有这一条，所以使用单元素不可变列表。
        FlowRuleManager.loadRules(Collections.singletonList(rule));

        /**
         下面这种写法有问题：

         FlowRuleManager.loadRules(Collections.singletonList(ruleA));
         FlowRuleManager.loadRules(Collections.singletonList(ruleB));

         最终通常只剩下 ruleB。如果有多条规则，要一次性加载完整列表：

         List<FlowRule> rules = new ArrayList<>();
         rules.add(ruleA);
         rules.add(ruleB);
         rules.add(ruleC);

         FlowRuleManager.loadRules(rules);
         */

    }
}
