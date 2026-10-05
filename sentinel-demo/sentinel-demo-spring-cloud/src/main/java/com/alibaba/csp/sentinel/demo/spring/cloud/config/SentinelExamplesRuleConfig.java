package com.alibaba.csp.sentinel.demo.spring.cloud.config;

import java.util.Arrays;
import java.util.Collections;

import com.alibaba.csp.sentinel.demo.spring.cloud.web.SentinelExamplesController;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 本地学习规则：只在 examples 模式加载，配套配置关闭 Nacos 规则源。
 * loadRules 替换对应类型的整组内存规则，因此集中初始化，不在请求中反复加载。
 */
@Configuration
@Profile("examples")
public class SentinelExamplesRuleConfig {

    @PostConstruct
    public void initRules() {
        FlowRule concurrencyRule = new FlowRule(SentinelExamplesController.CONCURRENCY_RESOURCE);
        concurrencyRule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        concurrencyRule.setCount(1);
        FlowRuleManager.loadRules(Arrays.asList(
            qpsRule(SentinelExamplesController.QPS_RESOURCE, 2),
            concurrencyRule,
            qpsRule(SentinelExamplesController.MANUAL_RESOURCE, 2),
            qpsRule(SentinelExamplesController.PRIORITY_RESOURCE, 2),
            qpsRule(SentinelExamplesController.BATCH_RESOURCE, 5)));

        // 保持现有熔断、热点示例的注释与本地运行规则一致。
        DegradeRule circuitRule = new DegradeRule(SentinelExamplesController.CIRCUIT_RESOURCE);
        circuitRule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT);
        circuitRule.setCount(1);
        circuitRule.setMinRequestAmount(2);
        circuitRule.setStatIntervalMs(10_000);
        circuitRule.setTimeWindow(5);
        DegradeRuleManager.loadRules(Collections.singletonList(circuitRule));

        ParamFlowRule hotRule = new ParamFlowRule(SentinelExamplesController.HOT_RESOURCE);
        hotRule.setParamIdx(0);
        hotRule.setCount(2);
        hotRule.setDurationInSec(1);
        ParamFlowRuleManager.loadRules(Collections.singletonList(hotRule));
    }

    private static FlowRule qpsRule(String resource, int count) {
        FlowRule rule = new FlowRule(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(count);
        // 优先级预约只在本地 QPS + 快速失败控制器中生效。
        rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        return rule;
    }
}
