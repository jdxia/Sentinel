package com.alibaba.csp.sentinel.demo.spring.cloud.config.sentinelLog;

import com.alibaba.csp.sentinel.log.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 Sentinel 内部日志桥接到 SLF4J（本项目里即 Logback）的基类。
 *
 * 【为什么需要它】
 * Sentinel 默认用自己的 JUL 封装（JavaLoggingAdapter + DateFileLogHandler）写
 * ~/logs/csp/ 下的 sentinel-record.log 和 command-center.log：
 * - 当天 200MB×4 循环覆盖，但【跨天历史文件不会被删除】，无限累积；
 * - 不走应用的 Logback 体系，无法和应用日志统一采集、检索、滚动。
 * 被本类接管后，滚动/归档/清理全部交给 Logback 的 RollingFileAppender
 * （maxHistory + totalSizeCap），和应用日志同一套出口。
 * 本项目在文件输出前使用有界 AsyncAppender，队列满时丢弃诊断日志，避免等待写盘。
 *
 * 【接管原理，生效链路全部在 sentinel-core 源码里】
 * 1. RecordLog / CommandCenterLog 的静态块【优先】查 SPI：
 *    RecordLog.java:33-40 → LoggerSpiProvider.getLogger(LOGGER_NAME)
 * 2. LoggerSpiProvider.resolveLoggers（LoggerSpiProvider.java:52-68）用 JDK 标准
 *    ServiceLoader 扫 classpath 上的 META-INF/services/com.alibaba.csp.sentinel.log.Logger，
 *    读取实现类上 @LogTarget 注解的 value 作为 key 注册（同名取第一个遇到的）；
 * 3. 找不到任何 SPI 实现时，才回落到默认的 JUL 实现（new JavaLoggingAdapter）。
 * 所以本包的两个子类 + resources/META-INF/services 注册文件凑齐后，
 * JUL 那套（含跨天文件无限累积问题）被整体旁路。
 *
 * 【生效时机】
 * LoggerSpiProvider 是静态块里做的 ServiceLoader 扫描，即"第一次有人写 RecordLog"
 * （通常是 SentinelConfig / 规则管理器类加载）时完成绑定，之后进程内不再变化，
 * 更换 SPI 实现需要重启；日志级别和输出策略由 Logback 管理。
 *
 * 【注意接管范围】
 * 只接管 RecordLog、CommandCenterLog 两族日志。
 * sentinel-block.log（EagleEye 写）和 {app}-metrics.log（MetricWriter 写）
 * 不经过此 SPI，它们使用各自的文件滚动策略，不受本类影响。
 *
 * 【占位符约定】
 * Sentinel 的 Logger 接口明确约定占位符就是 slf4j 的 "{}" 风格
 * （见 Logger.java 的 javadoc），所以 format + arguments 可以原样透传给
 * slf4j，不需要自己先做 MessageFormatter 格式化。
 */
public abstract class AbstractSlf4jSentinelLogger implements Logger {

    /**
     * 桥接目标 slf4j logger，logger 名由子类指定。
     * logback-spring.xml 里可以用
     * {@code <logger name="sentinelRecordLog" level="info"/>} 单独控制级别和 appender。
     */
    protected final org.slf4j.Logger logger;

    protected AbstractSlf4jSentinelLogger(String loggerName) {
        this.logger = LoggerFactory.getLogger(loggerName);
    }

    @Override
    public void info(String format, Object... arguments) {
        logger.info(format, arguments);
    }

    @Override
    public void info(String msg, Throwable e) {
        logger.info(msg, e);
    }

    @Override
    public void warn(String format, Object... arguments) {
        logger.warn(format, arguments);
    }

    @Override
    public void warn(String msg, Throwable e) {
        logger.warn(msg, e);
    }

    @Override
    public void trace(String format, Object... arguments) {
        logger.trace(format, arguments);
    }

    @Override
    public void trace(String msg, Throwable e) {
        logger.trace(msg, e);
    }

    @Override
    public void debug(String format, Object... arguments) {
        logger.debug(format, arguments);
    }

    @Override
    public void debug(String msg, Throwable e) {
        logger.debug(msg, e);
    }

    @Override
    public void error(String format, Object... arguments) {
        logger.error(format, arguments);
    }

    @Override
    public void error(String msg, Throwable e) {
        logger.error(msg, e);
    }
}
