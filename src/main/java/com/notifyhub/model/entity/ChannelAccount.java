package com.notifyhub.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通道账号：一组可独立管理的通道凭据。
 * <p>
 * 把 appId / appSecret / webhook 从 application.yml 里搬进数据库，
 * 应用才能同时接多个公众号、多个群机器人，而不用每加一个就改配置重启。
 */
@Data
@TableName("channel_account")
public class ChannelAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 账号名称，管理台里给人看的标签 */
    private String name;

    /** 通道类型：WECHAT_MP / WECHAT_WORK，见 ChannelType */
    private String channel;

    private String appId;

    /** 敏感字段：列表接口必须脱敏，见 ChannelAccountVO */
    private String appSecret;

    private String templateId;

    private String webhookUrl;

    /** 停用后绑定它的消息会明确失败，不静默回落到全局账号 */
    private Integer enabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
