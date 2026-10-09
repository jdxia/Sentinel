package com.alibaba.csp.sentinel.demo.spring.cloud.config.sentinelLog;

import com.alibaba.csp.sentinel.log.LogTarget;
import com.alibaba.csp.sentinel.transport.log.CommandCenterLog;

/**
 * 接管 command-center.log（transport 模块命令服务日志）的 SLF4J 实现。
 *
 * 原文件内容：8719/8720 命令服务启动（[CommandCenter] Begin listening at port）、
 * 每次 Dashboard 拉取 /metric、下发规则的 HTTP 命令处理、异常。
 * Dashboard 每秒都会来拉一次监控，所以这个文件在接了 Dashboard 后增长不慢，
 * 是跨天累积问题里最需要接管的一个。
 *
 * 注意点：
 * 1. @LogTarget 的 value 引用 CommandCenterLog.LOGGER_NAME（"sentinelCommandCenterLogger"），
 *    该类在 sentinel-transport-common 里——spring-cloud-starter-alibaba-sentinel
 *    会传递引入，无需额外加依赖；
 * 2. 若项目没引任何 transport 实现（纯 sentinel-core 裸用），本类不会被触发，
 *    CommandCenterLog 本身也不存在，无副作用。
 */
@LogTarget(CommandCenterLog.LOGGER_NAME)
public class Slf4jCommandCenterLogger extends AbstractSlf4jSentinelLogger {

    public Slf4jCommandCenterLogger() {
        super("sentinelCommandCenterLog");
    }
}
