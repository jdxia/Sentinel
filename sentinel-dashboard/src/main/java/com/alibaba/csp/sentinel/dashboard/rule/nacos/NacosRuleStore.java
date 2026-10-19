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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.RuleEntity;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.DigestUtils;

/**
 * Nacos 是唯一规则源。每次写入都读取完整配置、合并单条变更、CAS 发布，成功后才更新页面快照。
 * 快照 ID 标识一次规则内容；内容变化后更换 ID，使其他用户的旧页面提交明确失败，而不是覆盖新值。
 */
public class NacosRuleStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosRuleStore.class);
    private final ConfigService configService;
    private final String namespace;
    private final String group;
    private final String ruleProfile;
    private final long timeoutMs;
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong operations = new AtomicLong();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Snapshot>> scopes = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

    public NacosRuleStore(ConfigService configService, String namespace, String group,
                          String ruleProfile, long timeoutMs) {
        Assert.notNull(configService, "configService must not be null");
        Assert.hasText(group, "sentinel.nacos.group-id must not be blank");
        Assert.hasText(ruleProfile, "spring.profiles.active must not be blank");
        Assert.isTrue(timeoutMs > 0, "sentinel.nacos.timeout-ms must be positive");
        this.configService = configService;
        this.namespace = namespace;
        this.group = group;
        this.ruleProfile = ruleProfile;
        this.timeoutMs = timeoutMs;
        LOGGER.info("[NacosRule] store ready, namespace={}, group={}, ruleProfile={}, timeoutMs={}",
            namespace, group, ruleProfile, timeoutMs);
    }

    public <T extends RuleEntity> List<T> query(NacosRuleType<T> type, String app, String ip, Integer port)
            throws NacosException {
        String dataId = dataId(type, app);
        long operationId = operations.incrementAndGet();
        long started = System.nanoTime();
        synchronized (lockFor(dataId)) {
            try {
                String content = read(dataId);
                List<JSONObject> rules = parse(type, content);
                List<T> result = refresh(type, app, ip, port, dataId, rules);
                LOGGER.info("[NacosRule] query success, operationId={}, namespace={}, group={}, dataId={}, "
                        + "count={}, md5={}, elapsedMs={}", operationId, namespace, group, dataId,
                    rules.size(), md5(content), elapsedMs(started));
                return result;
            } catch (NacosException | RuntimeException e) {
                logFailure(operationId, "query", dataId, null, started, e);
                throw e;
            }
        }
    }

    public <T extends RuleEntity> T findById(NacosRuleType<T> type, Long id) {
        Snapshot snapshot = id == null ? null : snapshots.get(id);
        return snapshot == null || snapshot.type != type ? null : type.cast(snapshot.toEntity());
    }

    public <T extends RuleEntity> T add(NacosRuleType<T> type, T entity) throws NacosException {
        return change(type, entity, "add");
    }

    public <T extends RuleEntity> T update(NacosRuleType<T> type, T entity) throws NacosException {
        return change(type, entity, "update");
    }

    public <T extends RuleEntity> void delete(NacosRuleType<T> type, T entity) throws NacosException {
        change(type, entity, "delete");
    }

    private <T extends RuleEntity> T change(NacosRuleType<T> type, T entity, String operation)
            throws NacosException {
        String dataId = dataId(type, entity.getApp());
        long operationId = operations.incrementAndGet();
        long started = System.nanoTime();
        LOGGER.info("[NacosRule] write begin, operationId={}, operation={}, app={}, namespace={}, group={}, "
                + "dataId={}, ruleId={}", operationId, operation, entity.getApp(), namespace, group, dataId,
            entity.getId());
        // 同一 Data ID 的读、合并、发布、缓存更新必须在同一把 JVM 锁内，其他应用和规则类型可并行。
        synchronized (lockFor(dataId)) {
            try {
                long lockWaitMs = elapsedMs(started);
                String content = read(dataId);
                List<JSONObject> rules = parse(type, content);
                int beforeCount = rules.size();
                String expectedMd5 = md5(content);
                LOGGER.info("[NacosRule] read before write, operationId={}, dataId={}, count={}, "
                    + "expectedMd5={}, lockWaitMs={}", operationId, dataId, beforeCount, expectedMd5, lockWaitMs);
                int index = rules.size();
                if (!"add".equals(operation)) {
                    Snapshot old = snapshots.get(entity.getId());
                    if (old == null || old.type != type || !old.app.equals(entity.getApp())) {
                        throw new IllegalStateException("规则已变化或应用不匹配，请刷新规则页面后重试");
                    }
                    index = rules.indexOf(old.rule);
                    if (index < 0) {
                        throw new IllegalStateException("Nacos 中的目标规则已被修改或删除，请刷新规则页面后重试");
                    }
                }
                if ("delete".equals(operation)) {
                    rules.remove(index);
                } else {
                    JSONObject changed = type.toRuleJson(entity);
                    if ("add".equals(operation)) {
                        rules.add(changed);
                    } else {
                        // 保留当前页面没有建模的字段，避免编辑一条规则时删除其他版本客户端的扩展配置。
                        JSONObject merged = new JSONObject(rules.get(index));
                        merged.putAll(changed);
                        rules.set(index, merged);
                    }
                    validate(type, rules);
                }
                String published = JSON.toJSONString(rules, SerializerFeature.WriteMapNullValue);
                // MD5 必须由读取的原文计算；重新序列化会改变空白和字段顺序，导致错误的 CAS 冲突。
                if (!configService.publishConfigCas(dataId, group, published, expectedMd5, "json")) {
                    throw new IllegalStateException("Nacos CAS 发布未成功，配置可能已变化，请刷新后重试");
                }
                List<T> saved = refresh(type, entity.getApp(), entity.getIp(), entity.getPort(), dataId, rules);
                LOGGER.info("[NacosRule] write success, operationId={}, operation={}, namespace={}, group={}, "
                        + "dataId={}, ruleId={}, beforeCount={}, afterCount={}, expectedMd5={}, newMd5={}, "
                        + "elapsedMs={}", operationId, operation, namespace, group, dataId, entity.getId(),
                    beforeCount, rules.size(), expectedMd5, md5(published), elapsedMs(started));
                return "delete".equals(operation) ? null : saved.get(index);
            } catch (NacosException | RuntimeException e) {
                // 不重试、不回退到客户端推送；网络异常时提交结果可能未知，必须重新查询 Nacos。
                logFailure(operationId, operation, dataId, entity.getId(), started, e);
                throw e;
            }
        }
    }

    private String read(String dataId) throws NacosException {
        String content = configService.getConfig(dataId, group, timeoutMs);
        // 缺失与空白配置不能当作空规则发布，防止连错环境或读取失败后清空已有配置。
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalStateException("Nacos 配置不存在或为空：" + dataId
                + "；请核对 namespace/group，首次使用请先创建内容为 [] 的配置");
        }
        return content;
    }

    private <T extends RuleEntity> List<JSONObject> parse(NacosRuleType<T> type, String content) {
        Object parsed = JSON.parse(content);
        if (!(parsed instanceof JSONArray)) {
            throw new IllegalArgumentException("Nacos 配置必须是原生 Sentinel Rule JSON 数组");
        }
        List<JSONObject> rules = new ArrayList<>();
        for (Object value : (JSONArray) parsed) {
            if (!(value instanceof JSONObject)) {
                throw new IllegalArgumentException("Nacos 规则数组不能包含 null 或非对象元素");
            }
            rules.add((JSONObject) value);
        }
        validate(type, rules);
        return rules;
    }

    private <T extends RuleEntity> void validate(NacosRuleType<T> type, List<JSONObject> rules) {
        Set<JSONObject> unique = new HashSet<>();
        Set<String> apiNames = new HashSet<>();
        for (JSONObject rule : rules) {
            type.validate(rule);
            // 同名 API 分组在客户端按名称覆盖，必须在 CAS 发布前拒绝歧义配置。
            if (type == NacosRuleType.GW_API_GROUP && !apiNames.add(rule.getString("apiName"))) {
                throw new IllegalArgumentException("API 分组名称重复：" + rule.getString("apiName"));
            }
            if (!unique.add(rule)) {
                throw new IllegalArgumentException("存在完全相同的重复规则，无法安全定位，请先在 Nacos 中去重");
            }
        }
    }

    private <T extends RuleEntity> List<T> refresh(NacosRuleType<T> type, String app, String ip, Integer port,
                                                  String dataId, List<JSONObject> rules) {
        List<Snapshot> previous = scopes.getOrDefault(dataId, new ArrayList<>());
        Map<JSONObject, Snapshot> previousByRule = new HashMap<>();
        for (Snapshot snapshot : previous) {
            previousByRule.put(snapshot.rule, snapshot);
        }
        List<Snapshot> current = new ArrayList<>();
        List<T> entities = new ArrayList<>();
        for (JSONObject rule : rules) {
            Snapshot snapshot = previousByRule.remove(rule);
            if (snapshot == null) {
                snapshot = new Snapshot(ids.incrementAndGet(), type, app, ip, port, rule);
            } else if (ip != null && port != null) {
                // 启动预加载没有机器信息；页面查询时补齐，并保持内容未变化的规则 ID 不变。
                snapshot = new Snapshot(snapshot.id, type, app, ip, port, rule);
            }
            current.add(snapshot);
            T entity = type.toEntity(app, ip, port, rule);
            entity.setId(snapshot.id);
            entities.add(entity);
        }
        // 只淘汰当前应用、当前类型的旧版本；查询另一个应用不能清掉其他用户正在编辑的规则。
        for (Snapshot old : previousByRule.values()) {
            snapshots.remove(old.id);
        }
        for (Snapshot snapshot : current) {
            snapshots.put(snapshot.id, snapshot);
        }
        scopes.put(dataId, current);
        return entities;
    }

    private String dataId(NacosRuleType<?> type, String app) {
        Assert.hasText(app, "app must not be blank");
        return app + "-" + ruleProfile + "-" + type.getSuffix();
    }

    private Object lockFor(String dataId) {
        // 锁与应用生命周期一致，不能在请求结束时移除，否则等待线程与新线程可能拿到不同的锁。
        return locks.computeIfAbsent(dataId, key -> new Object());
    }

    private static String md5(String content) {
        return DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8));
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private void logFailure(long operationId, String operation, String dataId, Long id, long started, Exception e) {
        LOGGER.error("[NacosRule] operation failed; refresh before retry, operationId={}, operation={}, "
                + "namespace={}, group={}, dataId={}, ruleId={}, elapsedMs={}, reason={}",
            operationId, operation, namespace, group, dataId, id, elapsedMs(started), e.getMessage(), e);
    }

    private static final class Snapshot {
        private final long id;
        private final NacosRuleType<?> type;
        private final String app;
        private final String ip;
        private final Integer port;
        private final JSONObject rule;

        private Snapshot(long id, NacosRuleType<?> type, String app, String ip, Integer port, JSONObject rule) {
            this.id = id;
            this.type = type;
            this.app = app;
            this.ip = ip;
            this.port = port;
            this.rule = rule;
        }

        private RuleEntity toEntity() {
            RuleEntity entity = type.toEntity(app, ip, port, rule);
            entity.setId(id);
            return entity;
        }
    }
}
