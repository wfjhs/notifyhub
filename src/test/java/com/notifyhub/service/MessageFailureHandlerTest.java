package com.notifyhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.notifyhub.mapper.MessageDeadMapper;
import com.notifyhub.mapper.MessageMapper;
import com.notifyhub.model.entity.Message;
import com.notifyhub.model.entity.MessageDead;
import com.notifyhub.model.enums.MessageStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 失败处理单测：退避计算与死信转换。
 * <p>
 * 这段逻辑被单独拆成 Bean 的直接原因就是「自调用让 @Transactional 失效」，
 * 所以测试重点放在<b>分支边界</b>：第几次失败该退避、第几次该进死信。
 * 差一位就会导致「少重试一次」或「多发一次」，线上只能靠翻日志才发现。
 */
@ExtendWith(MockitoExtension.class)
class MessageFailureHandlerTest {

    @Mock
    private MessageMapper messageMapper;
    @Mock
    private MessageDeadMapper deadMapper;

    private MessageFailureHandler handler;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Message.class);
    }

    @BeforeEach
    void setUp() {
        handler = new MessageFailureHandler(messageMapper, deadMapper);
        ReflectionTestUtils.setField(handler, "defaultMaxRetry", 3);
        ReflectionTestUtils.setField(handler, "backoffBaseSeconds", 10L);
        ReflectionTestUtils.setField(handler, "backoffMaxSeconds", 300L);
    }

    // ==================== 退避计算 ====================

    @Test
    @DisplayName("退避时间按 base * 2^(retry-1) 递增")
    void backoffDoublesPerRetry() {
        assertThat(handler.backoffSeconds(1)).isEqualTo(10);
        assertThat(handler.backoffSeconds(2)).isEqualTo(20);
        assertThat(handler.backoffSeconds(3)).isEqualTo(40);
        assertThat(handler.backoffSeconds(4)).isEqualTo(80);
    }

    @Test
    @DisplayName("退避时间超过上限后不再增长")
    void backoffIsCapped() {
        // 10 * 2^5 = 320 > 300
        assertThat(handler.backoffSeconds(6)).isEqualTo(300);
        assertThat(handler.backoffSeconds(30)).isEqualTo(300);
    }

    @Test
    @DisplayName("超大重试次数不会因移位溢出变成负数")
    void backoffDoesNotOverflow() {
        // 1L << 1000 在 Java 里会被截断成 1L << (1000 & 63)，不设 Math.min 就会算错
        assertThat(handler.backoffSeconds(1000)).isEqualTo(300);
        assertThat(handler.backoffSeconds(Integer.MAX_VALUE)).isEqualTo(300);
    }

    // ==================== 分支边界 ====================

    @Test
    @DisplayName("未达上限：置 FAILED 并写退避时间，不落死信")
    void belowLimitGoesToFailed() {
        Message m = message(1L, MessageStatus.PENDING);
        m.setRetryCount(0);
        m.setMaxRetry(3);

        handler.handle(m, "gateway timeout");

        // nextRetry = 1 < maxRetry = 3
        verify(deadMapper, never()).insert(any(MessageDead.class));
        verify(messageMapper).update(ArgumentMatchers.<Message>isNull(),
                ArgumentMatchers.<LambdaUpdateWrapper<Message>>any());
    }

    @Test
    @DisplayName("达到上限：置 DEAD 并落死信，重试次数取消耗后的值")
    void exhaustedGoesToDeadLetter() {
        Message m = message(1L, MessageStatus.FAILED);
        m.setRetryCount(2);
        m.setMaxRetry(3);

        handler.handle(m, "still down");

        ArgumentCaptor<MessageDead> captor = ArgumentCaptor.forClass(MessageDead.class);
        verify(deadMapper).insert(captor.capture());
        MessageDead dead = captor.getValue();
        assertThat(dead.getMessageId()).isEqualTo(1L);
        assertThat(dead.getRetryCount()).isEqualTo(3);
        assertThat(dead.getBizId()).isEqualTo("BIZ-X");
        assertThat(dead.getFailReason()).isEqualTo("still down");
    }

    @Test
    @DisplayName("消息未指定 maxRetry 时用全局默认值兜底")
    void nullMaxRetryFallsBackToDefault() {
        Message m = message(1L, MessageStatus.FAILED);
        m.setRetryCount(2);
        m.setMaxRetry(null);   // 全局默认 3

        handler.handle(m, "boom");

        verify(deadMapper).insert(any(MessageDead.class));
    }

    @Test
    @DisplayName("失败原因超过 500 字符时截断，避免撑爆 fail_reason 列")
    void failReasonIsTruncated() {
        Message m = message(1L, MessageStatus.FAILED);
        m.setRetryCount(2);
        m.setMaxRetry(3);

        handler.handle(m, "x".repeat(800));

        ArgumentCaptor<MessageDead> captor = ArgumentCaptor.forClass(MessageDead.class);
        verify(deadMapper).insert(captor.capture());
        assertThat(captor.getValue().getFailReason()).hasSize(500);
    }

    // ==================== 辅助 ====================

    private Message message(Long id, MessageStatus status) {
        Message m = new Message();
        m.setId(id);
        m.setBizId("BIZ-X");
        m.setChannel("SMS");
        m.setReceiver("13800000000");
        m.setContent("测试内容");
        m.setStatus(status.name());
        m.setRetryCount(0);
        m.setMaxRetry(3);
        return m;
    }
}
