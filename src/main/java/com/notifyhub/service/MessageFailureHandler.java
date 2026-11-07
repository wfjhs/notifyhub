package com.notifyhub.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.notifyhub.mapper.MessageDeadMapper;
import com.notifyhub.mapper.MessageMapper;
import com.notifyhub.model.entity.Message;
import com.notifyhub.model.entity.MessageDead;
import com.notifyhub.model.enums.MessageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 失败处理：算退避时间，或转死信。
 * <p>
 * <b>为什么单独拆成一个 Bean</b>：这段逻辑原本是 {@code MessageService.markFailed}，
 * 被同类内的 {@code tryDispatch} 直接调用。Java 的 {@code this.xxx()} 不经过 Spring 代理，
 * {@code @Transactional} 完全失效——「把消息置 DEAD」与「写死信表」变成两条独立提交，
 * 中间失败就会出现「消息进了死信列表但状态没改」或反之的悬空数据。
 * <p>
 * 拆出来之后调用跨 Bean，走的是代理对象，事务才真正生效。
 * 这是自调用导致事务失效的典型案例，单测无法通过「方法上有没有注解」发现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageFailureHandler {

    private final MessageMapper messageMapper;
    private final MessageDeadMapper deadMapper;

    @Value("${notify.retry.max-retry:3}")
    private int defaultMaxRetry;

    @Value("${notify.retry.backoff-base-seconds:10}")
    private long backoffBaseSeconds;

    @Value("${notify.retry.backoff-max-seconds:300}")
    private long backoffMaxSeconds;

    /**
     * 处理一次投递失败。
     *
     * @param message 已失败的消息
     * @param reason  失败原因（会截断后落库）
     */
    @Transactional(rollbackFor = Exception.class)
    public void handle(Message message, String reason) {
        int nextRetry = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
        int maxRetry = message.getMaxRetry() == null ? defaultMaxRetry : message.getMaxRetry();

        if (nextRetry >= maxRetry) {
            // 重试耗尽：置终态 + 落死信
            messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getId, message.getId())
                    .set(Message::getStatus, MessageStatus.DEAD.name())
                    .set(Message::getRetryCount, nextRetry)
                    .set(Message::getFailReason, truncate(reason))
                    .set(Message::getNextRetryTime, null)
                    .set(Message::getUpdateTime, LocalDateTime.now()));

            MessageDead dead = new MessageDead();
            dead.setMessageId(message.getId());
            dead.setBizId(message.getBizId());
            dead.setChannel(message.getChannel());
            dead.setReceiver(message.getReceiver());
            dead.setContent(message.getContent());
            dead.setRetryCount(nextRetry);
            dead.setFailReason(truncate(reason));
            dead.setCreateTime(LocalDateTime.now());
            deadMapper.insert(dead);

            log.error("重试耗尽转死信：messageId={}, retry={}, reason={}",
                    message.getId(), nextRetry, reason);
            return;
        }

        // 还能重试：算指数退避时间。
        // 时间必须由数据库算，不能用 Java 的 LocalDateTime.now()：
        // selectDue() 用 MySQL 的 NOW() 判断是否到期，两个时钟源并不一致
        // （实测 sent_at 比 create_time 还早 1 秒），混用会导致消息提前或延后被投递。
        long delaySeconds = backoffSeconds(nextRetry);
        messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                .eq(Message::getId, message.getId())
                .set(Message::getStatus, MessageStatus.FAILED.name())
                .set(Message::getRetryCount, nextRetry)
                .set(Message::getFailReason, truncate(reason))
                .setSql("next_retry_time = DATE_ADD(NOW(), INTERVAL " + delaySeconds + " SECOND)")
                .setSql("update_time = NOW()"));

        log.info("等待重试：messageId={}, 第 {} 次失败, {} 秒后再投",
                message.getId(), nextRetry, delaySeconds);
    }

    /**
     * 指数退避：base * 2^(retry-1)，上限 backoffMaxSeconds。
     * <p>
     * 第 1 次失败等 10s、第 2 次 20s、第 3 次 40s……避免通道故障瞬间被重试打满。
     */
    long backoffSeconds(int retryCount) {
        long delay = backoffBaseSeconds * (1L << Math.min(retryCount - 1, 20));
        return Math.min(delay, backoffMaxSeconds);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
