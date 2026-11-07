import axios, { type AxiosResponse } from 'axios';
import type {
  ApiResult,
  ChannelAccount,
  ChannelType,
  DeadLetter,
  Message,
  Recipient,
  RecipientFormPayload,
  SaveChannelAccountPayload,
  SendMessagePayload,
  SendResult,
} from '@/types';

/**
 * baseURL 用相对路径 /api：
 * 开发时由 Vite 的 proxy 转发到 8082，生产由 nginx 反代到后端。
 * 前端代码里不出现后端地址与端口，换环境不用改代码。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
});

/**
 * 后端用 HTTP 200 + 业务 code 表达错误（登录失败也是 code=1002），
 * 所以不能只看 HTTP 状态码，必须在拦截器里解包。
 */
http.interceptors.response.use(
  (resp: AxiosResponse<ApiResult<unknown>>) => {
    const body = resp.data;
    if (body && typeof body.code === 'number' && body.code !== 0) {
      return Promise.reject(new Error(`[${body.code}] ${body.message}`));
    }
    return resp;
  },
  (error) => {
    const message =
      error?.response?.data?.message ??
      (error?.code === 'ECONNABORTED' ? '请求超时' : error?.message) ??
      '请求失败';
    return Promise.reject(new Error(message));
  },
);

async function unwrap<T>(promise: Promise<AxiosResponse<ApiResult<T>>>): Promise<T> {
  const { data } = await promise;
  return data.data;
}

/** 发送消息。同一 bizId + channel 重复提交会返回 duplicated=true 与原 messageId */
export function sendMessage(payload: SendMessagePayload): Promise<SendResult> {
  return unwrap(http.post<ApiResult<SendResult>>('/messages', payload));
}

/** 按业务键查该业务下所有通道的消息 —— 排查「用户说没收到」的第一入口 */
export function listByBizId(bizId: string): Promise<Message[]> {
  return unwrap(http.get<ApiResult<Message[]>>('/messages', { params: { bizId } }));
}

export function listDead(limit = 50): Promise<DeadLetter[]> {
  return unwrap(http.get<ApiResult<DeadLetter[]>>('/messages/dead', { params: { limit } }));
}

/** 重放死信：原消息重置为 PENDING 并清零重试次数 */
export function replayDead(deadId: number): Promise<void> {
  return unwrap(http.post<ApiResult<void>>(`/messages/dead/${deadId}/replay`));
}

// ==================== 发送对象 ====================

/**
 * 发送对象列表。keyword 按名称或地址模糊匹配。
 * 发送时只传 recipientId，地址由服务端查库解析 ——
 * 前端拿不到也改不了别人的地址。
 */
export function listRecipients(keyword?: string): Promise<Recipient[]> {
  return unwrap(
    http.get<ApiResult<Recipient[]>>('/recipients', {
      params: keyword?.trim() ? { keyword: keyword.trim() } : undefined,
    }),
  );
}

export function createRecipient(payload: RecipientFormPayload): Promise<Recipient> {
  return unwrap(http.post<ApiResult<Recipient>>('/recipients', payload));
}

export function updateRecipient(id: number, payload: RecipientFormPayload): Promise<Recipient> {
  return unwrap(http.put<ApiResult<Recipient>>(`/recipients/${id}`, payload));
}

export function deleteRecipient(id: number): Promise<void> {
  return unwrap(http.delete<ApiResult<void>>(`/recipients/${id}`));
}

// ==================== 通道账号 ====================

/** 列表返回的是脱敏视图：appSecret 不下发，webhook 只给掩码 */
export function listAccounts(channel?: ChannelType): Promise<ChannelAccount[]> {
  return unwrap(
    http.get<ApiResult<ChannelAccount[]>>('/accounts', {
      params: channel ? { channel } : undefined,
    }),
  );
}

export function createAccount(payload: SaveChannelAccountPayload): Promise<ChannelAccount> {
  return unwrap(http.post<ApiResult<ChannelAccount>>('/accounts', payload));
}

export function updateAccount(
  id: number,
  payload: SaveChannelAccountPayload,
): Promise<ChannelAccount> {
  return unwrap(http.put<ApiResult<ChannelAccount>>(`/accounts/${id}`, payload));
}

export function deleteAccount(id: number): Promise<void> {
  return unwrap(http.delete<ApiResult<void>>(`/accounts/${id}`));
}
