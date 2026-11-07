package com.notifyhub.worker;

import com.notifyhub.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 投递补偿 worker。
 * <p>
 * 用「定时扫描数据库」而不是「MQ 消费」来驱动投递，是这套设计的核心取舍：
 * <ul>
 *     <li><b>可靠性</b>：消息状态在库里，进程崩溃后重启继续扫，不依赖消息中间件的持久化配置；</li>
 *     <li><b>可观测</b>：任何时刻都能用一条 SQL 查出「有多少条卡住了」；</li>
 *     <li><b>代价</b>：有最多一个扫描间隔的延迟（默认 5s），且高频扫描对库有压力。</li>
 * </ul>
 * 通知场景对延迟不敏感（用户不会介意短信晚 5 秒），所以这个取舍是划算的。
 * 如果换成订单创建这种强实时场景，应该改用 MQ 或事务消息。
 * <p>
 * {@code fixedDelay} 而不是 {@code fixedRate}：上一轮没跑完时不应该叠加下一轮，
 * 否则通道变慢时任务会越堆越多。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "notify.worker.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryWorker {

    private final MessageService messageService;

    @Value("${notify.worker.batch-size:20}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${notify.worker.scan-interval-ms:5000}")
    public void scan() {
        try {
            int handled = messageService.scanAndDispatch(batchSize);
            if (handled > 0) {
                log.debug("补偿扫描处理 {} 条消息", handled);
            }
        } catch (Exception e) {
            // 定时任务抛异常会被调度器吞掉并停止后续执行，必须自己兜住
            log.error("补偿扫描异常，下一轮继续", e);
        }
    }
}
