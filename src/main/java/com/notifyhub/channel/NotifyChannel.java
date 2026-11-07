package com.notifyhub.channel;

import com.notifyhub.model.entity.Message;
import com.notifyhub.model.enums.ChannelType;

/**
 * 投递通道。
 * <p>
 * <b>为什么要这一层</b>：不同通道的差异极大——短信走第三方网关（可能超时）、
 * 站内信写库即时完成、邮件走 SMTP（可能进垃圾箱）。如果把这些差异写进
 * 投递主流程，每加一个通道就要改一次核心代码。
 * <p>
 * 抽成接口之后，主流程只认「发送成功 / 抛异常」两种结果，
 * 重试、退避、死信由外层统一处理。新增通道 = 加一个实现类，
 * 主流程一行不动。
 * <p>
 * <b>约定</b>：实现类发送失败必须<b>抛异常</b>而不是返回 false——
 * 异常能带上下文（超时？限流？号码非法？），便于记录 fail_reason。
 */
public interface NotifyChannel {

    /**
     * 该实现负责的通道类型，用于注册表分发。
     */
    ChannelType type();

    /**
     * 执行投递。
     *
     * @param message 待投递消息（已渲染好内容）
     * @throws Exception 发送失败，由调用方决定是否重试
     */
    void send(Message message) throws Exception;
}
