package com.notifyhub.model.enums;

/**
 * 消息状态机。
 * <pre>
 * PENDING ──抢占──> SENDING ──成功──> SUCCESS
 *    ↑                  │
 *    └──退避等待────────┤ 失败
 *                       ↓
 *                    FAILED ──超过次数──> DEAD
 * </pre>
 * <p>
 * PENDING 与 FAILED 都是「可被投递」的状态，区别只是 FAILED 带了下一次重试时间。
 * 补偿任务扫描的是 {@code PENDING} 与 {@code FAILED} 中 {@code next_retry_time <= now} 的记录。
 */
public enum MessageStatus {

    /** 已落库，等待首次投递 */
    PENDING,

    /** 已被 worker 抢占，正在投递（并发下靠条件更新保证只有一个 worker 能拿到） */
    SENDING,

    /** 投递成功（终态） */
    SUCCESS,

    /** 投递失败，等待重试 */
    FAILED,

    /** 重试次数耗尽，转入死信（终态） */
    DEAD;

    /** 是否为终态：终态消息不再参与补偿扫描 */
    public boolean isTerminal() {
        return this == SUCCESS || this == DEAD;
    }
}
