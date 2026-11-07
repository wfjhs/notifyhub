package com.notifyhub.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 发送结果。
 * <p>
 * {@code duplicated} 是幂等命中的标志：调用方重试提交时不会产生第二条消息，
 * 但需要知道「这条我其实没重新发」。返回它比静默成功更诚实——
 * 否则调用方无法区分「首次发送成功」和「重复提交被忽略」。
 */
@Data
@AllArgsConstructor
public class SendResultVO {

    private Long messageId;

    private String status;

    /** true 表示该 (bizId, channel) 已存在，本次未产生新消息 */
    private boolean duplicated;
}
