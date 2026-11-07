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

import java.util.Map;

/**
 * 企业微信群机器人通道。
 * <p>
 * 这是所有微信形态里最简单的一种：一个 Webhook URL，POST 一段 JSON 就发到群里，
 * 不需要 access_token、不需要用户 openid、不需要认证。典型用途是运维告警。
 * <p>
 * <b>能发到哪些群</b>：企业微信的群（含拉入了外部联系人的外部群）。
 * <b>个人微信群发不了</b>——微信没有开放任何「往个人群发消息」的接口，
 * 网上说的 itchat / wechaty 是逆向登录个人号，违反协议且会封号，不能用在正经系统里。
 * <p>
 * 与公众号通道的差异：
 * <ul>
 *     <li>接收方是「群」而不是「某个用户」，{@code receiver} 里放的是该群的 webhook；</li>
 *     <li>没有 token 管理，但同样要检查 {@code errcode}——HTTP 200 不代表发送成功。</li>
 * </ul>
 */
@Slf4j
@Component
public class WeChatWorkChannel implements NotifyChannel {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ChannelAccountService accountService;

    public WeChatWorkChannel(@Qualifier("weChatRestClient") RestClient restClient,
                             ObjectMapper objectMapper,
                             ChannelAccountService accountService) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.accountService = accountService;
    }

    @Override
    public ChannelType type() {
        return ChannelType.WECHAT_WORK;
    }

    @Override
    public void send(Message message) throws Exception {
        String webhook = resolveWebhook(message);
        if (webhook == null) {
            // 没配 webhook 时走 mock：本地开发与 CI 不需要真实机器人也能跑通链路
            log.info("[WECHAT_WORK][mock] 未配置 webhook，跳过真实调用：{}", message.getContent());
            return;
        }

        Map<String, Object> body = Map.of(
                "msgtype", "text",
                "text", Map.of("content", message.getContent())
        );

        WeChatApiResult result;
        try {
            // 同 WeChatMpChannel：必须定长 byte[]，否则 chunked 会被微信网关 412 拦掉
            byte[] payload = toJson(body);
            result = restClient.post()
                    .uri(webhook)
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(payload.length)
                    .body(payload)
                    .retrieve()
                    .body(WeChatApiResult.class);
        } catch (Exception e) {
            // 网络层失败抛出去，交给外层退避重试
            throw new IllegalStateException("请求企业微信 webhook 失败：" + e.getMessage(), e);
        }

        // 关键：企业微信即使业务失败也返回 HTTP 200，必须看 errcode
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("企业微信发送失败："
                    + (result == null ? "空响应" : result.describe()));
        }

        log.info("[WECHAT_WORK] 已发送：receiver={}", maskWebhook(message.getReceiver()));
    }

    /**
     * 决定这次往哪个群发，优先级：
     * <ol>
     *     <li>对象地址里显式写了完整 URL —— 尊重它，兼容本地假 webhook 与老数据；</li>
     *     <li>绑定账号里的 webhookUrl（没绑账号时就是全局配置）；</li>
     *     <li>都没有 —— 返回 null，走 mock。</li>
     * </ol>
     */
    private String resolveWebhook(Message message) {
        String receiver = message.getReceiver();
        if (receiver != null && receiver.trim().toLowerCase().startsWith("http")) {
            return receiver.trim();
        }
        ChannelCredentials cred = accountService.resolve(type(), message.getAccountId());
        String configured = cred.webhookUrl();
        return configured == null || configured.isBlank() ? null : configured.trim();
    }

    /** webhook 的 key 等同于密码，日志里不能明文打出来 */
    private String maskWebhook(String raw) {
        if (raw == null || raw.length() <= 12) {
            return raw;
        }
        return raw.substring(0, 12) + "***";
    }

    private byte[] toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化企业微信消息体失败", e);
        }
    }
}
