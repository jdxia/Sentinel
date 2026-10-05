package com.alibaba.csp.sentinel.demo.spring.cloud.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一处理 HTTP 请求中尚未被业务处理器消费的 Sentinel 规则拒绝。
 *
 * <p>Spring Cloud Alibaba 自动将 BlockExceptionHandler Bean 注入 Web 拦截器；这里重新抛出
 * BlockException，让 URL 规则拒绝与 Controller 调用链中向外传播的规则拒绝共用 JSON 响应。
 * 无需额外注册 Sentinel 拦截器。注解的 blockHandler/fallback 或手动 catch 若已处理异常，
 * 则保留它们的业务响应，不再进入本处理器。</p>
 *
 * <p>规则拒绝可能高频发生，不逐请求打印 WARN/ERROR；监控使用 Sentinel 指标。
 * 对外仅提供稳定错误码和通用提示，不暴露规则、阈值、来源、资源名或异常详情。</p>
 */
@Configuration(proxyBeanMethods = false)
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SentinelSpringMvcBlockHandlerConfig {

    @Bean
    public BlockExceptionHandler sentinelBlockExceptionHandler() {
        return (request, response, resourceName, exception) -> {
            throw exception;
        };
    }

    /**
     * 已提交的响应无法安全修改状态和正文，此时返回 null，避免二次写入。
     * 本方法只处理 BlockException，业务异常仍交给业务异常处理链。
     */
    @ExceptionHandler(BlockException.class)
    public ResponseEntity<BlockResponse> sentinelBlockHandler(BlockException exception,
                                                             HttpServletResponse response) {
        if (response.isCommitted()) {
            return null;
        }
        if (exception instanceof AuthorityException) {
            return blocked(HttpStatus.FORBIDDEN, "SENTINEL_AUTHORITY_BLOCKED", "请求来源不被允许");
        }
        if (exception instanceof DegradeException) {
            return blocked(HttpStatus.SERVICE_UNAVAILABLE, "SENTINEL_CIRCUIT_OPEN", "服务暂时不可用");
        }
        if (exception instanceof SystemBlockException) {
            return blocked(HttpStatus.SERVICE_UNAVAILABLE, "SENTINEL_SYSTEM_BLOCKED", "服务繁忙，请稍后再试");
        }
        if (exception instanceof ParamFlowException) {
            return blocked(HttpStatus.TOO_MANY_REQUESTS, "SENTINEL_PARAM_FLOW_BLOCKED", "请求过于频繁，请稍后再试");
        }
        if (exception instanceof FlowException) {
            return blocked(HttpStatus.TOO_MANY_REQUESTS, "SENTINEL_FLOW_BLOCKED", "请求过于频繁，请稍后再试");
        }
        // 未识别的保护类型不能推断为限流，统一按服务暂不可用处理。
        return blocked(HttpStatus.SERVICE_UNAVAILABLE, "SENTINEL_BLOCKED", "服务暂时不可用");
    }

    private static ResponseEntity<BlockResponse> blocked(HttpStatus status, String code, String message) {
        // 拒绝结果不能被缓存；无法统一推断各规则的恢复时间，因此不编造 Retry-After。
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(new BlockResponse(code, message));
    }

    public record BlockResponse(String code, String message) {
    }
}
