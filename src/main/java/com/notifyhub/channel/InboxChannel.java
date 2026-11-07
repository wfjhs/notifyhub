package com.notifyhub.channel;

import com.notifyhub.model.entity.Message;
import com.notifyhub.model.enums.ChannelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 站内信通道。
 * <p>
 * 与其他通道最大的不同：<b>消息本身已经在 {@code message} 表里了</b>，
 * 所以「投递」这个动作几乎不需要额外操作——把它置为 SUCCESS，
 * 用户查询「我的消息」时就能看到，这就是送达。
 * <p>
 * 也正因为不依赖外部系统，站内信是最可靠的通道，
 * 短信/邮件失败时可以用它兜底（本项目的降级策略留作扩展点，未实现）。
 */
@Slf4j
@Component
public class InboxChannel implements NotifyChannel {

    @Override
    public ChannelType type() {
        return ChannelType.INBOX;
    }

    @Override
    public void send(Message message) {
        // 无需外部调用：状态置为 SUCCESS 后即可被查询到
        log.info("[INBOX] 已投递至用户 {}：{}", message.getReceiver(), message.getTitle());
    }
}
