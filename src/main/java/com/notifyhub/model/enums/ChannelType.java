package com.notifyhub.model.enums;

/**
 * 投递通道。
 * <p>
 * 新增通道只需：加一个枚举值 + 实现一个 {@code NotifyChannel}，
 * 核心流程（落库、抢占、重试、死信）完全不用改。
 */
public enum ChannelType {

    /** 短信（演示用 Mock 实现，不真的发短信） */
    SMS,

    /** 站内信，直接落库 */
    INBOX,

    /**
     * 微信公众号模板消息。
     * <p>
     * 与短信最大的不同：发消息前必须先换 access_token，
     * 而这个 token 是全局共享、有过期时间、有日调用上限的。
     * 见 {@code WeChatTokenManager}。
     */
    WECHAT_MP,

    /**
     * 企业微信群机器人。
     * <p>
     * 最简单的一种微信推送：只要一个 Webhook URL，POST JSON 就能发到群里，
     * 不需要 access_token，很适合做告警通知。
     */
    WECHAT_WORK,

    /** 邮件（预留，未实现） */
    EMAIL
}
