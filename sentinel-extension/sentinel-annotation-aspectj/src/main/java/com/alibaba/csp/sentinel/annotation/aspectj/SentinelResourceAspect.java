/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.annotation.aspectj;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

import java.lang.reflect.Method;

/**
 * Aspect for methods with {@link SentinelResource} annotation.
 *
 * @author Eric Zhao
 */
@Aspect
public class SentinelResourceAspect extends AbstractSentinelAspectSupport {
    /**
     * 用法 @SentinelResource(value = "test", blockHandler = "handleException", blockHandlerClass = {ExceptionUtil.class},
     *          fallback = "fallbackHandler", fallbackClass = {FooUtil.class})
     */

    /**
     * 有这个注解就拦截
     */
    @Pointcut("@annotation(com.alibaba.csp.sentinel.annotation.SentinelResource)")
    public void sentinelResourceAnnotationPointcut() {
    }

    @Around("sentinelResourceAnnotationPointcut()")
    public Object invokeResourceWithSentinel(ProceedingJoinPoint pjp) throws Throwable {
        Method originMethod = resolveMethod(pjp);

        /**
         * 注意这个注解, 默认是 EntryType.OUT
         */

        /**
         * 拿这个 SentinelResource 注解
         */
        SentinelResource annotation = originMethod.getAnnotation(SentinelResource.class);
        if (annotation == null) {
            // Should not go through here.
            throw new IllegalStateException("Wrong state for SentinelResource annotation");
        }

        /**
         * 拿的注解的值
         */
        String resourceName = getResourceName(annotation.value(), originMethod);
        EntryType entryType = annotation.entryType();
        int resourceType = annotation.resourceType();
        Entry entry = null;
        try {
            /**
             * 进行限流
             * 往下
             */
            entry = SphU.entry(resourceName, resourceType, entryType, pjp.getArgs());
            // 直接被保护的切面里面的逻辑
            return pjp.proceed();
        } catch (BlockException ex) {
            /**
             * 如果是 BlockException类型的异常
             */
            return handleBlockException(pjp, annotation, ex);
        } catch (Throwable ex) {
            /**
             * 执行业务代码出异常, 不是 BlockException类型的异常
             */


            Class<? extends Throwable>[] exceptionsToIgnore = annotation.exceptionsToIgnore();
            // The ignore list will be checked first.
            if (exceptionsToIgnore.length > 0 && exceptionBelongsTo(ex, exceptionsToIgnore)) {
                throw ex;
            }

            /**
             * exceptionsToIgnore 检查，命中则直接抛 fallback → defaultFallback → 抛出原异常
             */
            if (exceptionBelongsTo(ex, annotation.exceptionsToTrace())) {
                traceException(ex);
                return handleFallback(pjp, annotation, ex);
            }

            // No fallback function can handle the exception, so throw it out.
            throw ex;
        } finally {
            if (entry != null) {

                /**
                 * entry 退出的方法
                 * {@link CtEntry#exit(int, Object...)}
                 *
                 * 往下
                 */
                entry.exit(1, pjp.getArgs());
            }
        }
    }
}
