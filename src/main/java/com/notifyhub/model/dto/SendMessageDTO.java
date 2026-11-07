package com.notifyhub.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 发送消息请求。
 * <p>
 * 两种寻址方式，二选一：
 * <ul>
 *     <li>{@code recipientId}：从发送对象（地址簿）里选，通道与地址由服务端解析；</li>
 *     <li>{@code channel + receiver}：直接指定，便于脚本、curl 和已有调用方。</li>
 * </ul>
 * 内容也有两种给法：给 {@code templateCode + vars} 由服务端渲染，
 * 或直接给 {@code content}。二选一，都为空时报参数错误。
 */
@Data
public class SendMessageDTO {

    /**
     * 业务唯一键（如订单号）。
     * <p>
     * 与 channel 共同构成幂等键：同一个订单的同一通道只会发出一次。
     * 调用方必须提供，否则重试发送会产生重复消息。
     */
    @NotBlank(message = "bizId 不能为空")
    private String bizId;

    @NotBlank(message = "bizType 不能为空")
    private String bizType;

    /**
     * 发送对象 ID。传了就优先用它解析通道、地址与账号，
     * 此时 {@code channel} / {@code receiver} / {@code accountId} 会被忽略。
     */
    private Long recipientId;

    /**
     * 通道账号 ID（直传方式用；选了发送对象时以对象绑定的账号为准）。
     * 为空表示回落 application.yml 的全局配置。
     */
    private Long accountId;

    /** 通道：SMS / INBOX / WECHAT_WORK / WECHAT_MP。与 recipientId 二选一 */
    private String channel;

    /** 接收方地址。与 recipientId 二选一 */
    private String receiver;

    /** 模板编码，与 vars 搭配使用 */
    private String templateCode;

    /** 模板变量 */
    private Map<String, String> vars;

    /** 直接指定内容（与 templateCode 二选一） */
    private String content;

    private String title;
}
