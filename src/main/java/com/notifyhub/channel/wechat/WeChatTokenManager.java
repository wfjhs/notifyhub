package com.notifyhub.channel.wechat;

import com.notifyhub.config.WeChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 微信公众号 access_token 管理器。
 * <p>
 * <b>为什么不能每次发消息都去换 token</b>：
 * <ul>
 *     <li>微信换 token 接口本身有限频，且每日总量有上限（约 2000 次）；</li>
 *     <li>新 token 生成后旧的会失效——多个实例各自刷新会互相踢掉对方的 token；</li>
 *     <li>换 token 是一次额外的网络往返，发消息延迟翻倍。</li>
 * </ul>
 *
 * <b>本实现的三条策略</b>：
 * <ol>
 *     <li><b>Redis 缓存</b>：token 存在 Redis，多实例共享同一份，不会互相踢；</li>
 *     <li><b>提前刷新</b>：缓存 TTL 比微信给的 expires_in 少 5 分钟，
 *         避免"刚好在过期瞬间拿到 token"导致发送失败；</li>
 *     <li><b>分布式锁</b>：缓存失效瞬间多个实例同时来取，只让一个去换，
 *         其余等待后重读缓存，避免并发刷新把配额烧光。</li>
 * </ol>
 *
 * <b>多账号隔离</b>：缓存 key 与锁 key 都带 appId。
 * 之前是全局常量 {@code wechat:mp:access_token}，接第二个公众号后会互相覆盖：
 * A 号刷新的 token 被 B 号拿去用，两边都报 40001。
 * 按 appId 分桶后，每个公众号各有一份独立 token 与独立刷新锁。
 *
 * <b>降级</b>：刷新失败时不清空旧缓存（旧 token 可能还有效），
 * 让下次调用再试，而不是把整条通道打死。
 */
@Slf4j
@Component
public class WeChatTokenManager {

    private static final String TOKEN_KEY_PREFIX = "wechat:mp:token:";
    private static final String LOCK_KEY_PREFIX = "wechat:mp:token:lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);
    private static final long EARLY_REFRESH_SECONDS = 300;

    private static final String TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token"
                    + "?grant_type=client_credential&appid={appid}&secret={secret}";

    private final StringRedisTemplate redis;
    private final WeChatProperties props;
    private final RestClient restClient;

    public WeChatTokenManager(StringRedisTemplate redis,
                              WeChatProperties props,
                              @Qualifier("weChatRestClient") RestClient restClient) {
        this.redis = redis;
        this.props = props;
        this.restClient = restClient;
    }

    /** 用全局配置里的公众号取 token（未绑定账号的消息走这条） */
    public String getAccessToken() {
        return getAccessToken(props.getMp().getAppId(), props.getMp().getAppSecret());
    }

    /** 按 appId 取 token。不同公众号互不影响。 */
    public String getAccessToken(String appId, String appSecret) {
        String key = tokenKey(appId);
        String cached = redis.opsForValue().get(key);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return refreshWithLock(appId, appSecret);
    }

    /** 微信返回 token 失效（40001 / 42001）时调用，强制下次重新换。 */
    public void invalidate(String appId) {
        try {
            redis.delete(tokenKey(appId));
        } catch (Exception e) {
            log.warn("清除 access_token 缓存失败：appId={}, {}", appId, e.getMessage());
        }
    }

    private String refreshWithLock(String appId, String appSecret) {
        String key = tokenKey(appId);
        String lockKey = lockKey(appId);
        Boolean locked = redis.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            // 别的实例正在刷新，短暂等待后直接读缓存
            sleepQuietly(500);
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
            throw new IllegalStateException("等待其他实例刷新 access_token 超时：appId=" + appId);
        }

        try {
            // 双检：可能在上锁的间隙别人已经刷好了
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
            return doRefresh(appId, appSecret);
        } finally {
            redis.delete(lockKey);
        }
    }

    private String doRefresh(String appId, String appSecret) {
        AccessTokenResponse resp;
        try {
            resp = restClient.get()
                    .uri(TOKEN_URL, appId, appSecret)
                    .retrieve()
                    .body(AccessTokenResponse.class);
        } catch (Exception e) {
            // 网络异常：不清缓存，旧 token 可能还没过期
            throw new IllegalStateException("请求微信 access_token 失败：" + e.getMessage(), e);
        }

        if (resp == null || resp.getAccessToken() == null || resp.getAccessToken().isBlank()) {
            throw new IllegalStateException("微信返回了无 token 的响应：appId=" + appId
                    + ", errcode=" + (resp == null ? "null" : resp.getErrcode())
                    + ", errmsg=" + (resp == null ? "null" : resp.getErrmsg()));
        }

        long ttl = Math.max(60, resp.getExpiresIn() - EARLY_REFRESH_SECONDS);
        redis.opsForValue().set(tokenKey(appId), resp.getAccessToken(), Duration.ofSeconds(ttl));
        log.info("access_token 已刷新，appId={}, 缓存 {} 秒（微信有效期 {} 秒，提前 {} 秒过期）",
                appId, ttl, resp.getExpiresIn(), EARLY_REFRESH_SECONDS);
        return resp.getAccessToken();
    }

    private static String tokenKey(String appId) {
        return TOKEN_KEY_PREFIX + appId;
    }

    private static String lockKey(String appId) {
        return LOCK_KEY_PREFIX + appId;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
