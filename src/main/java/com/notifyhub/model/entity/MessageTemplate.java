package com.notifyhub.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息模板。内容支持 {@code ${var}} 占位符。
 */
@Data
@TableName("message_template")
public class MessageTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板编码，如 ORDER_PAID */
    private String code;

    private String channel;

    private String title;

    /** 含占位符的模板内容 */
    private String content;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
