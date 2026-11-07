package com.notifyhub.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.notifyhub.channel.NotifyChannel;
import com.notifyhub.config.RateLimitException;
import com.notifyhub.mapper.MessageDeadMapper;
import com.notifyhub.mapper.MessageMapper;
import com.notifyhub.mapper.MessageTemplateMapper;
import com.notifyhub.model.dto.SendMessageDTO;
import com.notifyhub.model.entity.Message;
import com.notifyhub.model.entity.MessageDead;
import com.notifyhub.model.entity.MessageTemplate;
import com.notifyhub.model.enums.ChannelType;
import com.notifyhub.model.enums.MessageStatus;
import com.notifyhub.model.vo.SendResultVO;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MessageService 核心逻辑单测。
 * <p>
 * 这些都是「逻辑写错但能跑通」的地方——退避算错只会让重试变密或变疏，
 * 幂等写错只在并发下偶发重复，没有任何编译期与人工测试能稳定发现。
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageMapper messageMapper;
    @Mock
    private MessageTemplateMapper templateMapper;
    @Mock
    private MessageDeadMapper deadMapper;
    @Mock
    private NotifyChannel channel;
    @Mock
    private NotifyRateLimiter rateLimiter;
    @Mock
    private MessageFailureHandler failureHandler;
    @Mock
    private RecipientService recipientService;

    private MessageService service;

    /**
     * LambdaUpdateWrapper 解析 {@code Message::getStatus} 这类方法引用时，
     * 需要实体的 TableInfo 已注册（正常由 MyBatis 启动时完成）。
     * 纯 Mockito 单测没有 Spring 上下文，必须手动建一次，
     * 否则会抛 "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Message.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MessageDead.class);
    }

    @BeforeEach
    void setUp() {
        service = new MessageService(messageMapper, templateMapper, deadMapper,
                List.of(channel), rateLimiter, failureHandler, recipientService);
        // @Value 字段不在构造器里，单测中直接反射注入
        ReflectionTestUtils.setField(service, "defaultMaxRetry", 3);
        // 补偿投递的线程池大小，@Value 未注入时会是 0（newFixedThreadPool(0) 会抛异常）
        ReflectionTestUtils.setField(service, "dispatchConcurrency", 4);
        // channels() 注册表会遍历 channelList 调 type()，不是每个用例都用到
        lenient().when(channel.type()).thenReturn(ChannelType.SMS);
    }

    // ==================== 幂等 ====================

    @Test
    @DisplayName("幂等快查命中：返回已有消息，且不消耗限流配额、不重复落库")
    void idempotentHitSkipsRateLimitAndInsert() {
        Message exist = message(99L, MessageStatus.SUCCESS);
        when(messageMapper.selectOne(any())).thenReturn(exist);

        SendResultVO result = service.submit(dto("BIZ-1"));

        assertThat(result.isDuplicated()).isTrue();
        assertThat(result.getMessageId()).isEqualTo(99L);
        assertThat(result.getStatus()).isEqualTo(MessageStatus.SUCCESS.name());
        // 顺序设计的核心：重复提交发生在限流之前，调用方重试不会把自己的额度耗光
        verify(rateLimiter, never()).check(anyString(), any());
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("唯一键冲突（并发下快查都未命中）：捕获后返回已有消息而非报错")
    void duplicateKeyFallsBackToExisting() {
        Message exist = message(88L, MessageStatus.PENDING);
        // 第一次快查未命中，插入冲突后再查命中
        when(messageMapper.selectOne(any())).thenReturn(null, exist);
        when(messageMapper.insert(any(Message.class))).thenThrow(new DuplicateKeyException("uk_biz_channel"));

        SendResultVO result = service.submit(dto("BIZ-2"));

        assertThat(result.isDuplicated()).isTrue();
        assertThat(result.getMessageId()).isEqualTo(88L);
    }

    @Test
    @DisplayName("限流拒绝发生在落库之前：被拒时库里不留脏数据")
    void rateLimitRejectsBeforeInsert() {
        doThrow(new RateLimitException("too many")).when(rateLimiter).check(anyString(), any());

        assertThatThrownBy(() -> service.submit(dto("BIZ-3")))
                .isInstanceOf(RateLimitException.class);

        verify(messageMapper, never()).insert(any(Message.class));
    }

    // ==================== 抢占 ====================

    @Test
    @DisplayName("抢占失败（影响行数 0）：直接跳过，不调用通道，避免重复投递")
    void claimFailureSkipsSending() throws Exception {
        when(messageMapper.claim(1L)).thenReturn(0);

        service.tryDispatch(message(1L, MessageStatus.PENDING));

        verify(channel, never()).send(any());
        verify(messageMapper, never()).markSuccess(anyLong());
    }

    @Test
    @DisplayName("抢占成功且发送成功：标记 SUCCESS")
    void sendSuccessMarksSuccess() throws Exception {
        Message m = message(1L, MessageStatus.PENDING);
        when(messageMapper.claim(1L)).thenReturn(1);

        service.tryDispatch(m);

        verify(channel).send(m);
        verify(messageMapper).markSuccess(1L);
        verify(messageMapper, never()).update(ArgumentMatchers.<Message>isNull(),
                ArgumentMatchers.<LambdaUpdateWrapper<Message>>any());
    }

    // ==================== 失败委托 ====================

    @Test
    @DisplayName("发送失败：把失败原因交给失败处理器，自己不改状态")
    void sendFailureDelegatedToHandler() throws Exception {
        Message m = message(1L, MessageStatus.PENDING);
        when(messageMapper.claim(1L)).thenReturn(1);
        doThrow(new RuntimeException("gateway timeout")).when(channel).send(any());

        service.tryDispatch(m);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(failureHandler).handle(eq(m), reason.capture());
        assertThat(reason.getValue()).contains("gateway timeout");
        verify(messageMapper, never()).markSuccess(anyLong());
    }

    @Test
    @DisplayName("通道未注册：也算失败，交由失败处理器退避或进死信")
    void unknownChannelDelegatedToHandler() {
        Message m = message(1L, MessageStatus.PENDING);
        m.setChannel("EMAIL");
        when(messageMapper.claim(1L)).thenReturn(1);

        service.tryDispatch(m);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(failureHandler).handle(eq(m), reason.capture());
        assertThat(reason.getValue()).contains("通道未实现");
        verify(messageMapper, never()).markSuccess(anyLong());
    }

    // ==================== 补偿扫描 ====================

    @Test
    @DisplayName("补偿扫描：单条抛异常不中断整批")
    void scanContinuesWhenOneItemFails() {
        Message m1 = message(1L, MessageStatus.PENDING);
        Message m2 = message(2L, MessageStatus.PENDING);
        when(messageMapper.selectDue(20)).thenReturn(List.of(m1, m2));
        when(messageMapper.claim(1L)).thenThrow(new RuntimeException("db blip"));
        when(messageMapper.claim(2L)).thenReturn(1);

        assertThat(service.scanAndDispatch(20)).isEqualTo(2);

        verify(messageMapper).claim(2L);
        verify(messageMapper).markSuccess(2L);
    }

    @Test
    @DisplayName("补偿扫描：没有到期消息时不做事")
    void scanDoesNothingWhenNoDueMessage() {
        when(messageMapper.selectDue(20)).thenReturn(List.of());

        assertThat(service.scanAndDispatch(20)).isZero();

        verify(messageMapper, never()).claim(anyLong());
    }

    // ==================== 内容解析 ====================

    @Test
    @DisplayName("模板不存在：报参数错误，不落库")
    void missingTemplateRejected() {
        SendMessageDTO d = dto("BIZ-4");
        d.setTemplateCode("NOT_EXIST");
        when(templateMapper.findByCodeAndChannel("NOT_EXIST", "SMS")).thenReturn(null);

        assertThatThrownBy(() -> service.submit(d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模板不存在");

        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("模板命中：渲染变量后落库，文案里的占位符被替换")
    void templateRenderedBeforeInsert() {
        SendMessageDTO d = dto("BIZ-5");
        d.setTemplateCode("ORDER_PAID");
        d.setVars(Map.of("orderNo", "A1001"));
        MessageTemplate tpl = new MessageTemplate();
        tpl.setTitle("订单通知");
        tpl.setContent("订单 ${orderNo} 支付成功");
        when(templateMapper.findByCodeAndChannel("ORDER_PAID", "SMS")).thenReturn(tpl);
        when(messageMapper.insert(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(7L);
            return 1;
        });
        when(messageMapper.claim(7L)).thenReturn(1);

        service.submit(d);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("订单 A1001 支付成功");
        assertThat(captor.getValue().getStatus()).isEqualTo(MessageStatus.PENDING.name());
        assertThat(captor.getValue().getMaxRetry()).isEqualTo(3);
    }

    @Test
    @DisplayName("模板与正文都没提供：报参数错误")
    void missingContentRejected() {
        SendMessageDTO d = dto("BIZ-6");
        d.setContent(null);

        assertThatThrownBy(() -> service.submit(d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少提供一个");
    }

    @Test
    @DisplayName("不支持的通道名：报参数错误而不是 500")
    void unknownChannelNameRejected() {
        SendMessageDTO d = dto("BIZ-7");
        d.setChannel("PIGEON");

        assertThatThrownBy(() -> service.submit(d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的通道");
    }

    // ==================== 死信重放 ====================

    @Test
    @DisplayName("重放死信：消息重置为 PENDING 且重试次数清零")
    void replayResetsRetryCount() {
        MessageDead dead = new MessageDead();
        dead.setId(5L);
        dead.setMessageId(1L);
        when(deadMapper.selectById(5L)).thenReturn(dead);
        when(messageMapper.selectById(1L)).thenReturn(message(1L, MessageStatus.DEAD));

        service.replayDead(5L);

        // 改状态与删死信必须成对执行
        verify(messageMapper).update(ArgumentMatchers.<Message>isNull(),
                ArgumentMatchers.<LambdaUpdateWrapper<Message>>any());
        verify(deadMapper).deleteById(5L);
    }

    @Test
    @DisplayName("重放不存在的死信：报错且不写库")
    void replayMissingDeadRejected() {
        when(deadMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.replayDead(404L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("死信不存在");

        verify(messageMapper, never()).update(any(), any());
        verify(deadMapper, never()).deleteById(anyLong());
    }

    // ==================== 辅助 ====================

    private SendMessageDTO dto(String bizId) {
        SendMessageDTO d = new SendMessageDTO();
        d.setBizId(bizId);
        d.setBizType("ORDER");
        d.setChannel("SMS");
        d.setReceiver("13800000000");
        d.setContent("测试内容");
        return d;
    }

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
