/*
 * Copyright 1999-2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.ResourceTypeConstants;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.BaseWebMvcConfig;
import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.config.SentinelWebMvcConfig;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.StringUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Since request may be reprocessed in flow if any forwarding or including or other action
 * happened (see {@link jakarta.servlet.ServletRequest#getDispatcherType()}) we will only
 * deal with the initial request. So we use <b>reference count</b> to track in
 * dispatching "onion" though which we could figure out whether we are in initial type "REQUEST".
 * That means the sub-requests which we rarely meet in practice will NOT be recorded in Sentinel.
 * <p>
 * How to implement a forward sub-request in your action:
 * <pre>
 * initialRequest() {
 *     ModelAndView mav = new ModelAndView();
 *     mav.setViewName("another");
 *     return mav;
 * }
 * </pre>
 *
 * @since 1.8.8
 */
public abstract class AbstractSentinelInterceptor implements AsyncHandlerInterceptor {

    public static final String SENTINEL_SPRING_WEB_CONTEXT_NAME = "sentinel_spring_web_context";
    private static final String EMPTY_ORIGIN = "";

    /**
     * 看这个 {@link SentinelWebMvcConfig}
     *
     * 这个自动装配是在 com.alibaba.cloud.sentinel.SentinelWebAutoConfiguration
     * 就是在自动装配里面做的
     */
    private final BaseWebMvcConfig baseWebMvcConfig;

    public AbstractSentinelInterceptor(BaseWebMvcConfig config) {
        AssertUtil.notNull(config, "BaseWebMvcConfig should not be null");
        AssertUtil.assertNotBlank(config.getRequestAttributeName(), "requestAttributeName should not be blank");
        this.baseWebMvcConfig = config;
    }

    /**
     * @param request
     * @param rcKey
     * @param step
     * @return reference count after increasing (initial value as zero to be increased)
     */
    private Integer increaseReference(HttpServletRequest request, String rcKey, int step) {
        Object obj = request.getAttribute(rcKey);

        if (obj == null) {
            // initial
            obj = Integer.valueOf(0);
        }

        Integer newRc = (Integer) obj + step;
        request.setAttribute(rcKey, newRc);
        return newRc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String resourceName = "";
        try {

            /**
             *  获取 resourceName, 子类实现 {@link SentinelWebInterceptor#getResourceName(HttpServletRequest)}
             */
            resourceName = getResourceName(request);
            if (StringUtil.isEmpty(resourceName)) {
                return true;
            }
            if (increaseReference(request, this.baseWebMvcConfig.getRequestRefName(), 1) != 1) {
                return true;
            }

            /**
             * 解析这个请求来自哪里, 怎么写一个 可以看 自己写的 MyRequestOriginParser
             * cloud 才会取 自定义的 bean, boot要手动设置
             */
            // Parse the request origin using registered origin parser.
            String origin = parseOrigin(request);

            /**
             * 统一上下文 webContextUnify 解释
             * 它决定的是“要不要按请求入口，分别统计同一个资源的调用”。 生产中，你可以把它理解为：我只需要知道这个方法总共被调用了多少次，还是还需要知道这些调用分别从哪个接口进来的？
             *
             * 下单接口 /order/create
             *     → 下单逻辑
             *     → queryStock（查询库存）
             *
             * 报表接口 /report/stock
             *     → 报表逻辑
             *     → queryStock（查询库存）
             *
             * Resource，资源	现在执行的、需要保护的操作	queryStock
             * Context name，上下文名称	这串调用从哪个入口开始	/order/create 或 /report/stock
             * Origin，调用来源	谁来调用这个服务	某个上游应用，例如 order-service
             *
             * 要注意:
             * 统一 Context 名称，也不意味着所有请求共享同一个 Context 对象。
             * 同步调用中的 Context 保存在 ThreadLocal 中；不同线程有自己的上下文状态，而相同 Context 名称会复用相应的入口统计节点。
             * MAX_CONTEXT_NAME_SIZE 默认 2000, 如果新增 Context 触发数量保护时，ContextUtil 会使用 NullContext，后续 CtSph 对这些上下文中的 Entry 跳过规则检查
             *
             * Sentinel 的“上下文整合”本质是在控制统计维度的基数。
             * 开启整合：很多 HTTP 入口共用一个 context，大量统计节点可以复用。
             * 关闭整合：每个 HTTP 入口都可能成为独立 context，Sentinel 为“入口 × 内部资源”分别维护统计节点，所以内存可能迅速膨胀。
             */

            /**
             * 看 {@link SentinelWebInterceptor#getContextName(HttpServletRequest)}
             * 统一 context 返回 sentinel_spring_web_context, 归入同一个 Web 入口分组下统计
             * 按 URL 分 context 返回具体的url
             */
            String contextName = getContextName(request);

            /**
             * 结构可以通过调用 curl http://localhost:8719/tree?type=root 来显示
             *
             * 以 contextName 进入上下文
             * origin 代表来源
             */
            ContextUtil.enter(contextName, origin);

            /**
             * 再以 resourceName 创建资源条目, 下面就是他要保护的资源
             *
             * 相同资源，无论处于哪个 Context，都会共享同一条 Slot Chain
             *
             * 这个里面会判断要不要流控, 如果有问题, 会抛异常走到下面
             * 往下
             *
             * 注意他的退出是在另一个里面 {@link AbstractSentinelInterceptor#afterCompletion(HttpServletRequest, HttpServletResponse, Object, Exception)}
             * Sentinel 的统计是「进出配对」的：SphU.entry(...) 放行时做加法统计，entry.exit(count, args) 退出时做减法和收尾统计（RT、成功数、异常数、热点参数线程数递减）
             */
            Entry entry = SphU.entry(resourceName, ResourceTypeConstants.COMMON_WEB, EntryType.IN);
            request.setAttribute(baseWebMvcConfig.getRequestAttributeName(), entry);

            // 走到 下面的 拦截器 和 controller代码
            return true;
        } catch (BlockException e) {
            /**
             * 无论什么异常, 父类都是 BlockException
             */

            try {
                handleBlockException(request, response, resourceName, e);
            } finally {
                ContextUtil.exit();
            }

            // 不往下走
            return false;
        }
    }

    /**
     * Return the resource name of the target web resource.
     *
     * @param request web request
     * @return the resource name of the target web resource.
     */
    protected abstract String getResourceName(HttpServletRequest request);

    /**
     * Return the context name of the target web resource.
     *
     * @param request web request
     * @return the context name of the target web resource.
     */
    protected String getContextName(HttpServletRequest request) {
        return SENTINEL_SPRING_WEB_CONTEXT_NAME;
    }


    /**
     * 异步servlet走这个
     *
     * When a handler starts an asynchronous request, the DispatcherServlet exits without invoking postHandle and afterCompletion
     * Called instead of postHandle and afterCompletion to exit the context and clean thread-local variables when the handler is being executed concurrently.
     *
     * @param request  the current request
     * @param response the current response
     * @param handler  the handler (or {@link HandlerMethod}) that started async
     *                 execution, for type and/or instance examination
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) throws Exception {

        // 和 afterCompletion一样
        exit(request);
    }

    /**
     * Controller 抛异常时 afterCompletion 也一定会被调用，它就是拦截器链的 "finally"
     *
     * 不会回调它的三种情况
     * 1. preHandle 被流控返回 false, 清理在 preHandle 的 finally
     * 2. preHandle 直接抛 BlockException, 和上面一样
     * 3. Controller 开启异步,  不走 afterCompletion，改走 afterConcurrentHandlingStarted
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) throws Exception {

        // 往下
        exit(request, ex);
    }

    private void exit(HttpServletRequest request) {
        exit(request, null);
    }

    private void exit(HttpServletRequest request, Exception ex) {
        if (increaseReference(request, this.baseWebMvcConfig.getRequestRefName(), -1) != 0) {
            return;
        }

        Entry entry = getEntryInRequest(request, baseWebMvcConfig.getRequestAttributeName());
        if (entry == null) {
            // should not happen
            RecordLog.warn("[{}] No entry found in request, key: {}",
                    getClass().getSimpleName(), baseWebMvcConfig.getRequestAttributeName());
            return;
        }

        // Record the status code here.
//        String resourceName = entry.getResourceWrapper().getName();
//        int status = response.getStatus();
//        StatusCodeMetricManager.getInstance().recordStatusCode(resourceName, status);

        // 往下
        traceExceptionAndExit(entry, ex);
        removeEntryInRequest(request);
        ContextUtil.exit();
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) throws Exception {
    }

    protected Entry getEntryInRequest(HttpServletRequest request, String attrKey) {
        Object entryObject = request.getAttribute(attrKey);
        return entryObject == null ? null : (Entry) entryObject;
    }

    protected void removeEntryInRequest(HttpServletRequest request) {
        request.removeAttribute(baseWebMvcConfig.getRequestAttributeName());
    }

    protected void traceExceptionAndExit(Entry entry, Exception ex) {
        if (entry != null) {
            if (ex != null) {
                Tracer.traceEntry(ex, entry);
            }

            // 往下
            entry.exit();
        }
    }

    protected void handleBlockException(HttpServletRequest request, HttpServletResponse response, String resourceName,
                                        BlockException e)
            throws Exception {

        /**
         * 看用户有没有 自定义 BlockExceptionHandler 这个类型的bean
         */
        if (baseWebMvcConfig.getBlockExceptionHandler() != null) {
            // 交给用户自定义的 BlockExceptionHandler 类型的bean处理
            baseWebMvcConfig.getBlockExceptionHandler().handle(request, response, resourceName, e);

            // Record status when blocked
//            int status = response.getStatus();
//            StatusCodeMetricManager.getInstance().recordStatusCode(resourceName, status);
        } else {
            // Throw BlockException directly. Users need to handle it in Spring global exception handler.
            // NOTE: the status code statistics will be lost here!
            throw e;
        }
    }

    protected String parseOrigin(HttpServletRequest request) {
        String origin = EMPTY_ORIGIN;

        /**
         * 怎么获取到用户的, 可以看 baseWebMvcConfig 这个
         */
        if (baseWebMvcConfig.getOriginParser() != null) {
            origin = baseWebMvcConfig.getOriginParser().parseOrigin(request);
            if (StringUtil.isEmpty(origin)) {
                // 如果没有,就是 ""
                return EMPTY_ORIGIN;
            }
        }
        return origin;
    }

}
