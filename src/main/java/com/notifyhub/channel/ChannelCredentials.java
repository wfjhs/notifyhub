package com.notifyhub.channel;

/**
 * 一次投递要用的通道凭据。
 * <p>
 * 由 {@code ChannelAccountService.resolve(...)} 根据 message.accountId 解析：
 * 绑定了账号就用账号里的，没绑定就回落到 application.yml 的全局配置。
 * 通道实现只依赖这个值对象，不关心凭据来自数据库还是配置文件。
 */
public record ChannelCredentials(
        String appId,
        String appSecret,
        String templateId,
        String webhookUrl) {

    public boolean isMpConfigured() {
        return notBlank(appId) && notBlank(appSecret) && notBlank(templateId);
    }

    public boolean isWorkConfigured() {
        return notBlank(webhookUrl);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
