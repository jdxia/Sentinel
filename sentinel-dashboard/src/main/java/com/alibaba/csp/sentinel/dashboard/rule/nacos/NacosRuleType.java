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

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.gateway.ApiDefinitionEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.gateway.GatewayFlowRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.AuthorityRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.DegradeRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.FlowRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.ParamFlowRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.RuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.SystemRuleEntity;
import com.alibaba.csp.sentinel.slots.block.Rule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleUtil;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleUtil;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;

import static com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants.URL_MATCH_STRATEGY_REGEX;

/** 客户端规则、API 分组与页面实体的映射；Nacos 不存 Dashboard 的包装字段。 */
public final class NacosRuleType<T extends RuleEntity> {

    public static final NacosRuleType<FlowRuleEntity> FLOW = new NacosRuleType<>(
        "flow-rules", FlowRuleEntity.class, FlowRule.class, FlowRuleEntity::fromFlowRule,
        FlowRuleUtil::isValidRule);
    public static final NacosRuleType<DegradeRuleEntity> DEGRADE = new NacosRuleType<>(
        "degrade-rules", DegradeRuleEntity.class, DegradeRule.class, DegradeRuleEntity::fromDegradeRule,
        DegradeRuleManager::isValidRule);
    public static final NacosRuleType<ParamFlowRuleEntity> PARAM_FLOW = new NacosRuleType<>(
        "param-rules", ParamFlowRuleEntity.class, ParamFlowRule.class, ParamFlowRuleEntity::fromParamFlowRule,
        ParamFlowRuleUtil::isValidRule);
    public static final NacosRuleType<SystemRuleEntity> SYSTEM = new NacosRuleType<>(
        "system-rules", SystemRuleEntity.class, SystemRule.class, SystemRuleEntity::fromSystemRule,
        rule -> (rule.getHighestSystemLoad() >= 0 || rule.getHighestCpuUsage() >= 0
            || rule.getAvgRt() >= 0 || rule.getMaxThread() >= 0 || rule.getQps() >= 0)
            && rule.getHighestCpuUsage() <= 1);
    public static final NacosRuleType<AuthorityRuleEntity> AUTHORITY = new NacosRuleType<>(
        "authority-rules", AuthorityRuleEntity.class, AuthorityRule.class, AuthorityRuleEntity::fromAuthorityRule,
        rule -> AuthorityRuleManager.isValidRule(rule) && rule.getStrategy() <= 1);
    public static final NacosRuleType<GatewayFlowRuleEntity> GW_FLOW = new NacosRuleType<>(
        "gw-flow-rules", GatewayFlowRuleEntity.class,
        (app, ip, port, json) -> GatewayFlowRuleEntity.fromGatewayFlowRule(app, ip, port,
            json.toJavaObject(GatewayFlowRule.class)),
        json -> GatewayRuleManager.isValidRule(json.toJavaObject(GatewayFlowRule.class)),
        GatewayFlowRuleEntity::toGatewayFlowRule);
    public static final NacosRuleType<ApiDefinitionEntity> GW_API_GROUP = new NacosRuleType<>(
        "gw-api-group-rules", ApiDefinitionEntity.class,
        (app, ip, port, json) -> ApiDefinitionEntity.fromApiDefinition(app, ip, port, parseApi(json)),
        NacosRuleType::isValidApi, ApiDefinitionEntity::toApiDefinition);

    private final String suffix;
    private final Class<T> entityClass;
    private final EntityFactory<JSONObject, T> factory;
    private final Predicate<JSONObject> validator;
    private final Function<T, ?> converter;

    private <R extends Rule> NacosRuleType(String suffix, Class<T> entityClass, Class<R> ruleClass,
                                          EntityFactory<R, T> factory, Predicate<R> validator) {
        this(suffix, entityClass,
            (app, ip, port, json) -> factory.create(app, ip, port, json.toJavaObject(ruleClass)),
            json -> validator.test(json.toJavaObject(ruleClass)), RuleEntity::toRule);
    }

    private NacosRuleType(String suffix, Class<T> entityClass, EntityFactory<JSONObject, T> factory,
                          Predicate<JSONObject> validator, Function<T, ?> converter) {
        this.suffix = suffix;
        this.entityClass = entityClass;
        this.factory = factory;
        this.validator = validator;
        this.converter = converter;
    }

    String getSuffix() {
        return suffix;
    }

    T cast(RuleEntity entity) {
        return entityClass.cast(entity);
    }

    T toEntity(String app, String ip, Integer port, JSONObject rule) {
        // 深拷贝防止页面修改热点参数列表等嵌套对象时，提前污染用于冲突校验的快照。
        return factory.create(app, ip, port, JSONObject.parseObject(rule.toJSONString()));
    }

    JSONObject toRuleJson(T entity) {
        // 统一为 Nacos 读取时的 JSON 数值类型，避免 Double/BigDecimal 差异造成虚假的版本冲突。
        // 删除参数限流条件时必须发布 null，否则合并旧配置会重新保留 paramItem。
        Object converted = converter.apply(entity);
        JSONObject rule = JSONObject.parseObject(this == GW_FLOW
            ? JSONObject.toJSONString(converted, SerializerFeature.WriteMapNullValue)
            : JSONObject.toJSONString(converted));
        // 这三个旧页面未暴露 regex，toRule() 的默认 false 不能覆盖 Nacos 中已有的 true。
        if (this == FLOW || this == DEGRADE || this == SYSTEM) {
            rule.remove("regex");
        }
        return rule;
    }

    void validate(JSONObject rule) {
        if (rule.containsKey("rule") || rule.containsKey("app") || rule.containsKey("ip")
            || rule.containsKey("port") || !validator.test(rule)) {
            throw new IllegalArgumentException("无效的 " + suffix + " 配置：需要原生 Sentinel Rule 对象");
        }
        // 集群规则还需要 Token Server 的一致性管理，不能只向普通客户端的数据源发布。
        if (Boolean.TRUE.equals(rule.getBoolean("clusterMode"))) {
            throw new IllegalArgumentException("Nacos 模式暂不支持 clusterMode=true 的集群规则");
        }
    }

    private static ApiDefinition parseApi(JSONObject json) {
        ApiDefinition api = new ApiDefinition(json.getString("apiName"));
        JSONArray items = json.getJSONArray("predicateItems");
        Set<ApiPredicateItem> predicates = new HashSet<>();
        if (items != null) {
            for (Object item : items) {
                if (!(item instanceof JSONObject)) {
                    throw new IllegalArgumentException("API 分组匹配项必须是对象");
                }
                // ApiPredicateItem 是接口，按网关命令处理器的约定解析为路径匹配项。
                predicates.add(((JSONObject) item).toJavaObject(ApiPathPredicateItem.class));
            }
        }
        return api.setPredicateItems(predicates);
    }

    private static boolean isValidApi(JSONObject json) {
        ApiDefinition api = parseApi(json);
        if (!GatewayApiDefinitionManager.isValidApi(api) || api.getPredicateItems().isEmpty()) {
            return false;
        }
        for (ApiPredicateItem item : api.getPredicateItems()) {
            ApiPathPredicateItem path = (ApiPathPredicateItem) item;
            if (StringUtil.isBlank(path.getPattern()) || path.getMatchStrategy() < 0
                    || path.getMatchStrategy() > URL_MATCH_STRATEGY_REGEX) {
                return false;
            }
            if (path.getMatchStrategy() == URL_MATCH_STRATEGY_REGEX) {
                Pattern.compile(path.getPattern());
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface EntityFactory<R, T> {
        T create(String app, String ip, Integer port, R rule);
    }
}
