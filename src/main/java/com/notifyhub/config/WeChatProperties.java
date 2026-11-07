package com.notifyhub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信相关配置。
 * <p>
 * 没有配置凭据时不报错，而是让通道进入 mock 模式——
 * 这样本地开发和 CI 不需要真实资质也能跑通整条链路，
 * 配好凭据后同一份代码直接生效。
 */
@Data
@Component
@ConfigurationProperties(prefix = "notify.wechat")
public class WeChatProperties {

    private Mp mp = new Mp();
    private Work work = new Work();

    @Data
    public static class Mp {
        /** 公众号 AppID */
        private String appId;
        /** 公众号 AppSecret */
        private String appSecret;
        /** 模板消息的模板 ID */
        private String templateId;

        public boolean isConfigured() {
            return notBlank(appId) && notBlank(appSecret) && notBlank(templateId);
        }
    }

    @Data
    public static class Work {
        /** 企业微信群机器人的 Webhook 地址（含 key） */
        private String webhookUrl;

        public boolean isConfigured() {
            return notBlank(webhookUrl);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
