/** 后端统一响应体：HTTP 200 + 业务 code */
export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

export type MessageStatus = 'PENDING' | 'SENDING' | 'SUCCESS' | 'FAILED' | 'DEAD';

export type ChannelType = 'SMS' | 'INBOX' | 'WECHAT_WORK' | 'WECHAT_MP';

export interface Message {
  id: number;
  bizId: string;
  bizType: string;
  channel: ChannelType;
  receiver: string;
  /** 投递使用的通道账号；为空表示回落全局配置 */
  accountId?: number | null;
  title?: string | null;
  content: string;
  status: MessageStatus;
  retryCount: number;
  maxRetry: number;
  failReason?: string | null;
  nextRetryTime?: string | null;
  createTime?: string;
  updateTime?: string;
}

/** message_dead 表：额外带 messageId 指向原消息 */
export interface DeadLetter {
  id: number;
  messageId: number;
  bizId: string;
  channel: ChannelType;
  receiver: string;
  content: string;
  retryCount: number;
  failReason?: string | null;
  createTime?: string;
}

export interface SendMessagePayload {
  bizId: string;
  bizType: string;
  /** 发送对象 ID，传了就由服务端解析通道与地址，此时 channel / receiver 被忽略 */
  recipientId?: number;
  /** 未指定对象时使用；与 recipientId 二选一 */
  channel?: ChannelType;
  receiver?: string;
  /** 直传方式指定通道账号；选了对象时以对象绑定的为准 */
  accountId?: number;
  content: string;
}

export interface SendResult {
  messageId: number;
  status: MessageStatus;
  duplicated: boolean;
}

/** 发送对象（接收方地址簿）：一个对象 = 某个接收方 + 某个通道 + 该通道下的地址 */
export interface Recipient {
  id: number;
  name: string;
  channel: ChannelType;
  address: string;
  /** 绑定的通道账号；为空回落全局配置 */
  accountId?: number | null;
  remark?: string | null;
  createTime?: string;
}

/** 新增与修改共用同一份表单 */
export interface RecipientFormPayload {
  name: string;
  channel: ChannelType;
  address: string;
  accountId?: number | null;
  remark?: string;
}

/**
 * 通道账号（对外视图，密钥已脱敏）。
 * appSecret 与完整 webhook 都不会下发到浏览器，编辑时留空表示保持原值。
 */
export interface ChannelAccount {
  id: number;
  name: string;
  channel: ChannelType;
  appId?: string | null;
  templateId?: string | null;
  webhookMasked?: string | null;
  secretConfigured?: boolean;
  webhookConfigured?: boolean;
  enabled?: boolean;
  remark?: string | null;
  createTime?: string;
}

export interface SaveChannelAccountPayload {
  name: string;
  channel: ChannelType;
  appId?: string;
  /** 留空 = 不修改原密钥；只有显式填了才覆盖 */
  appSecret?: string;
  templateId?: string;
  webhookUrl?: string;
  enabled?: boolean;
  remark?: string;
}

/** 表格统一行，把 Message 与 DeadLetter 抹平成同一结构 */
export interface MessageRow {
  id: number;
  status: MessageStatus;
  channel: string;
  receiver: string;
  retryCount: number;
  maxRetry?: number;
  content: string;
  failReason?: string | null;
  time?: string;
}
