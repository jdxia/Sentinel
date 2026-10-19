# Spring Cloud Gateway Sentinel Demo

使用 JDK 17，启动类为 `com.alibaba.csp.sentinel.demo.spring.sc.gateway.GatewayDemoApplication`。
一个启动类提供两种模式，与 `SpringCloudSentinelDemoApplication` 的 profile 用法相同。
网关过滤器由 `config/MySentinelGatewayConfiguration` 注册，限流异常处理器仍由 Spring Cloud Alibaba 自动注册，
`config/SentinelGatewayBlockHandlerConfig` 定制规则拒绝提示，并处理向外传播的下游网络异常。
两种模式都不预置本地限流、熔断规则或 API 分组。没有熔断规则时，调用失败仍可执行 fallback，
但不会按失败次数或慢调用比例打开熔断器。

| 模式 | Gateway 启动参数 | Dashboard 启动参数 | 规则来源 |
| --- | --- | --- | --- |
| 默认 | 不设置 `nacos` profile | 不设置 `nacos` profile | Dashboard 通过 HTTP 下发到所选实例的内存 |
| Nacos | `--spring.profiles.active=nacos` | `--spring.profiles.active=nacos` | Dashboard 写 Nacos，Gateway 启动读取并监听变更 |

Dashboard 的模式是进程级配置。需要同时体验两种模式时，启动两个不同端口的 Dashboard，
并通过 `SENTINEL_DASHBOARD` 将各网关指向对应实例。切换模式时两端一起重启。

## 默认模式：不接入 Nacos

直接在 IDEA 运行 Gateway 和 Dashboard 的 main 方法，不添加 `nacos` profile。
Gateway 的 main 方法会在 Spring 启动前设置 `csp.sentinel.app.type=11`，无需额外添加 VM 参数。
若 Dashboard 已将同名应用登记为普通应用，需重启 Dashboard 清除旧的应用类型，再重启 Gateway。

默认地址：

- Dashboard：`http://127.0.0.1:8678`，可通过 `SENTINEL_DASHBOARD` 覆盖网关连接地址。
- Gateway 业务端口：`8090`，可通过 `--server.port` 覆盖。
- Gateway Sentinel 通信端口：`8721`，可通过 `SENTINEL_PORT` 覆盖。端口占用时 Sentinel 会尝试后续端口，以实际日志为准。
- 应用名：`spring-cloud-gateway-sentinel-demo`。

访问 `http://127.0.0.1:8090/httpbin/get` 产生网关流量，再在 Dashboard 的网关流控页面配置规则。
此路径转发到外部 `httpbin.org`，需要外网；也可以将 `httpbin_route` 的 URI 改为本地测试服务。
资源名填写 `httpbin_route`，不是 `/httpbin/get`。`aliyun_route` 匹配 `/product/**`。

默认模式不创建 Nacos 数据源。规则仅在所选网关实例的内存中生效，网关重启后不保留，
也不会自动同步到其他网关实例。此模式用于学习或临时验证。

## 友好的异常响应

`config/SentinelGatewayBlockHandlerConfig` 沿用普通 Spring Cloud Demo 的 `code`、`message` 格式。
Sentinel 拦截通过 `BlockRequestHandler` Bean 接入 Starter；网络异常由 WebFlux 的 `WebExceptionHandler` 处理。
默认模式和 Nacos 模式均自动生效，无需修改 `main` 或添加配置。

| 异常类型 | HTTP 状态 | code | message |
| --- | --- | --- | --- |
| 流控 | 429 | `SENTINEL_FLOW_BLOCKED` | 请求过于频繁，请稍后再试 |
| 热点参数流控（包括网关规则产生的参数拒绝） | 429 | `SENTINEL_PARAM_FLOW_BLOCKED` | 请求过于频繁，请稍后再试 |
| 熔断拒绝 | 503 | `SENTINEL_CIRCUIT_OPEN` | 服务暂时不可用 |
| 系统保护 | 503 | `SENTINEL_SYSTEM_BLOCKED` | 服务繁忙，请稍后再试 |
| 来源访问控制 | 403 | `SENTINEL_AUTHORITY_BLOCKED` | 请求来源不被允许 |
| 其他 Sentinel 规则拒绝 | 503 | `SENTINEL_BLOCKED` | 服务暂时不可用 |
| I/O 异常（包括连接失败、DNS/TLS 错误、连接提前关闭） | 502 | `GATEWAY_NETWORK_ERROR` | 网络异常，请稍后再试 |
| Gateway 已转换为 HTTP 504 的超时异常 | 504 | `GATEWAY_TIMEOUT` | 网络异常，请稍后再试 |

默认使用 `networkErrorStatusSimple`：只判断 Gateway 的 504 状态和根原因是否为 `IOException`。
原来的 `networkErrorStatus` 完整版保留；需要区分各种原始超时类型时，将调用处切回该方法即可。
简单版将原始 I/O 超时也归为 502，未转换为 504 的其他超时交给原处理链。

例如下游连接失败时，响应为 HTTP `502`：

```json
{"code":"GATEWAY_NETWORK_ERROR","message":"网络异常，请稍后再试"}
```

这些响应统一使用 `application/json` 和 `Cache-Control: no-store`，不向客户端暴露资源名、阈值或异常详情。
异常类型映射不代表已启用对应规则，规则仍由 Dashboard 或 Nacos 下发。
不要同时设置 `spring.cloud.sentinel.scg.fallback.mode`，Starter 的该配置会覆盖自定义限流回调。

路由已有的 `CircuitBreaker` / `fallbackUri` 先执行。下游降级接口成功返回时保留其状态和正文；
降级接口也发生连接失败或超时并向外抛出时，才返回上述网络异常提示。
下游直接返回的 HTTP 错误响应不是网络异常，不会在这里改写；普通业务异常仍交给原处理链。
响应已经提交时无法替换正文，此时继续传播异常，避免二次写入。

运行异常响应测试（不连接 Dashboard、Nacos 或公网）：

```bash
mvn -pl sentinel-demo/sentinel-demo-spring-cloud-gateway -am \
  -Dtest=SentinelGatewayBlockHandlerConfigTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 客户端 IP：优先读取代理头

`config/MySentinelGatewayConfiguration` 使用 Sentinel 的 `ConfigurableRequestItemParser`，仅定制客户端 IP 提取。
默认模式和 Nacos 模式均生效，过滤器顺序继续使用 `spring.cloud.sentinel.scg.order`。

依次检查 `X-Forwarded-For`、`Proxy-Client-IP`、`WL-Proxy-Client-IP`、`HTTP_CLIENT_IP`、
`HTTP_X_FORWARDED_FOR`。每个头按逗号拆分、去除两端空白，取第一个合法的 IPv4 或 IPv6 地址。
空值、`unknown` 和非法 IP 会被跳过；全部没有有效值时，回退到请求的 `remoteAddress`，保留回环地址。
例如 `X-Forwarded-For: 203.0.113.10, 10.0.0.20` 使用 `203.0.113.10` 参与客户端 IP 限流。

此 Demo 信任上述代理头，仅适用于可信代理已清理或重写这些头的环境。
如果客户端可以直接访问网关或自行控制这些头，就可以伪造限流 IP；部署时须限制网关入口并配置代理。
此处不修改发给下游的 Header，不做 Token 摘要，也不把 `127.0.0.1` 替换为网卡地址。

运行 IP 提取与过滤器装配测试（无需连接 Dashboard 或 Nacos）：

```bash
mvn -pl sentinel-demo/sentinel-demo-spring-cloud-gateway -am \
  -Dtest=GatewayClientIpTest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 熔断降级：复用官方 fallbackUri

`config/CircuitBreakerSentinelFilterFactory` 把已有的 Sentinel 熔断器工厂接入 Gateway，过滤器名称为
`CircuitBreaker`。转发、路径变量展开和请求体缓存均复用官方父类；本模块没有新增依赖。
`GatewayDemoApplication` 的组件扫描会注册适配类和 `GatewayFallbackController`。

路由不配置 `fallbackUri` 时，默认把原请求路径追加 `/fallback`，再由网关路由转发给原下游服务。
例如 `/product/123` 失败后调用商品服务的 `/product/123/fallback`；降级数据和响应状态由下游接口提供。
路由配置了 `fallbackUri` 时，优先使用路由自己的地址：

```yaml
demo:
  gateway:
    default-fallback-uri: forward:/{sentinelOriginalPath}/fallback

# 放在需要熔断的路由 filters 中，例如 aliyun_route：
filters:
  - name: CircuitBreaker
    args:
      name: aliyun_route_cb
      # 省略 fallbackUri，自动在原请求路径后追加 /fallback。
      statusCodes: [500, 502, 503, 504]
```

特殊路由直接填写自己的 `fallbackUri`，例如 `httpbin_route` 使用
`forward:/httpbin/fallback{segments}`。修改统一默认值只影响未配置 `fallbackUri` 的路由，不影响这些独立配置。
`demo.gateway.default-fallback-uri` 是本 Demo 适配类读取的配置，不是 Gateway 官方属性。
此项省略时也默认追加 `/fallback`；显式设为空字符串时关闭统一默认值，保留路由独立地址。
`sentinelOriginalPath` 由适配类在路径重写前放入当前请求的路径变量中，内容不含开头的 `/`，不修改路由共享配置。
每条需要熔断的路由仍须配置一个 `CircuitBreaker`。
此处不使用 `default-filters`，因为默认过滤器会与路由过滤器叠加，不能靠添加同名过滤器覆盖。

| 正常请求 | Sentinel 熔断资源名 | 网关内部转发路径 | 下游收到的降级路径 |
| --- | --- | --- | --- |
| `/product/123` | `aliyun_route_cb` | `/product/123/fallback` | `/product/123/fallback` |
| `/httpbin/status/500` | `httpbin_route_cb` | `/httpbin/fallback/status/500` | `/fallback/status/500` |

`httpbin_route` 的 `Path=/httpbin/{*segments}` 捕获剩余路径，`segments` 已包含开头的 `/`。
`CircuitBreaker` 放在 `RewritePath` 前，降级时使用原路由捕获的变量。
`forward:` 先在网关内部重新分派请求，再匹配路由并向下游发送 HTTP 请求，客户端不会收到重定向。
当前 `/product/**` 和 `/httpbin/{*segments}` 都能匹配降级路径，因此继续使用原服务的 URI。
若改用精确路径 `/product/{id}`，还须配置能匹配 `/product/{id}/fallback` 的路由并指向原服务。
独立配置降级地址时，也须确保新路径映射到预期服务，不能假设它一定复用原路由。

适配类用官方内部异常属性识别降级请求，并跳过再次执行 CircuitBreaker，防止已打开的熔断器拒绝降级请求或循环降级。
其他网关过滤器仍会执行。原请求方法、查询参数和 POST 请求体会保留，下游降级接口须支持相同的请求方法。
第一次业务失败通常产生两次下游请求；熔断期间跳过业务接口，只调用下游降级接口。
下游降级接口应返回缓存或简化结果，不应再次执行原来的故障依赖或重复提交写操作。

连接失败、向外传播的调用异常、Sentinel 熔断拒绝，以及配置的 HTTP `500/502/503/504` 都会进入 fallback。
普通 HTTP `4xx` 或 HTTP `200` 内的业务失败码不会因此降级。正常调用保持原响应。
下游降级接口的状态码和响应体直接返回给客户端；它再次失败时不会继续追加 `/fallback`。
若整个下游服务不可达，降级接口同样无法响应，这种方式不能代替独立的容灾服务。

原异常由官方父类放入 `CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR`，该属性仅在网关进程中使用，不会自动成为请求头。
下游若确实需要异常上下文，可单独接入官方 `FallbackHeadersGatewayFilterFactory`，当前未下发异常详情。
仍可显式配置 `forward:/fallback` 使用本地 `GatewayFallbackController`，此时由网关返回固定 HTTP `503` 和 JSON。
路径变量与转发至外部服务的方式见 [Gateway 官方文档](https://docs.spring.io/spring-cloud-gateway/reference/4.3/spring-cloud-gateway-server-webflux/gatewayfilter-factories/circuitbreaker-filter-factory.html)。
统一默认值为空且路由也没有配置 fallbackUri 时，适配类将 `TimeoutException` 转为 HTTP `504`，将 `DegradeException` 转为
HTTP `503`；`resumeWithoutError=true` 仅允许忽略其他普通异常，不会把超时和熔断拒绝变成成功。

配置中的公网 URI 仅作路由示例，不保证提供本 Demo 约定的降级接口。
手动联调时，先将 `aliyun_route.uri` 指向同时提供 `/product/{id}` 和 `/product/{id}/fallback` 的商品服务。
访问 `http://127.0.0.1:8090/product/123`，让业务接口返回配置的错误状态，即可检查下游降级响应。
想验证熔断器打开后跳过业务接口，可在 Dashboard 为 `aliyun_route_cb` 配置熔断规则。
Nacos 模式也可在下文的 `degrade-rules` 配置中保存以下学习用规则：

```json
[
  {
    "resource": "aliyun_route_cb",
    "grade": 2,
    "count": 2,
    "minRequestAmount": 5,
    "statIntervalMs": 10000,
    "timeWindow": 10
  }
]
```

该示例在 10 秒统计窗口内至少完成 5 次调用、异常数超过 2 次时熔断，熔断窗口为 10 秒。
让 `/product/123` 连续失败触发熔断，再检查商品服务日志：熔断期间只应收到 `/product/123/fallback`。
网关限流规则仍使用 `aliyun_route`；熔断规则使用独立的 `aliyun_route_cb`，避免两个入口重复统计同名资源。
示例只用于手动学习，不会在启动时自动加载；修改已有 Nacos 配置时须保留其他资源的规则。

独立运行降级测试（使用本机临时端口，不连接 Dashboard、Nacos 或公网）：

```bash
mvn -pl sentinel-demo/sentinel-demo-spring-cloud-gateway -am \
  -Dtest=GatewayCircuitBreakerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

测试通过本机 HTTP 下游验证默认追加路径、独立地址和路径重写、POST 请求体与编码参数保留、熔断后只调用降级接口、
降级接口失败不循环、路由间熔断隔离、无 fallback 时的错误状态和 Nacos 熔断数据源绑定。
请求体缓存会占用内存，大文件上传应单独规划路由和降级策略。

## Nacos 模式：持久化规则

Gateway 加载 `application.yml`，再合并 `application-nacos.yml`。
Dashboard 使用本仓库中已支持网关规则和 API 分组的版本。

两端设置相同的环境变量：

```bash
export NACOS_SERVER_ADDR=127.0.0.1:8848
export NACOS_NAMESPACE=
export NACOS_GROUP=SENTINEL_GROUP
```

public namespace 留空；其他 namespace 填 ID，不填显示名称。
开启鉴权时，通过 `NACOS_USERNAME` 和 `NACOS_PASSWORD` 注入各自账号。
Dashboard 需要配置读写权限，Gateway 只需读取和监听权限。

首次使用时，在上述 namespace/group 创建三个配置，内容都填写 `[]`：

| Data ID | 内容类型 | Gateway `rule-type` |
| --- | --- | --- |
| `spring-cloud-gateway-sentinel-demo-nacos-gw-flow-rules` | 原生 `GatewayFlowRule` JSON 数组 | `gw-flow` |
| `spring-cloud-gateway-sentinel-demo-nacos-gw-api-group-rules` | 原生 `ApiDefinition` JSON 数组 | `gw-api-group` |
| `spring-cloud-gateway-sentinel-demo-nacos-degrade-rules` | 原生 `DegradeRule` JSON 数组 | `degrade` |

已有配置不能用 `[]` 覆盖。Data ID 统一按 `${spring.application.name}-${spring.profiles.active}-规则后缀`
拼接。Dashboard 和 Gateway 的 `spring.profiles.active` 值必须一致；例如设置为 `prod,nacos` 时，
Data ID 中也会包含 `prod,nacos`，不会自动只取其中一个 profile。

给两个 main 方法都添加 Program arguments：

```text
--spring.profiles.active=nacos
```

之后在 Dashboard 中新增、修改、删除网关规则、API 分组或熔断规则。控制台先读取 Nacos 的最新完整列表，
合并变更并用 CAS 发布，成功后才更新页面快照。发布失败不会回退到单实例 HTTP 下发。
所有订阅同一 Data ID 的网关监听更新。删除最后一项会发布 `[]`，不删除配置项。

网关限流资源名应与路由 ID 或 API 分组名称一致；熔断资源名与路由中的 `CircuitBreaker.args.name` 一致。
使用 API 分组限流时，先创建分组，再配置引用它的规则。三个 Data ID 分别发布，不是跨配置事务。
网关限流使用 `gw-flow`，不能误用普通 `flow`；熔断使用 `degrade`。

## 打包与验证

在仓库根目录执行：

```bash
mvn -pl sentinel-dashboard,sentinel-demo/sentinel-demo-spring-cloud-gateway -am -DskipTests package
```

默认启动网关：

```bash
java -jar sentinel-demo/sentinel-demo-spring-cloud-gateway/target/sentinel-demo-spring-cloud-gateway-1.8.9.jar
```

Nacos 模式在该命令末尾添加 `--spring.profiles.active=nacos`。
Dashboard 对应的 JAR 为 `sentinel-dashboard/target/sentinel-dashboard.jar`。

运行本次新增测试：

```bash
mvn -pl sentinel-dashboard,sentinel-demo/sentinel-demo-spring-cloud-gateway -am \
  -Dtest=NacosGatewayRuleTest,GatewayDemoConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试覆盖默认自动装配、无本地规则、两个数据源绑定、规则属性更新后的放行/429、网关规则与分组 CRUD、
CAS 失败、旧快照冲突和预加载。Nacos 远程服务由测试替身代替，不代表真实服务端鉴权和网络联调已完成。

生产验收还需验证两实例同步、两端重启恢复、Nacos 断开及冷启动无配置时的行为。
当前复用 Starter 的数据源行为，没有增加“规则未就绪就拒绝接流量”的启动门禁。
同一份规则分发给多个实例仍按实例限流，不等于共享集群总额度。
Dashboard 和 Sentinel 通信端口应仅在受控网络开放。
