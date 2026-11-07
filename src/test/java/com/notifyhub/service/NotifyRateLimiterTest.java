package com.notifyhub.service;

import com.notifyhub.config.RateLimitException;
import com.notifyhub.model.enums.ChannelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 限流器单测。
 * <p>
 * Lua 脚本本身由 Redis 保证原子性，不需要在这里测；
 * 这里测的是<b>脚本返回值到业务语义的映射</b>：什么情况放行、什么情况拒绝、
 * Redis 挂掉时是拒绝还是放行——最后一个决策错了会导致缓存故障时全站发不出通知。
 */
@ExtendWith(MockitoExtension.class)
class NotifyRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    private NotifyRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new NotifyRateLimiter(redis);
        ReflectionTestUtils.setField(limiter, "enabled", true);
        ReflectionTestUtils.setField(limiter, "receiverLimit", 5);
        ReflectionTestUtils.setField(limiter, "channelLimit", 100);
        ReflectionTestUtils.setField(limiter, "windowSeconds", 60);
    }

    /** 脚本返回 1 = 未超限 */
    private void scriptAllows() {
        doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    /** 脚本返回 0 = 超限 */
    private void scriptRejects() {
        doReturn(0L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("开关关闭：直接放行，连 Redis 都不访问")
    void disabledSkipsRedis() {
        ReflectionTestUtils.setField(limiter, "enabled", false);

        assertThatCode(() -> limiter.check("13800000000", ChannelType.SMS))
                .doesNotThrowAnyException();

        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("脚本放行：不抛异常")
    void allowedPasses() {
        scriptAllows();

        assertThatCode(() -> limiter.check("13800000000", ChannelType.SMS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("接收方超限：拒绝，防止对同一号码的短信轰炸")
    void receiverLimitRejects() {
        scriptRejects();

        assertThatThrownBy(() -> limiter.check("13800000000", ChannelType.SMS))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("接收方");
    }

    @Test
    @DisplayName("接收方通过但通道超限：也拒绝（通道维度控制成本与配额）")
    void channelLimitRejects() {
        // 第一次调用（receiver）放行，第二次（channel）拒绝
        doReturn(1L, 0L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatThrownBy(() -> limiter.check("13800000000", ChannelType.SMS))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("通道");
    }

    @Test
    @DisplayName("Redis 故障：兜底放行，限流失效不应拖垮整条投递链路")
    void redisFailureFailsOpen() {
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThatCode(() -> limiter.check("13800000000", ChannelType.SMS))
                .doesNotThrowAnyException();
    }
}
