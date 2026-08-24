/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.demo.spring.webmvc.controller;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 使用 Sentinel 基础 API 对 Spring MVC 接口进行手工埋点的示例。
 *
 * <p>这里保护的是 {@link #RESOURCE_NAME} 手工资源。它与 Spring MVC 适配器自动生成的 URL 资源彼此独立，
 * 测试本类的 try/catch 限流分支时，应当给这个手工资源配置流控规则。</p>
 */
@Controller
public class MySphUController {

    /**
     * 手工埋点与本地流控规则共享的资源名，防止两边分别写字符串后发生配置漂移。
     */
    public static final String RESOURCE_NAME = "my-sphu-demo";

    @GetMapping("/sphu")
    @ResponseBody
    public ResponseEntity<String> apiSphU() {
        // try-with-resources 会在正常返回或业务异常时自动退出 Entry，避免遗漏 entry.exit() 污染当前调用链。
        try (Entry ignored = SphU.entry(RESOURCE_NAME)) {
            return ResponseEntity.ok("Passed by Sentinel");
        } catch (BlockException ignored) {
            // BlockException 是预期的限流结果；返回 429，让调用方可以明确区分“限流拒绝”和“正常成功”。
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Blocked by Sentinel");
        }
    }
}
