package com.notifyhub.config;

/**
 * 限流拒绝。
 * <p>
 * 单独一个异常类型而不是复用 IllegalArgumentException，
 * 是为了让全局异常处理器能返回 429 而不是 400——
 * 「参数错了」和「请求太频繁」对调用方是完全不同的处理方式。
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
