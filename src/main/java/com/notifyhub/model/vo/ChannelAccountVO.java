package com.notifyhub.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通道账号的对外视图。
 * <p>
 * <b>刻意不返回 appSecret</b>：管理台没有鉴权，把密钥下发到浏览器等于泄露。
 * webhookUrl 里的 key 等同于密码，同样只给掩码。
 * 两个 xxxConfigured 标志让前端能区分「没配」和「配了但不给你看」。
 * 编辑时这两项留空即表示保持原值（见 SaveChannelAccountDTO）。
 */
@Data
public class ChannelAccountVO {

    private Long id;
    private String name;
    private String channel;
    private String appId;
    private String templateId;

    /** 已脱敏，仅用于展示 */
    private String webhookMasked;

    private Boolean secretConfigured;
    private Boolean webhookConfigured;
    private Boolean enabled;
    private String remark;
    private LocalDateTime createTime;
}
