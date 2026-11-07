package com.notifyhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.notifyhub.channel.ChannelCredentials;
import com.notifyhub.config.WeChatProperties;
import com.notifyhub.mapper.ChannelAccountMapper;
import com.notifyhub.model.dto.SaveChannelAccountDTO;
import com.notifyhub.model.entity.ChannelAccount;
import com.notifyhub.model.enums.ChannelType;
import com.notifyhub.model.vo.ChannelAccountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通道账号管理：把凭据变成可以增删改查的数据。
 * <p>
 * 设计要点：
 * <ul>
 *     <li>凭据按账号存，消息只记 accountId；改密钥不用改消息，历史消息仍能按新密钥重试；</li>
 *     <li>未绑定账号的消息回落到全局配置，老数据零迁移成本；</li>
 *     <li>账号被删除或停用时不静默回落——那会把消息悄悄发到另一个公众号，
 *         比明确失败更危险。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelAccountService {

    private final ChannelAccountMapper accountMapper;
    private final WeChatProperties props;

    public List<ChannelAccountVO> list(String channel) {
        LambdaQueryWrapper<ChannelAccount> query = new LambdaQueryWrapper<ChannelAccount>()
                .orderByDesc(ChannelAccount::getId);
        if (channel != null && !channel.isBlank()) {
            query.eq(ChannelAccount::getChannel, parseChannel(channel).name());
        }
        return accountMapper.selectList(query).stream().map(this::toVO).toList();
    }

    public ChannelAccountVO create(SaveChannelAccountDTO dto) {
        ChannelType channel = parseChannel(dto.getChannel());
        ChannelAccount account = new ChannelAccount();
        account.setName(trim(dto.getName()));
        account.setChannel(channel.name());
        account.setAppId(trim(dto.getAppId()));
        account.setAppSecret(trimToNull(dto.getAppSecret()));
        account.setTemplateId(trim(dto.getTemplateId()));
        account.setWebhookUrl(trimToNull(dto.getWebhookUrl()));
        account.setEnabled(dto.getEnabled() == null || dto.getEnabled() ? 1 : 0);
        account.setRemark(trimToNull(dto.getRemark()));
        LocalDateTime now = LocalDateTime.now();
        account.setCreateTime(now);
        account.setUpdateTime(now);
        account.setDeleted(0);

        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该通道下已有同名账号：" + dto.getName());
        }
        log.info("新增通道账号：id={}, name={}, channel={}", account.getId(), account.getName(), channel);
        return toVO(account);
    }

    public ChannelAccountVO update(Long id, SaveChannelAccountDTO dto) {
        ChannelAccount existing = require(id);
        ChannelType channel = parseChannel(dto.getChannel());

        // secret 与 webhook 留空表示「不修改」，避免编辑备注时把密钥清空
        String secret = trimToNull(dto.getAppSecret());
        String webhook = trimToNull(dto.getWebhookUrl());

        try {
            accountMapper.update(null, new LambdaUpdateWrapper<ChannelAccount>()
                    .eq(ChannelAccount::getId, id)
                    .set(ChannelAccount::getName, trim(dto.getName()))
                    .set(ChannelAccount::getChannel, channel.name())
                    .set(ChannelAccount::getAppId, trim(dto.getAppId()))
                    .set(ChannelAccount::getTemplateId, trim(dto.getTemplateId()))
                    .set(secret != null, ChannelAccount::getAppSecret, secret)
                    .set(webhook != null, ChannelAccount::getWebhookUrl, webhook)
                    .set(ChannelAccount::getEnabled,
                            dto.getEnabled() == null || dto.getEnabled() ? 1 : 0)
                    .set(ChannelAccount::getRemark, trimToNull(dto.getRemark()))
                    .set(ChannelAccount::getUpdateTime, LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("该通道下已有同名账号：" + dto.getName());
        }
        log.info("修改通道账号：id={}, name={}, channel={}, 原通道={}",
                id, dto.getName(), channel, existing.getChannel());
        return toVO(require(id));
    }

    public void delete(Long id) {
        require(id);
        // 逻辑删除。历史消息仍存着 accountId，若之后重试会明确报「账号不存在」，
        // 由人工重放处理，不会误发到别的账号。
        accountMapper.deleteById(id);
        log.info("删除通道账号：id={}", id);
    }

    public ChannelAccount require(Long id) {
        ChannelAccount account = accountMapper.selectById(id);
        if (account == null) {
            throw new IllegalArgumentException("通道账号不存在或已删除：" + id);
        }
        return account;
    }

    /**
     * 解析一次投递要用的凭据。
     *
     * @param accountId 消息上记录的账号；null 表示老数据 / 未绑定，走全局配置
     */
    public ChannelCredentials resolve(ChannelType channel, Long accountId) {
        if (accountId == null) {
            return globalCredentials(channel);
        }

        ChannelAccount account = require(accountId);
        if (!channel.name().equals(account.getChannel())) {
            throw new IllegalArgumentException("账号 " + account.getName()
                    + " 属于 " + account.getChannel() + "，不能用于 " + channel);
        }
        if (account.getEnabled() == null || account.getEnabled() == 0) {
            throw new IllegalArgumentException("通道账号已停用：" + account.getName());
        }
        return new ChannelCredentials(
                account.getAppId(), account.getAppSecret(),
                account.getTemplateId(), account.getWebhookUrl());
    }

    private ChannelCredentials globalCredentials(ChannelType channel) {
        return switch (channel) {
            case WECHAT_MP -> new ChannelCredentials(
                    props.getMp().getAppId(), props.getMp().getAppSecret(),
                    props.getMp().getTemplateId(), null);
            case WECHAT_WORK -> new ChannelCredentials(
                    null, null, null, props.getWork().getWebhookUrl());
            default -> new ChannelCredentials(null, null, null, null);
        };
    }

    private ChannelAccountVO toVO(ChannelAccount a) {
        ChannelAccountVO vo = new ChannelAccountVO();
        vo.setId(a.getId());
        vo.setName(a.getName());
        vo.setChannel(a.getChannel());
        vo.setAppId(a.getAppId());
        vo.setTemplateId(a.getTemplateId());
        vo.setWebhookMasked(mask(a.getWebhookUrl(), 32));
        vo.setSecretConfigured(notBlank(a.getAppSecret()));
        vo.setWebhookConfigured(notBlank(a.getWebhookUrl()));
        vo.setEnabled(a.getEnabled() != null && a.getEnabled() == 1);
        vo.setRemark(a.getRemark());
        vo.setCreateTime(a.getCreateTime());
        return vo;
    }

    private static String mask(String raw, int keep) {
        if (!notBlank(raw)) {
            return null;
        }
        return raw.length() <= keep ? raw.substring(0, Math.min(4, raw.length())) + "***"
                : raw.substring(0, keep) + "***";
    }

    private static ChannelType parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("通道不能为空");
        }
        try {
            return ChannelType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("不支持的通道：" + raw);
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
