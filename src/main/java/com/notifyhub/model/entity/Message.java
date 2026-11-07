package com.notifyhub.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知消息实体。
 * <p>
 * 这张表同时承担「消息内容」和「投递任务」两个角色——
 * 这是刻意合并的：拆成两张表会引入「消息体与任务状态不同步」的问题，
 * 而通知场景的消息体很小（一条短信几十字节），合在一起反而更简单可靠。
 */
@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务唯一键，与 channel 组成幂等键 */
    private String bizId;

    /** 业务类型，便于按维度统计 */
    private String bizType;

    /** 通道：SMS / INBOX / EMAIL */
    private String channel;

    /** 接收方：手机号 / 用户ID / 邮箱 */
    private String receiver;

    /**
     * 投递使用的通道账号。
     * <p>
     * 必须落库而不是只在内存里传：补偿任务可能在进程重启后重试这条消息，
     * 那时已经没有「原始请求」上下文了，只能靠库里的 accountId 找回凭据。
     * 为空表示走全局配置。
     */
    private Long accountId;

    private String title;

    /** 已渲染的最终内容 */
    private String content;

    /** 见 MessageStatus */
    private String status;

    private Integer retryCount;

    private Integer maxRetry;

    /** 下次可投递时间，指数退避的落点 */
    private LocalDateTime nextRetryTime;

    private String failReason;

    private LocalDateTime sentAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
