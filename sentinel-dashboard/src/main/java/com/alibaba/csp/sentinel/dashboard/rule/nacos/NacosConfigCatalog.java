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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Nacos 2.x 配置目录；SDK 没有配置列表接口，这里只发现 Data ID，不用列表中的正文更新缓存。 */
public class NacosConfigCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosConfigCatalog.class);
    private static final int PAGE_SIZE = 100;
    private final RestTemplate restTemplate;
    private final List<URI> servers = new ArrayList<>();
    private final String namespace;
    private final String group;
    private final String ruleProfile;
    private final String username;
    private final String password;

    public NacosConfigCatalog(RestTemplate restTemplate, String serverAddr, String namespace, String group,
                               String ruleProfile, String username, String password) {
        Assert.hasText(serverAddr, "sentinel.nacos.server-addr must not be blank");
        this.restTemplate = restTemplate;
        this.namespace = "public".equals(namespace) ? "" : namespace;
        this.group = group;
        this.ruleProfile = ruleProfile;
        this.username = username;
        this.password = password;
        for (String address : serverAddr.split(",")) {
            String value = address.trim();
            URI uri = URI.create(value.contains("://") ? value : "http://" + value);
            Assert.isTrue(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null, "Invalid Nacos server address");
            UriComponentsBuilder builder = UriComponentsBuilder.fromUri(uri);
            if (uri.getPort() < 0) {
                builder.port(8848);
            }
            if (!StringUtils.hasText(uri.getPath()) || "/".equals(uri.getPath())) {
                builder.replacePath("/nacos");
            }
            servers.add(builder.build().toUri());
        }
    }

    public List<String> listDataIds() {
        // 只读发现允许切换节点重试；每次从第一页重新扫描，避免拼接不同节点的不完整分页结果。
        for (URI server : servers) {
            try {
                return listDataIds(server);
            } catch (RestClientException | IllegalStateException | IllegalArgumentException e) {
                LOGGER.warn("[NacosRule] discovery node failed, server={}, namespace={}, group={}, reason={}",
                    server, namespace, group, e.getMessage());
            }
        }
        throw new IllegalStateException("Nacos 配置目录读取失败，请检查 discovery node failed 日志、管理接口权限和服务端版本");
    }

    private List<String> listDataIds(URI server) {
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.hasText(username)) {
            // Token 放在请求头，避免出现在 URL、异常消息和代理的访问日志中。
            headers.setBearerAuth(login(server));
        }
        Set<String> dataIds = new LinkedHashSet<>();
        for (int page = 1; ; page++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Nacos 配置发现已被中断");
            }
            URI uri = endpoint(server, "/v1/cs/configs")
                .queryParam("search", "blur").queryParam("dataId", "*-" + ruleProfile + "-*")
                .queryParam("group", group).queryParam("tenant", namespace)
                .queryParam("pageNo", page).queryParam("pageSize", PAGE_SIZE).build().encode().toUri();
            JSONObject result = request(uri, HttpMethod.GET, new HttpEntity<>(headers));
            Integer pageNumber = result.getInteger("pageNumber");
            Integer pages = result.getInteger("pagesAvailable");
            Object items = result.get("pageItems");
            if (pageNumber == null || pageNumber != page || pages == null || pages < 0
                || !(items instanceof JSONArray)) {
                throw new IllegalStateException("Nacos 配置分页响应格式错误，page=" + page);
            }
            for (Object item : (JSONArray) items) {
                if (!(item instanceof JSONObject)) {
                    throw new IllegalStateException("Nacos 配置列表包含非对象元素，page=" + page);
                }
                JSONObject config = (JSONObject) item;
                String tenant = config.getString("tenant");
                tenant = tenant == null || "public".equals(tenant) ? "" : tenant;
                String dataId = config.getString("dataId");
                // 服务端模糊搜索也作用于 group；必须再次精确校验，不能加载其他环境或分组的规则。
                if (namespace.equals(tenant) && group.equals(config.getString("group"))
                    && StringUtils.hasText(dataId)) {
                    dataIds.add(dataId);
                }
            }
            LOGGER.info("[NacosRule] discovery page, server={}, namespace={}, group={}, page={}, pages={}, "
                    + "pageItems={}, matchedScopeTotal={}", server, namespace, group, page, pages,
                ((JSONArray) items).size(), dataIds.size());
            if (page >= pages) {
                return new ArrayList<>(dataIds);
            }
            if (((JSONArray) items).isEmpty()) {
                throw new IllegalStateException("Nacos 配置分页提前返回空页，page=" + page);
            }
        }
    }

    private String login(URI server) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        JSONObject result = request(endpoint(server, "/v1/auth/login").build().toUri(),
            HttpMethod.POST, new HttpEntity<>(form, headers));
        String token = result.getString("accessToken");
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("Nacos 登录响应缺少 accessToken");
        }
        return token;
    }

    private JSONObject request(URI uri, HttpMethod method, HttpEntity<?> request) {
        String body;
        try {
            body = restTemplate.exchange(uri, method, request, String.class).getBody();
        } catch (RestClientResponseException e) {
            // 服务端错误正文可能带回登录参数，日志只保留状态码，不传播原始响应。
            throw new IllegalStateException("Nacos 管理接口返回 HTTP " + e.getRawStatusCode());
        }
        try {
            JSONObject result = JSON.parseObject(body);
            if (result == null) {
                throw new IllegalArgumentException("empty response");
            }
            return result;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Nacos 管理接口返回了无效 JSON 对象");
        }
    }

    private static UriComponentsBuilder endpoint(URI server, String path) {
        String basePath = server.getPath().replaceAll("/+$", "");
        return UriComponentsBuilder.fromUri(server).replacePath(basePath + path);
    }
}
