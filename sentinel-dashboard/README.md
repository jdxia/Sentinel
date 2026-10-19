# Sentinel 控制台

## 0. 概述

Sentinel 控制台是流量控制、熔断降级规则统一配置和管理的入口，它为用户提供了机器自发现、簇点链路自发现、监控、规则配置等功能。在 Sentinel 控制台上，我们可以配置规则并实时查看流量控制效果。

## 1. 编译和启动

### 1.1 如何编译

使用如下命令将代码打包成一个 fat jar:

```bash
mvn clean package
```

### 1.2 如何启动

使用如下命令启动编译后的控制台：

```bash
java -Dserver.port=8080 \
-Dcsp.sentinel.dashboard.server=localhost:8080 \
-Dproject.name=sentinel-dashboard \
-jar target/sentinel-dashboard.jar
```

上述命令中我们指定几个 JVM 参数，其中 `-Dserver.port=8080` 是 Spring Boot 的参数，
用于指定 Spring Boot 服务端启动端口为 `8080`。其余几个是 Sentinel 客户端的参数。

为便于演示，我们对控制台本身加入了流量控制功能，具体做法是引入 Sentinel 提供的 `CommonFilter` 这个 Servlet Filter。
上述 JVM 参数的含义是：

 | 参数 | 作用 |
 | -------- | -------- |
 | `-Dcsp.sentinel.dashboard.server=localhost:8080` | 向 Sentinel 接入端指定控制台的地址 |
 | `-Dproject.name=sentinel-dashboard` | 向 Sentinel 指定应用名称，比如上面对应的应用名称就为 `sentinel-dashboard` |

全部的配置项可以参考 [启动配置项文档](https://github.com/alibaba/Sentinel/wiki/%E5%90%AF%E5%8A%A8%E9%85%8D%E7%BD%AE%E9%A1%B9)。

经过上述配置，控制台启动后会自动向自己发送心跳。程序启动后浏览器访问 `localhost:8080` 即可访问 Sentinel 控制台。

从 Sentinel 1.6.0 开始，Sentinel 控制台支持简单的**登录**功能，默认用户名和密码都是 `sentinel`。用户可以通过如下参数进行配置：

- `-Dsentinel.dashboard.auth.username=sentinel` 用于指定控制台的登录用户名为 `sentinel`；
- `-Dsentinel.dashboard.auth.password=123456` 用于指定控制台的登录密码为 `123456`；如果省略这两个参数，默认用户和密码均为 `sentinel`；
- `-Dserver.servlet.session.timeout=7200` 用于指定 Spring Boot 服务端 session 的过期时间，如 `7200` 表示 7200 秒；`60m` 表示 60 分钟，默认为 30 分钟；

## 2. 客户端接入

选择合适的方式接入 Sentinel，然后在应用启动时加入 JVM 参数 `-Dcsp.sentinel.dashboard.server=consoleIp:port` 指定控制台地址和端口。
确保客户端有访问量，**Sentinel 会在客户端首次调用的时候进行初始化，开始向控制台发送心跳包**，将客户端纳入到控制台的管辖之下。

客户端接入的详细步骤请参考 [Wiki 文档](https://github.com/alibaba/Sentinel/wiki/%E6%8E%A7%E5%88%B6%E5%8F%B0#3-%E5%AE%A2%E6%88%B7%E7%AB%AF%E6%8E%A5%E5%85%A5%E6%8E%A7%E5%88%B6%E5%8F%B0)。

## 3. 验证是否接入成功

客户端正确配置并启动后，会**在初次调用后**主动向控制台发送心跳包，汇报自己的存在；
控制台收到客户端心跳包之后，会在左侧导航栏中显示该客户端信息。如果控制台能够看到客户端的机器信息，则表明客户端接入成功了。

## 4. 本分支的 Nacos 规则持久化

普通模式不启用 `nacos` profile，继续从客户端读取和向客户端推送规则。启用 `nacos` profile 后，
流控（V1、V2）、熔断、热点、系统、授权、网关流控、API 分组接口统一从 Nacos 查询，并将增删改持久化到 Nacos。
客户端通过已有的 Nacos 数据源监听生效，Dashboard 不再向客户端发送这七类配置的 HTTP 更新请求。
这是 Dashboard 进程级模式选择，同一进程不按应用混用两种模式。

### 4.1 配置与启动

复用 `src/main/resources/application-nacos.properties`。Dashboard 和客户端必须使用相同的 Nacos
地址、namespace ID、group 和 Data ID。public namespace 填空字符串，其他 namespace 填 ID。
账号密码通过 `NACOS_USERNAME`、`NACOS_PASSWORD` 环境变量设置，不写入仓库。

在仓库根目录打包：

```bash
mvn -pl sentinel-dashboard -am -DskipTests package
```

启动 Dashboard：

```bash
export NACOS_SERVER_ADDR=127.0.0.1:8848
export NACOS_NAMESPACE=
export NACOS_GROUP=SENTINEL_GROUP
java -jar sentinel-dashboard/target/sentinel-dashboard.jar --spring.profiles.active=nacos
```

当前项目构建请使用 JDK 17。客户端 `SpringCloudSentinelDemoApplication` 也启用 `nacos` profile。
此时示例应用的配置为：

| 规则类型 | Data ID |
| --- | --- |
| 流控 | `sentinel-demo-spring-cloud-nacos-flow-rules` |
| 熔断 | `sentinel-demo-spring-cloud-nacos-degrade-rules` |
| 热点 | `sentinel-demo-spring-cloud-nacos-param-rules` |
| 系统 | `sentinel-demo-spring-cloud-nacos-system-rules` |
| 授权 | `sentinel-demo-spring-cloud-nacos-authority-rules` |

Gateway 示例 `GatewayDemoApplication` 使用同样的启动方式，额外支持以下两个 Data ID：

| 配置类型 | Data ID |
| --- | --- |
| 网关流控 | `spring-cloud-gateway-sentinel-demo-nacos-gw-flow-rules` |
| API 分组 | `spring-cloud-gateway-sentinel-demo-nacos-gw-api-group-rules` |

Gateway 只需创建这两项，首次内容为 `[]`，不需要创建普通应用的五项。
完整的两种启动方式见 [Gateway Demo](../sentinel-demo/sentinel-demo-spring-cloud-gateway/README.md)。

格式为 `${spring.application.name}-${spring.profiles.active}-规则后缀`，与客户端 YAML 一致。
Dashboard 使用目标客户端应用名和自身的 `spring.profiles.active` 拼接，两端的 profile 值必须一致。
多个 profile 会按属性原文拼入 Data ID，例如 `prod,nacos`，不会自动只取其中一个。
配置内容必须是原生 Sentinel Rule JSON 数组，不是 Dashboard 的 `app/ip/port/rule` 包装对象。
客户端 Rule 自身的 `id` 字段与 Dashboard 页面快照 ID 不同，现有原生 `id` 会保留。

首次使用时，先核对目标环境和旧规则，再创建五个 Data ID；无规则时填写 `[]`。
配置缺失、空白、格式错误、完全重复或规则校验不通过时，Dashboard 拒绝发布。
已有规则需要先迁入对应配置，不能用 `[]` 覆盖旧规则。删除最后一条规则会发布 `[]`，不会删除配置项。

### 4.2 启动自动发现与预加载（Nacos 2.x）

启用 `nacos` profile 时，默认开启 `sentinel.nacos.preload-enabled=true`。
Dashboard 收到 `ApplicationReadyEvent` 后，在 Spring Boot 管理的后台执行器中执行一次预加载。
无人访问页面、没有业务客户端上报心跳时，也会执行。预加载流程为：

```text
启动完成 → 分页查询当前 namespace/group 的配置目录
         → 按 -profile-七类配置后缀精确匹配并提取应用名
         → 使用现有 ConfigService 逐项读取正文
         → 复用 NacosRuleStore 的校验、JVM 锁和缓存刷新
```

目录发现使用 Nacos 2.x 的 `/nacos/v1/cs/configs?search=blur` 接口，每页 100 项。
复用 `NACOS_SERVER_ADDR`、`NACOS_NAMESPACE`、`NACOS_GROUP`、`NACOS_USERNAME`、`NACOS_PASSWORD`。
开启鉴权时先通过 `/nacos/v1/auth/login` 获取令牌；查询时将令牌放在 Authorization 请求头。
账号需要具有目标范围的配置列表和读取权限。目录接口不能直接用于 Nacos 3.x。
多个 Nacos 地址使用逗号分隔；一个节点扫描失败后，从下一个节点的第一页重新扫描。
对列表返回的 namespace 和 group 再次精确校验，避免模糊查询扩大实际加载范围。

只加载已存在且匹配的 Data ID，不创建缺失配置，不向业务客户端推送，也不伪造在线机器。
因此，没有客户端心跳的应用不会仅因预加载就出现在原来的在线机器列表中。
预加载失败会记录日志；单项规则失败继续处理其他项，不阻止 Dashboard 启动。
这是一次性启动预加载，不注册规则监听，也不定时扫描；之后的页面查询和写入仍重新读取 Nacos。
启动缓存不能替代写入前的最新读取、旧规则校验和 CAS。

需要关闭预加载时设置 `NACOS_PRELOAD_ENABLED=false`；按需查询和保存仍可用。

### 4.3 防覆盖与并发行为

以 Nacos 已有 A、B，Dashboard 重启后从簇点链路新增 C 为例，调用顺序为：

```text
Controller 校验页面参数
  → 获取当前 Data ID 的 JVM 锁
  → 读取 Nacos 完整原文 A、B，计算原文 MD5
  → 合并本次新增，得到 A、B、C
  → publishConfigCas(dataId, group, 新内容, 原文 MD5, "json")
  → 发布成功后更新页面快照，释放锁
  → 客户端的 Nacos 数据源监听变更
```

- 同一 Data ID 的查询和写入共用 JVM 锁。不同应用、不同类型可并行。不使用分布式锁。
- 修改、删除还会核对页面快照对应的旧规则内容。目标已被修改或删除时，返回刷新提示；其他规则的并发变更会保留。
- 页面 ID 只存在于 Dashboard 内存。相同内容保持 ID，内容变化后分配新 ID；另一用户的旧页面提交失败，必须刷新。
- 缓存只替换当前应用、当前类型，不调用会清空全局规则缓存的 `repository.saveAll()`。
- 只更新本次目标规则，其他规则和页面未建模的扩展字段保留。流控、熔断、系统的 `regex` 不会被旧页面的默认值覆盖。
- CAS 返回 false 或抛出异常时返回失败，不自动重试，也不回退到客户端推送。网络超时可能发生在服务端已保存之后，须重新查询 Nacos 确认结果后再操作。
- 保存成功表示 Nacos 已接受配置，不表示所有客户端已监听生效；需核对客户端规则和运行效果。

支持普通应用五类规则及网关流控、API 分组。API 分组名称必须唯一，匹配项保存为原生路径谓词；
网关流控的时间单位转换为客户端的 `intervalSec`，删除参数条件时显式发布 `paramItem: null`。
两个网关 Data ID 独立发布，不提供跨配置事务。Token Server 集群管理不在本次范围内；
Nacos 模式拒绝 `clusterMode=true`，避免只更新普通客户端数据源而造成集群规则不一致。
同名应用实例共享规则配置，普通 QPS 阈值仍按实例计数。

### 4.4 日志与验证

日志默认在 `~/logs/csp/sentinel-dashboard.log`，搜索 `[NacosRule]`：

- `preload begin`：开始自动发现和预加载。
- `discovery page`：目录分页进度、当前范围累计配置数。
- `discovery node failed`：节点不可用、鉴权或目录响应异常。
- `preload item success/failed`：单项规则结果，包含应用和 Data ID。
- `preload complete`：应用数、匹配配置数、成功数、失败数和耗时；失败数不为零表示部分加载。
- `preload aborted`：目录扫描或整个任务失败；不能将其当作“没有规则”。
- `store ready`：确认 namespace、group、ruleProfile、读取超时。
- `write begin`：获取 operationId、操作类型、应用、Data ID、页面规则 ID。
- `read before write`：核对原规则数量、expectedMd5、JVM 锁等待时间。
- `write success`：核对保存前后数量、原/新 MD5 和总耗时。
- `operation failed`：同一 operationId 记录失败目标、原因和异常栈。不会打印账号密码或完整规则正文。
- `query success`：查看本次读取数量、MD5 和耗时，确认刷新是否读到了新配置。

定向测试命令：

```bash
mvn -o -pl sentinel-dashboard -am \
  -Dtest=NacosConfigCatalogTest,NacosRulePreloaderTest,NacosPreloadConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

本次 17 项启动预加载测试及打包通过，使用模拟 HTTP 目录响应和 ConfigService，覆盖分页、鉴权、
分组与命名空间过滤、多节点切换、五类规则匹配、部分失败、后台启动事件和普通模式。
另外验证预加载后补齐机器信息，以及新增规则仍读取 Nacos 最新完整列表。
本模块显式选择 JUnit 4 运行器，避免被 Boot 的 Jupiter 依赖静默跳过。
前次全模块回归另有 9 项既有失败，已在修改前源码复现：`DashboardConfigTest` 的 JDK 17 反射限制、
两组 Gateway Controller 测试的日期毫秒精度断言，以及 `JsonSerializeTest` 的旧 JSON 断言；本次未修改这些行为。
真实环境还需在独立测试 namespace 验证地址、鉴权、Nacos 客户端/服务端兼容性及 Dashboard → Nacos → 客户端的监听效果。

网关两种模式的回归命令如下。旧 Gateway Controller 测试比较完整 Date 值，需在测试时保留毫秒精度；
该参数不改变生产配置的日期格式。

```bash
mvn -o -pl sentinel-dashboard,sentinel-demo/sentinel-demo-spring-cloud-gateway -am \
  -Dtest=NacosGatewayRuleTest,GatewayDemoConfigurationTest,GatewayFlowRuleControllerTest,GatewayApiControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dspring.jackson.date-format=yyyy-MM-dd HH:mm:ss.SSS' package
```

## 6. 构建Docker镜像

```bash
docker build --build-arg SENTINEL_VERSION=1.8.9 -t ${REGISTRY}/sentinel-dashboard:v1.8.9 .
```

*注意：Sentinel 控制台目前仅支持单机部署。Sentinel 控制台项目提供 Sentinel 功能全集示例，不作为开箱即用的生产环境控制台，不提供安全可靠保障。若希望在生产环境使用请根据[文档](https://github.com/alibaba/Sentinel/wiki/%E5%9C%A8%E7%94%9F%E4%BA%A7%E7%8E%AF%E5%A2%83%E4%B8%AD%E4%BD%BF%E7%94%A8-Sentinel)自行进行定制和改造。*

更多：[控制台功能介绍](./Sentinel_Dashboard_Feature.md)。
