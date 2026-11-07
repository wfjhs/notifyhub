-- ============================================================
-- NotifyHub 种子数据（幂等，可重复执行）
-- 用法：mysql -uroot -p < sql/seed.sql
-- ============================================================

SET NAMES utf8mb4;

USE `notifyhub`;

INSERT INTO `message_template` (`code`, `channel`, `title`, `content`, `create_time`, `update_time`, `deleted`)
VALUES ('ORDER_PAID', 'SMS', NULL,
        '【NotifyHub】您的订单 ${orderNo} 已支付成功，金额 ${amount} 元。', NOW(), NOW(), 0),
       ('ORDER_PAID', 'INBOX', '订单支付成功',
        '您的订单 ${orderNo} 已支付成功，金额 ${amount} 元。', NOW(), NOW(), 0),
       ('CODE_RESULT', 'INBOX', '判题完成',
        '提交 #${submissionId} 的判题结果为 ${status}，得分 ${score}。', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE `content` = VALUES(`content`),
                        `title`   = VALUES(`title`),
                        `update_time` = NOW();
