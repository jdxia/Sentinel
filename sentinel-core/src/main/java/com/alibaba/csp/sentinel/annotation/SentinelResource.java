/*
 * Copyright 1999-2020 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.annotation;

import com.alibaba.csp.sentinel.EntryType;

import java.lang.annotation.*;

/**
 * The annotation indicates a definition of Sentinel resource.
 *
 * @author Eric Zhao
 * @author zhaoyuguang
 * @since 0.1.1
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface SentinelResource {

    /**
     * @return name of the Sentinel resource
     *
     * Sentinel资源的名称，我们不仅可以通过url进行限流，也可以把此值作为资源名配置，一样可以限流。
     */
    String value() default "";

    /**
     * @return the entry type (inbound or outbound), outbound by default
     *
     * 条目类型（入站或出站），默认为出站（EntryType.OUT）
     */
    EntryType entryType() default EntryType.OUT;

    /**
     * @return the classification (type) of the resource
     * @since 1.7.0
     *
     * 资源的分类（类型）
     */
    int resourceType() default 0;

    /**
     * @return name of the block exception function, empty by default
     *
     * 块异常函数的名称，默认为空
     */
    String blockHandler() default "";

    /**
     * The {@code blockHandler} is located in the same class with the original method by default.
     * However, if some methods share the same signature and intend to set the same block handler,
     * then users can set the class where the block handler exists. Note that the block handler method
     * must be static.
     *
     * @return the class where the block handler exists, should not provide more than one classes
     *
     * 指定块处理方法所在的类。
     * 默认情况下， blockHandler与原始方法位于同一类中。
     * 但是，如果某些方法共享相同的签名并打算设置相同的块处理程序，则用户可以设置存在块处理程序的类。
     * 请注意，块处理程序方法必须是静态的。
     */
    Class<?>[] blockHandlerClass() default {};

    /**
     * @return name of the fallback function, empty by default
     *
     * 后备函数的名称，默认为空
     */
    String fallback() default "";

    /**
     * The {@code defaultFallback} is used as the default universal fallback method.
     * It should not accept any parameters, and the return type should be compatible
     * with the original method.
     *
     * @return name of the default fallback method, empty by default
     * @since 1.6.0
     *
     * 默认后备方法的名称，默认为空
     * 它不应接受任何参数，并且返回类型应与原始方法兼容
     */
    String defaultFallback() default "";

    /**
     * The {@code fallback} is located in the same class with the original method by default.
     * However, if some methods share the same signature and intend to set the same fallback,
     * then users can set the class where the fallback function exists. Note that the shared fallback method
     * must be static.
     *
     * @return the class where the fallback method is located (only single class)
     * @since 1.6.0
     *
     * fallback方法所在的类（仅单个类）。
     * 默认情况下， fallback与原始方法位于同一类中。
     * 但是，如果某些方法共享相同的签名并打算设置相同的后备，则用户可以设置存在后备功能的类。
     * 请注意，共享的后备方法必须是静态的
     */
    Class<?>[] fallbackClass() default {};

    /**
     * @return the list of exception classes to trace, {@link Throwable} by default
     * @since 1.5.1
     *
     * 异常类的列表追查，默认 Throwable
     */
    Class<? extends Throwable>[] exceptionsToTrace() default {Throwable.class};

    /**
     * Indicates the exceptions to be ignored. Note that {@code exceptionsToTrace} should
     * not appear with {@code exceptionsToIgnore} at the same time, or {@code exceptionsToIgnore}
     * will be of higher precedence.
     *
     * @return the list of exception classes to ignore, empty by default
     * @since 1.6.0
     *
     * 要忽略的异常类列表，默认情况下为空
     */
    Class<? extends Throwable>[] exceptionsToIgnore() default {};
}
