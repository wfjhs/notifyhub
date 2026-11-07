package com.notifyhub.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发送对象（接收方地址簿）。
 * <p>
 * 一条记录 = 「某个人/某个群 + 某个通道 + 该通道下的地址」。
 * 同一个人要收短信和站内信，就是两条记录（通道不同、地址也不同）。
 */
@Data
@TableName("recipient")
public class Recipient {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 通道类型，取值见 {@link com.notifyhub.model.enums.ChannelType} */
    private String channel;

    /** 地址：站内信是用户 ID，短信是手机号，企业微信是群标识，公众号是 openid */
    private String address;

    /** 绑定的通道账号；为空表示回落全局配置（兼容老数据） */
    private Long accountId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
