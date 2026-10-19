# Spring Cloud Gateway Sentinel Demo

使用 JDK 17，启动类为 `com.alibaba.csp.sentinel.demo.spring.sc.gateway.GatewayDemoApplication`。
一个启动类提供两种模式，与 `SpringCloudSentinelDemoApplication` 的 profile 用法相同。
网关过滤器和限流异常处理器由 Spring Cloud Alibaba 自动注册，不再使用 `GatewayConfiguration`。
两种模式都不预置本地限流规则或 API 分组。没有外部规则时，Sentinel 不执行这些规则的限流。

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

首次使用时，在上述 namespace/group 创建两个配置，内容都填写 `[]`：

| Data ID | 内容类型 | Gateway `rule-type` |
| --- | --- | --- |
| `spring-cloud-gateway-sentinel-demo-nacos-gw-flow-rules` | 原生 `GatewayFlowRule` JSON 数组 | `gw-flow` |
| `spring-cloud-gateway-sentinel-demo-nacos-gw-api-group-rules` | 原生 `ApiDefinition` JSON 数组 | `gw-api-group` |

已有配置不能用 `[]` 覆盖。Data ID 统一按 `${spring.application.name}-${spring.profiles.active}-规则后缀`
拼接。Dashboard 和 Gateway 的 `spring.profiles.active` 值必须一致；例如设置为 `prod,nacos` 时，
Data ID 中也会包含 `prod,nacos`，不会自动只取其中一个 profile。

给两个 main 方法都添加 Program arguments：

```text
--spring.profiles.active=nacos
```

之后在 Dashboard 中新增、修改、删除网关规则或 API 分组。控制台先读取 Nacos 的最新完整列表，
合并变更并用 CAS 发布，成功后才更新页面快照。发布失败不会回退到单实例 HTTP 下发。
所有订阅同一 Data ID 的网关监听更新。删除最后一项会发布 `[]`，不删除配置项。

规则资源名应与路由 ID 或 API 分组名称一致。使用 API 分组限流时，先创建分组，再配置引用它的规则；
两个 Data ID 分别发布，不是跨配置事务。网关规则使用 `gw-flow`，不能误用普通 `flow`。

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
