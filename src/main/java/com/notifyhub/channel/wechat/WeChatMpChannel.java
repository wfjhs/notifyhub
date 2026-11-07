package com.notifyhub.channel.wechat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notifyhub.channel.ChannelCredentials;
import com.notifyhub.channel.NotifyChannel;
import com.notifyhub.model.entity.Message;
import com.notifyhub.model.enums.ChannelType;
import com.notifyhub.service.ChannelAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信公众号模板消息通道。
 * <p>
 * 相比企业微信机器人复杂得多，难点全在 access_token 上：
 * 必须先换 token 才能发消息，而 token 有有效期、有调用配额、且新 token 会让旧的失效。
 * 这部分逻辑抽在 {@link WeChatTokenManager} 里，本类只负责拼装消息体与处理错误码。
 *
 * <b>多账号</b>：凭据由 {@link ChannelAccountService} 按 {@code message.accountId}
 * 解析。绑定了账号就用账号的 appId / templateId，没绑定就回落全局配置。
 * 通道本身不直接读 application.yml，这样才能同时接多个公众号。
 *
 * <b>错误码分类处理</b>（这是本类最值得看的地方）：
 * <ul>
 *     <li>{@code 40001 / 42001}：token 失效 —— 清该 appId 的缓存并<b>立即重试一次</b>。
 *         这是唯一值得立即重试的情况，因为重试前换了新 token，成功率高；</li>
 *     <li>{@code 45009}：调用频率超限 —— <b>不能立即重试</b>，抛异常交给外层指数退避，
 *         否则越重试越超限；</li>
 *     <li>其他错误码：直接失败，让消息进入重试队列。</li>
 * </ul>
 */
@Slf4j
@Component
public class WeChatMpChannel implements NotifyChannel {

    private static final String SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/template/send?access_token={token}";

    /** token 无效/过期 */
    private static final int ERR_TOKEN_INVALID = 40001;
    private static final int ERR_TOKEN_EXPIRED = 42001;
    /** 调用超过当日上限 */
    private static final int ERR_RATE_LIMIT = 45009;

    private final WeChatTokenManager tokenManager;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ChannelAccountService accountService;

    public WeChatMpChannel(WeChatTokenManager tokenManager,
                           @Qualifier("weChatRestClient") RestClient restClient,
                           ObjectMapper objectMapper,
                           ChannelAccountService accountService) {
        this.tokenManager = tokenManager;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.accountService = accountService;
    }

    @Override
    public ChannelType type() {
        return ChannelType.WECHAT_MP;
    }

    @Override
    public void send(Message message) throws Exception {
        ChannelCredentials cred = accountService.resolve(type(), message.getAccountId());
        if (!cred.isMpConfigured()) {
            log.info("[WECHAT_MP][mock] 账号未配置凭据，跳过真实调用：{}", message.getContent());
            return;
        }
        doSend(message, cred, true);
    }

    /**
     * @param allowTokenRetry 是否允许在 token 失效时重试一次（防止无限递归）
     */
    private void doSend(Message message, ChannelCredentials cred, boolean allowTokenRetry) {
        String token = tokenManager.getAccessToken(cred.appId(), cred.appSecret());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("first", Map.of("value", message.getTitle() == null ? "通知" : message.getTitle()));
        data.put("content", Map.of("value", message.getContent()));
        data.put("remark", Map.of("value", "来自 NotifyHub"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", message.getReceiver());
        body.put("template_id", cred.templateId());
        body.put("data", data);

        WeChatApiResult result;
        try {
            /*
             * body 必须先序列化成定长 byte[]，不能直接 .body(Map)。
             * 传对象时 RestClient 会用 Jackson 流式写入，底层 JdkClientHttpRequest
             * 拿到的是长度未知的 InputStream，于是发 Transfer-Encoding: chunked。
             * 微信网关不接受 chunked，直接 412 Precondition Failed 且响应体为空，
             * 连 errcode 都看不到。定长 byte[] 会带上 Content-Length，实测返回 200。
             */
            byte[] payload = toJson(body);
            result = restClient.post()
                    .uri(SEND_URL, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(payload.length)
                    .body(payload)
                    .retrieve()
                    .body(WeChatApiResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("请求公众号模板消息接口失败：" + e.getMessage(), e);
        }

        if (result != null && result.isSuccess()) {
            log.info("[WECHAT_MP] 已发送：appId={}, touser={}, msgid={}",
                    cred.appId(), message.getReceiver(), result.getMsgid());
            return;
        }

        int errcode = result == null || result.getErrcode() == null ? -1 : result.getErrcode();

        // token 类错误：只清这个 appId 的缓存，别把其他公众号的 token 一起清掉
        if (allowTokenRetry && (errcode == ERR_TOKEN_INVALID || errcode == ERR_TOKEN_EXPIRED)) {
            log.warn("[WECHAT_MP] access_token 失效（{}），appId={}，清缓存后重试一次",
                    errcode, cred.appId());
            tokenManager.invalidate(cred.appId());
            doSend(message, cred, false);
            return;
        }

        // 频率超限：立即重试只会更糟，抛出去让外层退避
        if (errcode == ERR_RATE_LIMIT) {
            throw new IllegalStateException("公众号调用频率超限（" + ERR_RATE_LIMIT + "），需退避后重试");
        }

        throw new IllegalStateException("公众号发送失败："
                + (result == null ? "空响应" : result.describe()));
    }

    private byte[] toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化公众号消息体失败", e);
        }
    }
}
