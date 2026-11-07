package com.notifyhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.notifyhub.mapper.RecipientMapper;
import com.notifyhub.model.dto.RecipientFormDTO;
import com.notifyhub.model.entity.ChannelAccount;
import com.notifyhub.model.entity.Recipient;
import com.notifyhub.model.enums.ChannelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发送对象（接收方地址簿）。
 * <p>
 * 存在的意义：把「发给谁」从每次手输变成一个可复用的对象。
 * 手输手机号错一位就是发错人，而且同一个人在不同通道地址不同，
 * 靠脑子记不现实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipientService {

    private final RecipientMapper recipientMapper;
    private final ChannelAccountService accountService;

    /**
     * 新增对象。
     * <p>
     * 重名判定交给 {@code uk_name_channel} 唯一键，而不是先查后插：
     * 后者在并发录入两个同名对象时都有机会通过查询。
     */
    public Recipient create(RecipientFormDTO dto) {
        ChannelType channel = parseChannel(dto.getChannel());
        validateAccount(dto.getAccountId(), channel);

        Recipient recipient = new Recipient();
        recipient.setName(dto.getName().trim());
        recipient.setChannel(channel.name());
        recipient.setAddress(dto.getAddress().trim());
        recipient.setAccountId(dto.getAccountId());
        recipient.setRemark(dto.getRemark());
        LocalDateTime now = LocalDateTime.now();
        recipient.setCreateTime(now);
        recipient.setUpdateTime(now);
        recipient.setDeleted(0);

        try {
            recipientMapper.insert(recipient);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(
                    "该通道下已有同名对象：" + dto.getName() + " / " + channel);
        }
        log.info("新增发送对象：id={}, name={}, channel={}, address={}",
                recipient.getId(), recipient.getName(), channel, recipient.getAddress());
        return recipient;
    }

    /**
     * 修改对象。
     * <p>
     * 用 {@link LambdaUpdateWrapper} 显式 set 每个字段，而不是 {@code updateById}：
     * 后者会跳过 null 字段，导致「把备注清空」这个操作永远不生效。
     * <p>
     * 改名或改通道可能撞上 {@code uk_name_channel}，同样交给唯一键报错，
     * 而不是先查后改。
     */
    public Recipient update(Long id, RecipientFormDTO dto) {
        require(id);
        ChannelType channel = parseChannel(dto.getChannel());
        validateAccount(dto.getAccountId(), channel);

        try {
            recipientMapper.update(null, new LambdaUpdateWrapper<Recipient>()
                    .eq(Recipient::getId, id)
                    .set(Recipient::getName, dto.getName().trim())
                    .set(Recipient::getChannel, channel.name())
                    .set(Recipient::getAddress, dto.getAddress().trim())
                    .set(Recipient::getAccountId, dto.getAccountId())
                    .set(Recipient::getRemark, dto.getRemark())
                    .set(Recipient::getUpdateTime, LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(
                    "该通道下已有同名对象：" + dto.getName() + " / " + channel);
        }

        log.info("修改发送对象：id={}, name={}, channel={}, address={}",
                id, dto.getName(), channel, dto.getAddress());
        return require(id);
    }

    /**
     * 对象列表。
     *
     * @param keyword 可选，按名称或地址模糊匹配（地址簿常见的找法：
     *                「我记得名字里带运维」或「我记得手机号后四位」）
     */
    public List<Recipient> list(String keyword) {
        LambdaQueryWrapper<Recipient> query = new LambdaQueryWrapper<Recipient>()
                .orderByDesc(Recipient::getId);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            // 用 and(...) 把两个 like 括起来，否则会和前面的逻辑删除条件
            // 拼成 "deleted=0 AND name like ? OR address like ?"，OR 会绕过逻辑删除过滤
            query.and(w -> w.like(Recipient::getName, k).or().like(Recipient::getAddress, k));
        }
        return recipientMapper.selectList(query);
    }

    public void delete(Long id) {
        require(id);
        // 逻辑删除：历史消息里的 receiver 是地址快照，对象删了也不影响已发出的记录
        recipientMapper.deleteById(id);
        log.info("删除发送对象：id={}", id);
    }

    /**
     * 供发送链路调用：把 recipientId 解析成对象。
     * <p>
     * 发送时只传 ID，地址由服务端查库取——
     * 这样前端拿不到、也改不了别人的地址，避免「选了 A 却发到 B」。
     */
    public Recipient require(Long id) {
        Recipient recipient = recipientMapper.selectById(id);
        if (recipient == null) {
            throw new IllegalArgumentException("发送对象不存在：" + id);
        }
        return recipient;
    }

    /**
     * 账号必须存在，且通道要和对象一致。
     * <p>
     * 不能只靠前端过滤下拉框：绕过管理台直接调 API 也能传 accountId，
     * 把短信对象绑到公众号账号上会导致投递时取到一套完全无关的凭据。
     */
    private void validateAccount(Long accountId, ChannelType channel) {
        if (accountId == null) {
            return;
        }
        ChannelAccount account = accountService.require(accountId);
        if (!channel.name().equals(account.getChannel())) {
            throw new IllegalArgumentException("账号「" + account.getName() + "」属于 "
                    + account.getChannel() + " 通道，不能绑定到 " + channel + " 对象");
        }
    }

    private ChannelType parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("通道不能为空");
        }
        try {
            return ChannelType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的通道：" + raw);
        }
    }
}
