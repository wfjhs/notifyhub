package com.notifyhub.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 根路径跳转到前端控制台。
 * <p>
 * 前后端分离后，前端是独立工程（{@code notifyhub/frontend}，Vite + Vue3），
 * 不再打进后端 jar。这里只负责把误访问后端根路径的人引导过去，
 * 地址由 {@code notify.console-url} 配置：开发指向 Vite dev server，
 * 生产指向 nginx 托管的静态站点。
 */
@Controller
public class HomeController {

    @Value("${notify.console-url:http://localhost:5174}")
    private String consoleUrl;

    @GetMapping("/")
    public String index() {
        return "redirect:" + consoleUrl;
    }
}
