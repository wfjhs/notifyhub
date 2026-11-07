-- ============================================================
-- NotifyHub 建表脚本
-- 用法：mysql -uroot -p < sql/schema.sql
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `notifyhub`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `notifyhub`;

-- ------------------------------------------------------------
-- 消息表：一条待投递的消息
--
-- 核心设计：不依赖 MQ，用「数据库消息表 + 定时补偿」保证最终一致。
-- 消息先落库再投递，只要库里还有非终态记录，补偿任务就会继续尝试，
-- 因此进程崩溃、通道不可用都不会导致消息「凭空消失」。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `message` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `biz_id`          VARCHAR(64)  NOT NULL COMMENT '业务唯一键（如订单号），与 channel 组成幂等键',
    `biz_type`        VARCHAR(32)  NOT NULL COMMENT '业务类型，便于按业务维度排查与统计',
    `channel`         VARCHAR(16)  NOT NULL COMMENT '通道：SMS / INBOX / EMAIL',
    `receiver`        VARCHAR(128) NOT NULL COMMENT '接收方：手机号 / 用户ID / 邮箱',
    `account_id`      BIGINT                DEFAULT NULL COMMENT '投递使用的通道账号快照；为空回落全局配置',
    `title`           VARCHAR(128)          DEFAULT NULL,
    `content`         TEXT         NOT NULL COMMENT '模板渲染后的最终内容，落库后不再变',
    `status`          VARCHAR(16)  NOT NULL COMMENT 'PENDING / SENDING / SUCCESS / FAILED / DEAD',
    `retry_count`     INT          NOT NULL DEFAULT 0,
    `max_retry`       INT          NOT NULL DEFAULT 3,
    `next_retry_time` DATETIME              DEFAULT NULL COMMENT '下次可投递时间，指数退避的落点',
    `fail_reason`     VARCHAR(500)          DEFAULT NULL,
    `sent_at`         DATETIME              DEFAULT NULL,
    `create_time`     DATETIME     NOT NULL,
    `update_time`     DATETIME     NOT NULL,
    `deleted`         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    -- 幂等的地基：同一业务同一通道只允许一条记录。
    -- 重复提交由唯一键挡掉（捕获 DuplicateKeyException 后返回已有记录），
    -- 不需要分布式锁，也不存在「先查后插」的竞态窗口。
    UNIQUE KEY `uk_biz_channel` (`biz_id`, `channel`),
    -- 补偿扫描专用：WHERE status=? AND next_retry_time<=? ORDER BY next_retry_time
    -- 两列顺序不能反，否则用不上索引
    KEY `idx_status_retry` (`status`, `next_retry_time`),
    KEY `idx_receiver_time` (`receiver`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='通知消息表';

-- ------------------------------------------------------------
-- 模板表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `message_template` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(64)  NOT NULL COMMENT '模板编码，如 ORDER_PAID',
    `channel`     VARCHAR(16)  NOT NULL,
    `title`       VARCHAR(128)          DEFAULT NULL,
    `content`     TEXT         NOT NULL COMMENT '支持 ${var} 占位符',
    `create_time` DATETIME     NOT NULL,
    `update_time` DATETIME     NOT NULL,
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_channel` (`code`, `channel`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='消息模板';

-- ------------------------------------------------------------
-- 死信表：重试耗尽的消息
--
-- 为什么不直接留在 message 表里标 DEAD：死信需要人工介入，
-- 单独一张表便于告警、导出、以及「修好后重放」。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `message_dead` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `message_id`  BIGINT       NOT NULL,
    `biz_id`      VARCHAR(64)  NOT NULL,
    `channel`     VARCHAR(16)  NOT NULL,
    `receiver`    VARCHAR(128) NOT NULL,
    `content`     TEXT         NOT NULL,
    `retry_count` INT          NOT NULL,
    `fail_reason` VARCHAR(500)          DEFAULT NULL,
    `create_time` DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_message` (`message_id`),
    KEY `idx_biz` (`biz_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='重试耗尽的死信，需人工介入';

-- ------------------------------------------------------------
-- 发送对象表：接收方地址簿
--
-- 为什么单独建表而不是每次手输手机号/openid：
--   1. 地址是长期稳定的，重复输入既慢又容易写错（错一位就是发错人）；
--   2. 同一个人在不同通道有不同的地址（站内信用 userId，短信用手机号），
--      表里按「名称 + 通道」唯一，天然支持一个人多条记录；
--   3. 发送时只传 recipientId，地址由服务端解析，
--      前端拿不到也改不了别人的地址。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `recipient` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(64)  NOT NULL COMMENT '对象名称，如「张三」「运维告警群」',
    `channel`     VARCHAR(16)  NOT NULL COMMENT '通道：INBOX / SMS / WECHAT_WORK / WECHAT_MP',
    `address`     VARCHAR(128) NOT NULL COMMENT '地址：用户ID / 手机号 / 群标识 / openid',
    `account_id`  BIGINT                DEFAULT NULL COMMENT '绑定的通道账号；为空回落全局配置',
    `remark`      VARCHAR(255)          DEFAULT NULL COMMENT '备注，便于区分同名对象',
    `create_time` DATETIME     NOT NULL,
    `update_time` DATETIME     NOT NULL,
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    -- 同名同通道只允许一条：既防重复录入，也避免下拉框里出现两个一样的选项
    UNIQUE KEY `uk_name_channel` (`name`, `channel`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='发送对象（接收方地址簿）';

-- ------------------------------------------------------------
-- 通道账号表：把「凭据」从应用级配置下沉成可管理的数据
--
-- 为什么需要这张表：
--   之前 appId / appSecret / templateId 写在 application.yml，
--   整个应用只能有一个公众号。要接第二个公众号就得改配置重启，
--   这不算「中台」。账号入库后，新增/停用/切换都在管理台完成。
--
-- 与 recipient 的关系：
--   一个对象绑定一个账号（可空）。消息落库时把 accountId 一起记下来，
--   补偿任务在进程重启后重试时才知道当初该用哪套凭据。
--
-- 安全边界：
--   app_secret 明文存库仅适用于本地演示；生产应换成 KMS/Vault 或加密列，
--   且列表接口必须脱敏（见 ChannelAccountVO）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `channel_account` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(64)  NOT NULL COMMENT '账号名称，如「主公众号」「告警号」',
    `channel`     VARCHAR(16)  NOT NULL COMMENT '通道：WECHAT_MP / WECHAT_WORK',
    `app_id`      VARCHAR(64)           DEFAULT NULL COMMENT '公众号 AppID',
    `app_secret`  VARCHAR(256)          DEFAULT NULL COMMENT '公众号 AppSecret（列表接口不返回）',
    `template_id` VARCHAR(128)          DEFAULT NULL COMMENT '公众号模板 ID',
    `webhook_url` VARCHAR(512)          DEFAULT NULL COMMENT '企业微信群机器人 Webhook',
    `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '停用后使用该账号的消息会明确失败，不静默回落',
    `remark`      VARCHAR(255)          DEFAULT NULL,
    `create_time` DATETIME     NOT NULL,
    `update_time` DATETIME     NOT NULL,
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account_name_channel` (`name`, `channel`),
    KEY `idx_account_channel` (`channel`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='通道账号（凭据）';
