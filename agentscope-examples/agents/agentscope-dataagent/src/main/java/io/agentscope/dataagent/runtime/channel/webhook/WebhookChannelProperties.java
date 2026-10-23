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
package io.agentscope.dataagent.runtime.channel.webhook;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for a single generic-webhook channel — the lightweight HTTP-in / HTTP-out
 * side-channel used to invoke a DataAgent from IM / ticketing / CI systems without opening the
 * primary Chat UI.
 *
 * @param sharedSecret HMAC-SHA256 shared secret; if non-blank, every inbound request must carry an
 *     {@code X-DataAgent-Sig} header equal to {@code hex(hmacSha256(secret, rawBody))}. Leave blank
 *     to disable signature checks (only acceptable behind a trusted reverse proxy).
 * @param allowedIps optional allow-list of source IPs; when non-empty, requests from other peers
 *     are rejected with 403 even when the signature is valid. The remote address is read from
 *     {@code X-Forwarded-For} when present, otherwise the direct connection address.
 * @param outboundParkCapacity per-channel ring-buffer capacity for {@code replyMode=poll} requests
 *     awaiting their delivery via {@code GET /api/webhook/{channelId}/outbound/{inboundId}}. When
 *     full, the oldest parked reply is dropped.
 * @param longPollTimeoutMillis how long the poll endpoint waits before returning {@code 204 No
 *     Content} so the client can re-poll.
 */
public record WebhookChannelProperties(
        String sharedSecret,
        List<String> allowedIps,
        int outboundParkCapacity,
        long longPollTimeoutMillis) {

    public static final int DEFAULT_PARK_CAPACITY = 256;
    public static final long DEFAULT_LONG_POLL_MILLIS = 5_000L;

    public WebhookChannelProperties {
        allowedIps = allowedIps != null ? List.copyOf(allowedIps) : List.of();
        if (outboundParkCapacity <= 0) {
            outboundParkCapacity = DEFAULT_PARK_CAPACITY;
        }
        if (longPollTimeoutMillis <= 0) {
            longPollTimeoutMillis = DEFAULT_LONG_POLL_MILLIS;
        }
    }

    /** Reads a {@link WebhookChannelProperties} out of an arbitrary properties map. */
    public static WebhookChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        return new WebhookChannelProperties(
                asString(p, "sharedSecret"),
                asStringList(p, "allowedIps"),
                asInt(p, "outboundParkCapacity", DEFAULT_PARK_CAPACITY),
                asLong(p, "longPollTimeoutMillis", DEFAULT_LONG_POLL_MILLIS));
    }

    /** Whether HMAC signature verification is required. */
    public boolean requiresSignature() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }

    /** Whether the IP allow-list is active. */
    public boolean hasIpAllowList() {
        return !allowedIps.isEmpty();
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of(v.toString());
    }

    private static int asInt(Map<String, Object> p, String key, int fallback) {
        Object v = p.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long asLong(Map<String, Object> p, String key, long fallback) {
        Object v = p.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
