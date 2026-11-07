package com.notifyhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.notifyhub.channel.NotifyChannel;
import com.notifyhub.mapper.MessageDeadMapper;
import com.notifyhub.mapper.MessageMapper;
import com.notifyhub.mapper.MessageTemplateMapper;
import com.notifyhub.model.dto.SendMessageDTO;
import com.notifyhub.model.entity.Message;
import com.notifyhub.model.entity.MessageDead;
import com.notifyhub.model.entity.MessageTemplate;
import com.notifyhub.model.entity.Recipient;
import com.notifyhub.model.enums.ChannelType;
import com.notifyhub.model.enums.MessageStatus;
import com.notifyhub.model.vo.SendResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 消息投递核心服务。
 * <p>
 * 三个职责：落库（含幂等）、投递（含抢占）、失败处理（含退避与死信）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageMapper messageMapper;
    private final MessageTemplateMapper templateMapper;
    private final MessageDeadMapper deadMapper;
    private final List<NotifyChannel> channelList;
    private final NotifyRateLimiter rateLimiter;
    private final MessageFailureHandler failureHandler;
    private final RecipientService recipientService;

    @Value("${notify.retry.max-retry:3}")
    private int defaultMaxRetry;

    /** 通道注册表：启动时由 Spring 注入所有实现，新增通道无需改这里 */
    private Map<ChannelType, NotifyChannel> channels;

    /**
     * 补偿投递的并发线程池。
     * <p>
     * 懒加载而不是 {@code @PostConstruct}：单测是直接 new 出来的，
     * 不走 Spring 生命周期；懒加载让两种场景都能工作。
     */
    private ExecutorService dispatchPool;

    @Value("${notify.worker.concurrency:8}")
    private int dispatchConcurrency;

    private ExecutorService executor() {
        if (dispatchPool == null) {
            synchronized (this) {
                if (dispatchPool == null) {
                    dispatchPool = Executors.newFixedThreadPool(dispatchConcurrency, r -> {
                        Thread t = new Thread(r, "notify-dispatch");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return dispatchPool;
    }

    @PreDestroy
    void shutdownDispatchPool() {
        if (dispatchPool != null) {
            dispatchPool.shutdown();
        }
    }

    private Map<ChannelType, NotifyChannel> channels() {
        if (channels == null) {
            Map<ChannelType, NotifyChannel> map = new EnumMap<>(ChannelType.class);
            for (NotifyChannel c : channelList) {
                map.put(c.type(), c);
            }
            channels = map;
        }
        return channels;
    }

    // ==================== 提交 ====================

    /**
     * 提交一条消息。
     * <p>
     * 幂等靠数据库唯一键 {@code uk_biz_channel}：重复提交时插入抛
     * {@link DuplicateKeyException}，捕获后返回已有记录，而不是「先查后插」——
     * 后者在并发下有竞态窗口，两个请求可能都查到「不存在」然后都插入。
     *
     * @return 消息 ID 与是否命中幂等
     */
    public SendResultVO submit(SendMessageDTO dto) {
        // 寻址：优先用发送对象（地址簿），否则用显式 channel + receiver。
        // 传 recipientId 时地址由服务端查库解析，前端既拿不到也改不了别人的地址。
        ChannelType channel;
        String receiver;
        Long accountId = dto.getAccountId();
        if (dto.getRecipientId() != null) {
            Recipient recipient = recipientService.require(dto.getRecipientId());
            channel = parseChannel(recipient.getChannel());
            receiver = recipient.getAddress();
            // 对象绑定的账号优先于请求里带的，避免「选了 A 对象却用 B 账号发」
            accountId = recipient.getAccountId();
        } else {
            if (dto.getChannel() == null || dto.getChannel().isBlank()) {
                throw new IllegalArgumentException("recipientId 与 channel 至少提供一个");
            }
            if (dto.getReceiver() == null || dto.getReceiver().isBlank()) {
                throw new IllegalArgumentException("未指定发送对象时 receiver 不能为空");
            }
            channel = parseChannel(dto.getChannel());
            receiver = dto.getReceiver();
        }

        // ① 幂等快查：已存在直接返回，**不消耗限流配额**。
        //    重复提交是异常路径，多一次索引查询换取「调用方重试不吃配额」是划算的。
        //    注意这只是优化，真正的幂等保证仍靠后面的唯一键约束（并发下快查可能都查不到）。
        Message found = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizId, dto.getBizId())
                .eq(Message::getChannel, channel.name())
                .last("LIMIT 1"));
        if (found != null) {
            log.info("幂等命中（快查），返回已有消息：bizId={}, channel={}, messageId={}",
                    dto.getBizId(), channel, found.getId());
            return new SendResultVO(found.getId(), found.getStatus(), true);
        }

        // ② 限流：拒在落库之前，避免无效写入
        rateLimiter.check(receiver, channel);

        String content = resolveContent(dto, channel);

        LocalDateTime now = LocalDateTime.now();
        Message message = new Message();
        message.setBizId(dto.getBizId());
        message.setBizType(dto.getBizType());
        message.setChannel(channel.name());
        message.setReceiver(receiver);
        // 账号一起落库：补偿任务可能在进程重启后重试，那时只能靠库里的值找回凭据
        message.setAccountId(accountId);
        message.setTitle(dto.getTitle());
        message.setContent(content);
        message.setStatus(MessageStatus.PENDING.name());
        message.setRetryCount(0);
        message.setMaxRetry(defaultMaxRetry);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        message.setDeleted(0);

        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            // 幂等命中：同一业务同一通道已经发过了
            Message exist = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                    .eq(Message::getBizId, dto.getBizId())
                    .eq(Message::getChannel, channel.name())
                    .last("LIMIT 1"));
            log.info("幂等命中，返回已有消息：bizId={}, channel={}, messageId={}",
                    dto.getBizId(), channel, exist == null ? null : exist.getId());
            return new SendResultVO(exist == null ? null : exist.getId(),
                    exist == null ? MessageStatus.PENDING.name() : exist.getStatus(), true);
        }

        // 立即尝试投递一次：绝大多数消息能一次成功，用户感知延迟最低。
        // 失败不抛异常——状态已记录在库里，补偿任务会接手。
        tryDispatch(message);

        // 重新读一次拿最新状态
        Message latest = messageMapper.selectById(message.getId());
        return new SendResultVO(message.getId(),
                latest == null ? MessageStatus.PENDING.name() : latest.getStatus(), false);
    }

    // ==================== 投递 ====================

    /**
     * 尝试投递一条消息。
     * <p>
     * <b>不加事务</b>：这里包含远程调用（短信网关），把远程调用包进事务会长时间占用
     * 数据库连接，且远程成功而事务回滚会造成「发了却记录成没发」。
     * 数据库操作都在独立的小事务里完成。
     */
    public void tryDispatch(Message message) {
        // 抢占：只有 PENDING / FAILED 才能改成 SENDING。
        // 影响行数为 0 说明别的 worker 已经在处理，或消息已是终态
        int claimed = messageMapper.claim(message.getId());
        if (claimed == 0) {
            log.debug("抢占失败，跳过：messageId={}", message.getId());
            return;
        }

        NotifyChannel channel = channels().get(ChannelType.valueOf(message.getChannel()));
        if (channel == null) {
            failureHandler.handle(message, "通道未实现：" + message.getChannel());
            return;
        }

        try {
            channel.send(message);
            messageMapper.markSuccess(message.getId());
            log.info("投递成功：messageId={}, channel={}, receiver={}",
                    message.getId(), message.getChannel(), message.getReceiver());
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("投递失败：messageId={}, channel={}, reason={}",
                    message.getId(), message.getChannel(), reason);
            failureHandler.handle(message, reason);
        }
    }


    // ==================== 补偿扫描 ====================

    /**
     * 扫描到期消息并逐条尝试投递。由 {@code DeliveryWorker} 定时调用。
     *
     * @return 本轮处理条数
     */
    public int scanAndDispatch(int batchSize) {
        List<Message> due = messageMapper.selectDue(batchSize);
        if (due.isEmpty()) {
            return 0;
        }
        log.debug("补偿扫描到 {} 条待投递消息", due.size());

        // 批内并发投递。
        // 单条投递绝大部分时间是「等网关返回」（真实短信 100~300ms），串行执行时
        // 吞吐 = batch / (单条耗时 × batch + 间隔)，线程几乎全在阻塞等待。
        // 并发后一批的整体耗时 ≈ 单条耗时，吞吐提升约 concurrency 倍。
        // 安全性由 claim 的条件更新保证：并发抢同一条只有一行影响行数为 1。
        List<CompletableFuture<Void>> futures = due.stream()
                .map(m -> CompletableFuture.runAsync(() -> {
                    try {
                        tryDispatch(m);
                    } catch (Exception e) {
                        // 单条异常不能中断整批
                        log.error("投递异常：messageId={}", m.getId(), e);
                    }
                }, executor()))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return due.size();
    }

    // ==================== 查询 ====================

    public Message detail(Long id) {
        Message m = messageMapper.selectById(id);
        if (m == null) {
            throw new IllegalArgumentException("消息不存在：" + id);
        }
        return m;
    }

    /**
     * 按业务键查该业务下所有通道的消息。
     * <p>
     * 排查「用户说没收到」时的第一入口：一次看清短信是否成功、站内信有没有落库。
     */
    public List<Message> listByBizId(String bizId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getBizId, bizId)
                .orderByAsc(Message::getId));
    }

    // ==================== 死信管理 ====================

    /**
     * 死信列表（按时间倒序）。
     */
    public List<MessageDead> listDead(int limit) {
        int safe = Math.min(Math.max(limit, 1), 200);
        return deadMapper.selectList(new LambdaQueryWrapper<MessageDead>()
                .orderByDesc(MessageDead::getId)
                .last("LIMIT " + safe));
    }

    /**
     * 重放一条死信：把原消息重置为待投递，并删除死信记录。
     * <p>
     * 典型场景：短信网关故障两小时后恢复，期间积压的死信可以批量补发。
     * <p>
     * <b>重试次数清零</b>是刻意的：重放意味着「人为确认这次值得再试」，
     * 应该重新给满配额，而不是继承上次已经耗尽的计数——否则重放一次就立刻又进死信。
     * <p>
     * 放在事务里：改消息状态与删死信必须同时成功，
     * 否则会出现「死信删了但消息没重置」或反之，导致消息悬空。
     */
    @Transactional(rollbackFor = Exception.class)
    public void replayDead(Long deadId) {
        MessageDead dead = deadMapper.selectById(deadId);
        if (dead == null) {
            throw new IllegalArgumentException("死信不存在：" + deadId);
        }
        Message message = messageMapper.selectById(dead.getMessageId());
        if (message == null) {
            throw new IllegalArgumentException("原消息已不存在：messageId=" + dead.getMessageId());
        }

        messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                .eq(Message::getId, message.getId())
                .set(Message::getStatus, MessageStatus.PENDING.name())
                .set(Message::getRetryCount, 0)
                .set(Message::getFailReason, null)
                .set(Message::getNextRetryTime, null)
                .setSql("update_time = NOW()"));

        deadMapper.deleteById(deadId);
        log.info("死信已重放：deadId={}, messageId={}, channel={}",
                deadId, message.getId(), message.getChannel());
    }

    // ==================== 辅助 ====================

    private ChannelType parseChannel(String raw) {
        try {
            return ChannelType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的通道：" + raw);
        }
    }

    private String resolveContent(SendMessageDTO dto, ChannelType channel) {
        if (dto.getTemplateCode() != null && !dto.getTemplateCode().isBlank()) {
            MessageTemplate tpl = templateMapper.findByCodeAndChannel(dto.getTemplateCode(), channel.name());
            if (tpl == null) {
                throw new IllegalArgumentException("模板不存在：" + dto.getTemplateCode() + " / " + channel);
            }
            if (dto.getTitle() == null || dto.getTitle().isBlank()) {
                dto.setTitle(TemplateRenderer.render(tpl.getTitle(), dto.getVars()));
            }
            return TemplateRenderer.render(tpl.getContent(), dto.getVars());
        }
        if (dto.getContent() == null || dto.getContent().isBlank()) {
            throw new IllegalArgumentException("templateCode 与 content 至少提供一个");
        }
        return dto.getContent();
    }
}
