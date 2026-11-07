-- ============================================================
-- 迁移 001：通道账号（多公众号支持）
-- 用法：mysql -uroot -p < sql/migration-001-channel-account.sql
--
-- 新库不用跑这个文件，schema.sql 已经包含全部表与列。
-- 这个脚本是给「改造前已建好的库」用的，且可以重复执行。
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `notifyhub`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `notifyhub`;

CREATE TABLE IF NOT EXISTS `channel_account` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(64)  NOT NULL COMMENT '账号名称，如「主公众号」「告警号」',
    `channel`     VARCHAR(16)  NOT NULL COMMENT '通道：WECHAT_MP / WECHAT_WORK',
    `app_id`      VARCHAR(64)           DEFAULT NULL COMMENT '公众号 AppID',
    `app_secret`  VARCHAR(256)          DEFAULT NULL COMMENT '公众号 AppSecret（列表接口不返回）',
    `template_id` VARCHAR(128)          DEFAULT NULL COMMENT '公众号模板 ID',
    `webhook_url` VARCHAR(512)          DEFAULT NULL COMMENT '企业微信群机器人 Webhook',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '停用后使用该账号的消息会明确失败',
    `remark`      VARCHAR(255)          DEFAULT NULL,
    `create_time` DATETIME     NOT NULL,
    `update_time` DATETIME     NOT NULL,
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_name_channel` (`name`, `channel`),
    KEY `idx_account_channel` (`channel`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='通道账号（凭据）';

-- MySQL 8 没有 ADD COLUMN IF NOT EXISTS，用存储过程按 information_schema 判断，
-- 保证这个脚本重复执行也不会因为列已存在而中断。
DROP PROCEDURE IF EXISTS `nh_add_col`;
DELIMITER $$
CREATE PROCEDURE `nh_add_col`(IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_col
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL `nh_add_col`('message', 'account_id',
    '`account_id` BIGINT NULL COMMENT ''投递使用的通道账号快照；为空回落全局配置'' AFTER `receiver`');
CALL `nh_add_col`('recipient', 'account_id',
    '`account_id` BIGINT NULL COMMENT ''绑定的通道账号；为空回落全局配置'' AFTER `address`');

DROP PROCEDURE IF EXISTS `nh_add_col`;
