/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { apiFetch, ApiError } from '@/lib/apiClient';

export type WeixinFlowStatus = 'STARTING' | 'WAITING_SCAN' | 'SCANNED' | 'NEED_VERIFY_CODE' | 'AUTHORIZED' | 'COMPLETED' | 'EXPIRED' | 'CANCELLED' | 'FAILED';
export interface WeixinLinkFlow {
  flowId: string;
  status: WeixinFlowStatus;
  expiresAt: number;
  pollAfterMs: number;
  errorCode?: string;
  qrcodeImage?: string;
}
export interface WeixinConnectionStatus {
  status: 'PENDING_LINK' | 'STARTING' | 'RUNNING' | 'DISABLED' | 'REAUTH_REQUIRED' | 'DISCONNECTED' | 'FAILED';
  connected: boolean;
  disabled?: boolean;
  accountId?: string | null;
}

const errors: Record<string, string> = {
  weixin_account_in_use: '这个微信连接已绑定其他 Channel，请先在原 Channel 断开连接。',
  weixin_account_mismatch: '请使用原来绑定的微信账号重新授权。更换账号前，请先断开原连接。',
  weixin_already_linked: '微信提示该连接已被绑定，请检查原有连接后重试。',
  weixin_retry_later: '操作过于频繁，请稍后重试。',
  weixin_flow_superseded: '已在其他页面开始新的授权，请重新生成二维码。',
  weixin_flow_not_found: '授权流程已不可用，请重新生成二维码。',
  weixin_channel_not_found: 'Channel 不存在，或你没有访问权限。',
  weixin_authorization_not_ready: '微信授权尚未完成，请先扫码并在手机上确认。',
  weixin_authorization_required: '请先完成微信扫码授权。',
  weixin_invalid_verification_code: '请输入有效的微信验证码。',
  weixin_verification_not_requested: '当前不需要验证码，请刷新授权状态。',
  weixin_provider_unavailable: '暂时无法连接微信，请稍后重试。',
  weixin_invalid_provider_response: '微信返回的授权信息不完整，请重新生成二维码。',
  weixin_scheduler_not_configured: '微信连接服务尚未配置，请联系管理员。',
  weixin_scheduler_unavailable: '微信连接服务暂时不可用，请稍后重试。',
  weixin_storage_unavailable: '暂时无法保存授权状态，请稍后重试。',
  weixin_vault_unavailable: '微信凭据存储不可用，请联系管理员恢复后重试。',
  weixin_already_completed: '此授权已完成，请刷新连接状态。',
};

export function weixinErrorMessage(code?: string): string {
  return (code && errors[code]) || '微信连接操作失败，请重试。';
}

export class WeixinError extends Error {
  constructor(public status: number, public code: string) {
    super(errors[code] || (status === 403 ? '你没有配置此微信连接的权限。' : weixinErrorMessage(code)));
  }
}

async function request<T>(channelId: string, suffix: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
  try {
    return await apiFetch<T>(`/api/channels/${encodeURIComponent(channelId)}/weixin/${suffix}`, {
      method, signal, cache: 'no-store', body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (error) {
    if (error instanceof ApiError) {
      let code = '';
      try { code = JSON.parse(error.body).errorCode || ''; } catch { /* Do not render raw server responses. */ }
      throw new WeixinError(error.status, code);
    }
    throw error;
  }
}

const flowPath = (flowId: string) => `link-flows/${encodeURIComponent(flowId)}`;
export const weixin = {
  status: (channelId: string, signal?: AbortSignal) => request<WeixinConnectionStatus>(channelId, 'status', 'GET', undefined, signal),
  start: (channelId: string, relink = false, signal?: AbortSignal) => request<WeixinLinkFlow>(channelId, relink ? 'relink' : 'link-flows', 'POST', undefined, signal),
  get: (channelId: string, flowId: string, signal?: AbortSignal) => request<WeixinLinkFlow>(channelId, flowPath(flowId), 'GET', undefined, signal),
  poll: (channelId: string, flowId: string, signal?: AbortSignal) => request<WeixinLinkFlow>(channelId, `${flowPath(flowId)}/poll`, 'POST', undefined, signal),
  verify: (channelId: string, flowId: string, verifyCode: string, signal?: AbortSignal) => request<WeixinLinkFlow>(channelId, `${flowPath(flowId)}/verify`, 'POST', { verifyCode }, signal),
  complete: (channelId: string, flowId: string, signal?: AbortSignal) => request<WeixinLinkFlow>(channelId, `${flowPath(flowId)}/complete`, 'POST', undefined, signal),
  cancel: (channelId: string, flowId: string, signal?: AbortSignal) => request<WeixinLinkFlow>(channelId, `${flowPath(flowId)}/cancel`, 'POST', undefined, signal),
  disconnect: (channelId: string, signal?: AbortSignal) => request<WeixinConnectionStatus>(channelId, 'disconnect', 'POST', undefined, signal),
};
