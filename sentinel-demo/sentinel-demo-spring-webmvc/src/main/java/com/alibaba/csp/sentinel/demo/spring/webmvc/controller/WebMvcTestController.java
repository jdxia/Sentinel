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

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.ModelAndView;

/**
 * Test controller
 *
 * @author kaizi2009
 */
@Controller
public class WebMvcTestController {

    @GetMapping("/hello")
    @ResponseBody
    public String apiHello() {
//        WebMvcTestController obj = (WebMvcTestController) AopContext.currentProxy();
        doBusiness();
        return "Hello!";
    }

    @GetMapping("/async")
    @ResponseBody
    public DeferredResult<String> distribute() throws Exception {
        DeferredResult<String> result = new DeferredResult<>(4000L);

        Thread thread = new Thread(() -> result.setResult("async result"));
        thread.start();

        return result;
    }

    /**
     * blockHandler：当流量控制或熔断降级触发时，会调用该方法，返回对应的提示信息。
     */
    @SentinelResource(value = "business", blockHandler = "blockHandler")
    public String doBusiness() {
        Random random = new Random(1);
        try {
            TimeUnit.MILLISECONDS.sleep(random.nextInt(100));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return "ok";
    }

    public String blockHandler(BlockException blockException) {
        System.out.println("=============> blockHandler");

        return "block...";
    }


}
