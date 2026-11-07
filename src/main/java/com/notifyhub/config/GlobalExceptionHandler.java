package com.notifyhub.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把异常收敛成统一响应，不让堆栈泄漏给调用方。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验失败（@Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResult<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? "参数错误" : fe.getField() + " " + fe.getDefaultMessage();
        return ApiResult.fail(400, msg);
    }

    /** 限流拒绝：429 让调用方知道「不是参数错，是让我慢一点」 */
    @ExceptionHandler(RateLimitException.class)
    public ApiResult<Void> handleRateLimit(RateLimitException e) {
        log.warn("触发限流：{}", e.getMessage());
        return ApiResult.fail(429, e.getMessage());
    }

    /** 业务参数问题，如通道不支持、模板不存在 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResult<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误：{}", e.getMessage());
        return ApiResult.fail(400, e.getMessage());
    }

    /**
     * 路径不存在（含静态资源、favicon.ico）。
     * <p>
     * 这不是系统故障，是客户端请求了不存在的地址。若落到兜底的 Exception 分支，
     * 每访问一次就刷一整屏堆栈，真正的故障会被淹没。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResult<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("资源不存在：{}", e.getResourcePath());
        return ApiResult.fail(404, "接口不存在：" + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return ApiResult.fail(500, "系统繁忙，请稍后重试");
    }
}
