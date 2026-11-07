package com.notifyhub.channel;

import com.notifyhub.model.entity.Message;
import com.notifyhub.model.enums.ChannelType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 短信通道的 Mock 实现。
 * <p>
 * 真实的短信网关有几类典型故障：连接超时、被限流、号码非法、余额不足。
 * 这里用「按比例随机失败」来模拟前两类，好让重试与死信链路能被真实触发——
 * 一个永远成功的通道是测不出可靠性设计的。
 * <p>
 * 失败率通过 {@code notify.channel.sms.fail-rate} 配置，默认 0.3。
 * 想跑通全链路时改成 0 即可。
 */
@Slf4j
@Component
public class MockSmsChannel implements NotifyChannel {

    @Value("${notify.channel.sms.fail-rate:0.3}")
    private double failRate;

    @Value("${notify.channel.sms.latency-ms:20}")
    private long latencyMs;

    @Override
    public ChannelType type() {
        return ChannelType.SMS;
    }

    @Override
    public void send(Message message) throws Exception {
        // 模拟网络往返
        if (latencyMs > 0) {
            Thread.sleep(latencyMs);
        }

        if (ThreadLocalRandom.current().nextDouble() < failRate) {
            // 抛异常而不是返回 false：外层需要拿到失败原因写进 fail_reason
            throw new IllegalStateException("模拟短信网关超时（随机失败，fail-rate=" + failRate + "）");
        }

        log.info("[SMS] 已送达 {}：{}", message.getReceiver(), message.getContent());
    }
}
