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
package io.agentscope.extensions.channel.weixin;

import java.util.Map;
import java.util.Objects;

/**
 * Provider configuration for one Personal Weixin Channel instance.
 *
 * <p>{@code requestTimeoutMs} bounds the control calls, while {@code longPollTimeoutMs} bounds the
 * {@code getupdates} long poll — the provider holds that request open, so the client derives a
 * longer deadline for it (see {@code WeixinOutboundClient.timeoutFor}).
 */
public record WeixinChannelProperties(
        String accountId,
        String baseUrl,
        String ilinkUserId,
        String channelVersion,
        String botAgent,
        int longPollTimeoutMs,
        int requestTimeoutMs,
        int maxBackoffMs,
        int leaseMs,
        int dispatchTimeoutMs) {
    public static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    public WeixinChannelProperties {
        if (accountId == null || accountId.isBlank())
            throw new IllegalArgumentException("weixin.accountId is required");
        baseUrl =
                WeixinEndpointPolicy.normalizeBaseUrl(baseUrl == null ? DEFAULT_BASE_URL : baseUrl);
        if (longPollTimeoutMs <= 0) longPollTimeoutMs = 35000;
        if (requestTimeoutMs <= 0) requestTimeoutMs = 45000;
        if (maxBackoffMs <= 0) maxBackoffMs = 60000;
        if (leaseMs <= 0) leaseMs = 90000;
        if (dispatchTimeoutMs <= 0) dispatchTimeoutMs = 120000;
    }

    public static WeixinChannelProperties from(String channelId, Map<String, Object> raw) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = raw == null ? Map.of() : raw;
        return new WeixinChannelProperties(
                string(p, "accountId", channelId),
                string(p, "baseUrl", null),
                string(p, "ilinkUserId", null),
                string(p, "channelVersion", "agentscope-weixin/1.0"),
                string(p, "botAgent", "AgentScope"),
                integer(p, "longPollTimeoutMs", 35000),
                integer(p, "requestTimeoutMs", 45000),
                integer(p, "maxBackoffMs", 60000),
                integer(p, "leaseMs", 90000),
                integer(p, "dispatchTimeoutMs", 120000));
    }

    private static String string(Map<String, Object> p, String k, String d) {
        Object v = p.get(k);
        return v == null ? d : v.toString();
    }

    private static int integer(Map<String, Object> p, String k, int d) {
        Object v = p.get(k);
        if (v == null) return d;
        try {
            return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("weixin." + k + " must be an integer", e);
        }
    }
}
