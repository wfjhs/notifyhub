package com.notifyhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 微信 API 调用用的 HTTP 客户端。
 * <p>
 * <b>为什么不用默认的 SimpleClientHttpRequestFactory</b>：
 * 它底层是 JDK 1.1 时代的 {@code HttpURLConnection}，POST JSON 时会用
 * {@code Transfer-Encoding: chunked}（不带 Content-Length）。微信网关不接受
 * chunked，直接返回 {@code 412 Precondition Failed} 且响应体为空——
 * 于是异常里只有「412 [no body]」，看不到任何微信错误码，非常难排查。
 * 换成 Java 11+ 的 {@code HttpClient} 后，body 是已知长度的 byte[]，
 * 请求带 Content-Length，就不会被拦在业务校验之前。
 * <p>
 * <b>必须显式设置超时</b>：默认没有读超时，微信接口一旦不响应，
 * 调用线程会永久阻塞。在定时补偿任务里，一个挂住的任务会让整批消息都发不出去。
 */
@Configuration
public class RestClientConfig {

    @Bean("weChatRestClient")
    public RestClient weChatRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .requestFactory(factory)
                // 显式声明 UA 与 Accept，避免网关按客户端类型做额外拦截
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) NotifyHub/1.0")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
