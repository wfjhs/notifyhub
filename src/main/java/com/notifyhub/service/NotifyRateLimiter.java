package com.notifyhub.service;

import com.notifyhub.config.RateLimitException;
import com.notifyhub.model.enums.ChannelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 通知投递限流。
 * <p>
 * <b>为什么通知中台必须有限流</b>：
 * <ul>
 *     <li><b>短信轰炸</b>：有人刷接口给同一个手机号发几百条，既烧钱又会被投诉；</li>
 *     <li><b>成本</b>：短信按条计费，站内信免费——通道之间成本差异巨大；</li>
 *     <li><b>通道配额</b>：微信有日调用上限，打爆了当天全站都发不出去。</li>
 * </ul>
 *
 * <b>为什么用固定窗口计数而不是令牌桶</b>：
 * 令牌桶控制的是「速率」（每秒 N 个），适合限 QPS；
 * 通知场景要控制的是「总量」——「同一手机号每分钟最多 5 条」，
 * 语义上就是固定窗口计数，用令牌桶反而绕远。
 *
 * <b>已实现的维度</b>：
 * <ol>
 *     <li>按接收方：防单点轰炸（默认 5 条/分钟）</li>
 *     <li>按通道：防某通道被打爆（默认 100 条/分钟）</li>
 * </ol>
 *
 * <b>已知缺陷</b>：固定窗口在窗口边界有「双倍突发」问题——
 * 第 59 秒发 5 条、第 61 秒再发 5 条，两秒内实际发了 10 条。
 * 要彻底解决得用滑动窗口（ZSet 或 Redis Cell），这里为了简单先接受。
 */
@Slf4j
@Component
public class NotifyRateLimiter {

    /**
     * 固定窗口计数脚本。
     * <pre>
     *   KEYS[1] = 计数键
     *   ARGV[1] = 窗口内允许上限
     *   ARGV[2] = 窗口秒数
     *   返回 1 放行 / 0 拒绝
     * </pre>
     * 只在计数为 1（窗口内第一次）时设过期，保证是固定窗口而不是滑动窗口；
     * INCR 与 EXPIRE 在同一个脚本里，不会出现「加了计数但没设过期」导致 key 永久驻留。
     */
    private static final String FIXED_WINDOW_LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
              return 0
            end
            return 1
            """;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> script;

    @Value("${notify.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${notify.ratelimit.receiver-limit:5}")
    private int receiverLimit;

    @Value("${notify.ratelimit.channel-limit:100}")
    private int channelLimit;

    @Value("${notify.ratelimit.window-seconds:60}")
    private int windowSeconds;

    public NotifyRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText(FIXED_WINDOW_LUA);
        s.setResultType(Long.class);
        this.script = s;
    }

    /**
     * 检查是否允许本次发送，不允许则抛 {@link RateLimitException}。
     *
     * @param receiver 接收方（手机号 / openid）
     * @param channel  通道
     */
    public void check(String receiver, ChannelType channel) {
        if (!enabled) {
            return;
        }
        if (!tryAcquire("rl:notify:receiver:" + receiver, receiverLimit)) {
            log.warn("接收方触发限流：receiver={}, limit={}/{}s", receiver, receiverLimit, windowSeconds);
            throw new RateLimitException("该接收方发送过于频繁，请稍后再试");
        }
        if (!tryAcquire("rl:notify:channel:" + channel.name(), channelLimit)) {
            log.warn("通道触发限流：channel={}, limit={}/{}s", channel, channelLimit, windowSeconds);
            throw new RateLimitException("该通道当前发送量过大，请稍后再试");
        }
    }

    private boolean tryAcquire(String key, int limit) {
        try {
            Long allowed = redis.execute(script,
                    Collections.singletonList(key),
                    String.valueOf(limit), String.valueOf(windowSeconds));
            return allowed != null && allowed == 1L;
        } catch (Exception e) {
            // Redis 故障时兜底放行：限流失效好过整条链路不可用。
            // 与项目一令牌桶的取舍一致。
            log.warn("限流执行失败，兜底放行：key={}, {}", key, e.getMessage());
            return true;
        }
    }
}
