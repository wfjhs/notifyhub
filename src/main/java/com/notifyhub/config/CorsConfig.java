package com.notifyhub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。
 * <p>
 * 前后端分离后，前端与后端不再同源：
 * <ul>
 *     <li>开发时前端在 5174，通过 Vite proxy 转发 /api —— 这条路径其实不产生跨域；</li>
 *     <li>生产时前端在 nginx / CDN，若浏览器直连后端 API 域名，就会触发预检请求。</li>
 * </ul>
 * 所以允许来源做成可配置项，默认只放行本地开发地址。
 * <p>
 * <b>不要图省事写 {@code allowedOrigins("*")}</b>：一旦将来加上 Cookie 或鉴权头，
 * 通配来源与凭据不能共存，而且等于允许任何站点拿用户的浏览器当跳板。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${notify.cors.allowed-origins:http://localhost:5174}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 预检结果缓存一小时，避免每个请求都多一次 OPTIONS 往返
                .maxAge(3600);
    }
}
