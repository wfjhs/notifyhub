package com.notifyhub.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 死信：重试耗尽的消息。
 * <p>
 * 单独存表而不是在消息表里标 DEAD，是为了让「需要人工介入」的东西
 * 能被单独告警、导出、以及在修好通道后批量重放。
 */
@Data
@TableName("message_dead")
public class MessageDead {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long messageId;

    private String bizId;

    private String channel;

    private String receiver;

    private String content;

    private Integer retryCount;

    private String failReason;

    private LocalDateTime createTime;
}
