package com.alibaba.csp.sentinel.demo.spring.cloud.config.sentinelLog;

import com.alibaba.csp.sentinel.log.LogTarget;
import com.alibaba.csp.sentinel.log.RecordLog;

/**
 * 接管 sentinel-record.log（Sentinel 通用运行日志）的 SLF4J 实现。
 *
 * 原文件内容：初始化过程（[InitExecutor] Found init func）、规则加载/变更
 * （[FlowRuleManager] Flow rules loaded）、metric 文件滚动
 * （[MetricWriter] New metric file created）等，是排查"Sentinel 为什么没生效"的第一站。
 *
 * 接管后这些日志由 Logback 异步写入独立 Sentinel 日志文件，原 JUL 文件不再产生，
 * 也就不存在跨天文件无限累积的问题。
 *
 * 注意：@LogTarget 的 value 必须与 RecordLog.LOGGER_NAME 完全一致
 * （即 "sentinelRecordLogger"），LoggerSpiProvider 以它为 key 查找；
 * 写错不会报错，只会静默回落 JUL 实现，等于白配。
 */
@LogTarget(RecordLog.LOGGER_NAME)
public class Slf4jRecordLogger extends AbstractSlf4jSentinelLogger {

    /**
     * slf4j 侧的 logger 名，和业务 logger 命名空间区分开，
     * 方便在 logback 配置里单独调整级别：
     * {@code <logger name="sentinelRecordLog" level="info"/>}
     */
    public Slf4jRecordLogger() {
        super("sentinelRecordLog");
    }
}
