package com.alibaba.csp.sentinel.demo.spring.webmvc.config;

import com.alibaba.csp.sentinel.demo.spring.webmvc.vo.ResultWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Spring configuration for global exception handler.
 * This will be activated when the {@code BlockExceptionHandler}
 * throws {@link BlockException} directly.
 *
 * <p>当前项目的 MyBootInterceptorConfig 已将 URL 拦截器中的 BlockException 重新抛出，
 * 因此无需重复注册拦截器。Controller 调用链中向外传播的 BlockException 也会进入这里；
 * 若注解的 blockHandler/fallback 或手动 catch 已经处理异常，则保留其业务响应。</p>
 *
 * @author kaizi2009
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SentinelSpringMvcBlockHandlerConfig {

    /**
     * BlockException 是 Sentinel 所有"规则拦截"的抽象父类（注意：它只表示被 Sentinel 拦截，
     * 普通业务异常不会走到这里），常见的 5 个子类按触发规则区分：
     * 1. FlowException        —— 流量控制规则：QPS 或并发线程数超过 FlowRule 阈值
     * 2. DegradeException     —— 熔断降级规则：断路器处于 OPEN（熔断中）/ 半开试探未通过
     * 3. ParamFlowException   —— 热点参数限流规则：携带热点参数的请求量超过 ParamFlowRule 阈值  (在 sentinel-extension 的 parameter-flow-control 模块中)
     * 4. AuthorityException   —— 授权规则：调用来源命中黑名单或不在白名单（AuthorityRule）
     * 5. SystemBlockException —— 系统自适应保护：Load/CPU/平均 RT/总线程数/入口总 QPS 任一指标超过 SystemRule 阈值时整体拒绝
     *
     * 部分异常可通过 e.getRule() 拿到具体命中的规则对象，或 instanceof 子类来区分场景、返回不同响应。
     * SystemBlockException 通过 getLimitType() 提供系统保护类型，不能假定所有异常都有规则对象。
     *
     * @return HTTP 状态与统一拒绝正文；响应已提交时返回 null，避免二次写入
     */
    @ExceptionHandler(BlockException.class)
    // 表示返回值不会被当作页面名，而会交给 Spring 的消息转换器序列化。
    @ResponseBody
    public ResponseEntity<ResultWrapper> sentinelBlockHandler(BlockException e, HttpServletResponse response) {
        /*
         * Spring MVC 可直接注入 HttpServletResponse，并用 ResponseEntity 设置状态和响应头，
         * 无需再从 ThreadLocal 请求上下文中间接获取响应对象。
         *
         * 做好响应提交状态检查；已提交时不能重写状态或追加 JSON 正文，避免异常处理器再次抛出异常，
         * 将原本可控的 Sentinel 拦截错误升级为 HTTP 500。
         */
        if (response.isCommitted()) {
            return null;
        }

        /*
         * Sentinel 拦截是预期中的高频保护行为，不能逐请求打印 WARN/ERROR。
         * 否则流量越大、拦截越多，日志 IO 和采集压力越大，可能形成日志风暴。
         * 生产监控应使用 Sentinel 指标或低基数的 Micrometer Counter；
         * 如果确实需要日志，应在日志框架或监控层做采样、限频和聚合。
         *
         * 对外仅返回稳定的通用错误，不返回命中的规则对象、阈值、来源规则等内部信息。
         */
        // 保留现有 ResultWrapper 错误码和字段；明确 JSON 类型，防止原接口的文本类型影响序列化。
        // 拒绝结果不能被缓存；各类规则恢复时间不同，因此不编造统一的 Retry-After。
        return ResponseEntity.status(getHttpStatus(e))
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(ResultWrapper.blocked());
    }

    private static HttpStatus getHttpStatus(BlockException e) {
        if (e instanceof AuthorityException) {
            // 调用来源未通过 Sentinel 黑白名单校验。
            return HttpStatus.FORBIDDEN;
        }
        if (e instanceof DegradeException || e instanceof SystemBlockException) {
            /*
             * 熔断说明当前依赖暂不可用，系统保护说明当前实例已接近容量边界，
             * 两者都属于服务暂时不可用，而不是单个客户端请求频率过高。
             */
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        /*
         * 普通流控和热点参数限流按 429 处理。
         * 当前 WebMVC Demo 已引入 sentinel-parameter-flow-control，直接识别 ParamFlowException。
         */
        if (e instanceof FlowException || e instanceof ParamFlowException) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        // 未来未识别的 BlockException 无法推断为限流，按服务暂时不可用处理。
        return HttpStatus.SERVICE_UNAVAILABLE;
    }
}
