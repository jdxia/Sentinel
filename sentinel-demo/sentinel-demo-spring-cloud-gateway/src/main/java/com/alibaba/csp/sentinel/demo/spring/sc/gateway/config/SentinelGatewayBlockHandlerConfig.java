package com.alibaba.csp.sentinel.demo.spring.sc.gateway.config;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLException;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.authority.AuthorityException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.system.SystemBlockException;
import io.netty.channel.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.lang.NonNull;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

/**
 * 为尚未被 fallback 消费的 Sentinel 规则拒绝和下游网络异常提供中文 JSON 提示。
 *
 * <p>Spring Cloud Alibaba 自动注册本 Bean 为网关回调；原异常处理器负责识别规则拒绝和检查响应是否已提交。
 * 网络异常通过独立的 WebExceptionHandler 处理；其他业务异常和已有降级响应保留原行为。
 * 对外不暴露规则、下游地址或异常详情。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SentinelGatewayBlockHandlerConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(SentinelGatewayBlockHandlerConfig.class);

    // 与 BlockException.isBlockException 的查找深度一致，避免异常原因链循环。
    private static final int MAX_CAUSE_DEPTH = 10;

    @Bean
    public BlockRequestHandler sentinelGatewayBlockRequestHandler() {
        return (exchange, throwable) -> {
            Throwable exception = findBlockException(throwable);
            LOGGER.warn("Sentinel blocked request: uri={}, exception={}",
                exchange.getRequest().getURI().getRawPath(), exception.toString());
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
            // 仅有 Sentinel 标记或未知保护类型时，不能推断为限流。
            return blocked(HttpStatus.SERVICE_UNAVAILABLE, "SENTINEL_BLOCKED", "服务暂时不可用");
        };
    }

    @Bean
    @Order(-2)
    public WebExceptionHandler gatewayNetworkExceptionHandler(ServerCodecConfigurer codecConfigurer) {
        ServerResponse.Context responseContext = new ServerResponse.Context() {
            @Override
            @NonNull
            public List<HttpMessageWriter<?>> messageWriters() {
                return codecConfigurer.getWriters();
            }

            @Override
            @NonNull
            public List<ViewResolver> viewResolvers() {
                return Collections.emptyList();
            }
        };
        // Sentinel 处理器先处理规则拒绝，本处理器在 Boot 默认错误页（-1）之前处理网络异常。
        return (exchange, exception) -> {
            if (exchange.getResponse().isCommitted()) {
                return Mono.error(exception);
            }
            HttpStatus status = networkErrorStatusSimple(exception);
            if (status == null) {
                return Mono.error(exception);
            }
            LOGGER.warn("Gateway network error: uri={}", exchange.getRequest().getURI().getRawPath(), exception);
            String code = status == HttpStatus.GATEWAY_TIMEOUT ? "GATEWAY_TIMEOUT" : "GATEWAY_NETWORK_ERROR";
            return blocked(status, code, "网络异常，请稍后再试")
                .flatMap(response -> response.writeTo(exchange, responseContext));
        };
    }

    // shortcut: 简单版只识别 Gateway 的 504 和 I/O 异常，需要细分原始超时类型时切回 networkErrorStatus。
    private static HttpStatus networkErrorStatusSimple(Throwable throwable) {
        if (throwable instanceof ResponseStatusException statusException
            && statusException.getStatusCode().value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        return NestedExceptionUtils.getMostSpecificCause(throwable) instanceof IOException
            ? HttpStatus.BAD_GATEWAY : null;
    }

    // 保留完整版，便于需要精确识别异常原因链和各类超时时切换使用。
    private static HttpStatus networkErrorStatus(Throwable throwable) {
        // Gateway 会将超时包装为 ResponseStatusException，需要沿原因链识别具体异常。
        for (int depth = 0; throwable != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (throwable instanceof TimeoutException || throwable instanceof SocketTimeoutException
                || throwable instanceof ConnectTimeoutException
                || throwable instanceof org.springframework.cloud.gateway.support.TimeoutException
                || throwable instanceof io.netty.handler.timeout.TimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
            if (throwable instanceof SocketException || throwable instanceof UnknownHostException
                || throwable instanceof SSLException || throwable instanceof PrematureCloseException) {
                return HttpStatus.BAD_GATEWAY;
            }
            throwable = throwable.getCause();
        }
        return null;
    }

    private static Throwable findBlockException(Throwable throwable) {
        Throwable cause = throwable;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof BlockException) {
                return cause;
            }
            cause = cause.getCause();
        }
        return throwable;
    }

    private static Mono<ServerResponse> blocked(HttpStatus status, String code, String message) {
        // 拒绝结果不能被缓存；无法统一推断恢复时间，因此不编造 Retry-After。
        return ServerResponse.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .bodyValue(new BlockResponse(code, message));
    }

    public record BlockResponse(String code, String message) {
    }
}
