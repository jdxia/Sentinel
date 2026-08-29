package com.alibaba.csp.sentinel.demo.spring.webmvc.config;

import com.alibaba.csp.sentinel.demo.spring.webmvc.vo.ResultWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Spring configuration for global exception handler.
 * This will be activated when the {@code BlockExceptionHandler}
 * throws {@link BlockException directly}.
 *
 * @author kaizi2009
 */
@ControllerAdvice
@Order(0)
public class SentinelSpringMvcBlockHandlerConfig {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * BlockException 是 Sentinel 所有"规则拦截"的抽象父类（注意：它只表示被 Sentinel 拦截，
     * 业务代码自身抛出的异常不会走到这里），共有 5 个子类，按触发规则区分：
     * 1. FlowException        —— 流量控制规则：QPS 或并发线程数超过 FlowRule 阈值
     * 2. DegradeException     —— 熔断降级规则：断路器处于 OPEN（熔断中）/ 半开试探未通过
     * 3. ParamFlowException   —— 热点参数限流规则：携带热点参数的请求量超过 ParamFlowRule 阈值  (在 sentinel-extension 的 parameter-flow-control 模块中)
     * 4. AuthorityException   —— 授权规则：调用来源命中黑名单或不在白名单（AuthorityRule）
     * 5. SystemBlockException —— 系统自适应保护：Load/CPU/平均 RT/总线程数/入口总 QPS 任一指标超过 SystemRule 阈值时整体拒绝
     *
     * 可通过 e.getRule() 拿到具体命中的规则对象，或 instanceof 子类来区分场景、返回不同响应。
     */
    @ExceptionHandler(BlockException.class)
    // 表示返回值不会被当作页面名，而会交给 Spring 的消息转换器序列化。
    @ResponseBody
    public ResultWrapper sentinelBlockHandler(BlockException e) {
        logger.warn("==========> Blocked by Sentinel: {}", e.getRule());

        /*
         * 普通流控、热点参数限流以及未来未识别的 BlockException 默认按 429 处理。
         * 不直接依赖 ParamFlowException，避免当前 WebMVC Demo 强耦合
         * sentinel-parameter-flow-control 扩展模块。
         */
        int httpStatus = getHttpStatus(e);

        /*
         * 当前方法签名不能直接注入 HttpServletResponse，也不能返回 ResponseEntity，
         * 因此只能从当前 Spring MVC 请求上下文中取得响应对象。
         *
         * 做好类型、null 和响应提交状态检查，避免异常处理器再次抛出异常，
         * 将原本可控的 Sentinel 拦截错误升级为 HTTP 500。
         */
        org.springframework.web.context.request.RequestAttributes requestAttributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();

        if (requestAttributes
                instanceof org.springframework.web.context.request.ServletRequestAttributes) {
            org.springframework.web.context.request.ServletRequestAttributes servletRequestAttributes =
                    (org.springframework.web.context.request.ServletRequestAttributes) requestAttributes;
            jakarta.servlet.http.HttpServletResponse response =
                    servletRequestAttributes.getResponse();

            if (response != null && !response.isCommitted()) {
                response.setStatus(httpStatus);
            }
        }

        /*
         * Sentinel 拦截是预期中的高频保护行为，不能逐请求打印 WARN/ERROR。
         * 否则流量越大、拦截越多，日志 IO 和采集压力越大，可能形成日志风暴。
         * 生产监控应使用 Sentinel 指标或低基数的 Micrometer Counter；
         * 如果确实需要日志，应在日志框架或监控层做采样、限频和聚合。
         *
         * 对外仅返回稳定的通用错误，不返回命中的规则对象、阈值、来源规则等内部信息。
         */
        return ResultWrapper.blocked();
    }

    private static int getHttpStatus(BlockException e) {
        int httpStatus = org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value();

        if (e instanceof com.alibaba.csp.sentinel.slots.block.authority.AuthorityException) {
            // 调用来源未通过 Sentinel 黑白名单校验。
            httpStatus = org.springframework.http.HttpStatus.FORBIDDEN.value();

        } else if (e instanceof com.alibaba.csp.sentinel.slots.block.degrade.DegradeException
                || e instanceof com.alibaba.csp.sentinel.slots.system.SystemBlockException) {
            /*
             * 熔断说明当前依赖暂不可用，系统保护说明当前实例已接近容量边界，
             * 两者都属于服务暂时不可用，而不是单个客户端请求频率过高。
             */
            httpStatus = org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE.value();
        }
        return httpStatus;
    }
}
