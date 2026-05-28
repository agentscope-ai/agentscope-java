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
package io.agentscope.builder.runtime.channel.wecom;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-specific configuration for a single WeCom (企业微信) channel instance, sourced from the
 * {@code properties} block of {@code channels.<id>} in {@code agentscope.json}.
 *
 * <p>The environment-variable-resolved values (e.g. {@code ${WECOM_CORP_ID}}) are expected to be
 * substituted by the caller before construction.
 *
 * @param corpId WeCom corporation id (cn: 企业 ID)
 * @param agentId self-built application id (numeric, but kept as int for the send API)
 * @param secret application secret
 * @param token callback token (configured in the WeCom admin console)
 * @param encodingAesKey 43-character base64 (no padding) AES key for callback encryption
 * @param callbackPath the HTTP path Spring exposes the callback under; defaults to
 *     {@code /api/channels/wecom/{channelId}/callback}
 * @param apiBase override for the WeCom API base URL; default
 *     {@code https://qyapi.weixin.qq.com}
 */
public record WeComChannelProperties(
        String corpId,
        int agentId,
        String secret,
        String token,
        String encodingAesKey,
        String callbackPath,
        String apiBase) {

    public WeComChannelProperties {
        if (corpId == null || corpId.isBlank()) {
            throw new IllegalArgumentException("wecom.corpId is required");
        }
        if (agentId <= 0) {
            throw new IllegalArgumentException("wecom.agentId must be a positive integer");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("wecom.secret is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("wecom.token is required");
        }
        if (encodingAesKey == null || encodingAesKey.length() != 43) {
            throw new IllegalArgumentException("wecom.encodingAesKey must be 43 characters");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://qyapi.weixin.qq.com";
        }
    }

    /** Reads a {@link WeComChannelProperties} out of an arbitrary properties map. */
    public static WeComChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String defaultCallback = "/api/channels/wecom/" + channelId + "/callback";
        return new WeComChannelProperties(
                asString(p, "corpId"),
                asInt(p, "agentId"),
                asString(p, "secret"),
                asString(p, "token"),
                asString(p, "encodingAesKey"),
                asStringOr(p, "callbackPath", defaultCallback),
                asStringOr(p, "apiBase", null));
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static String asStringOr(Map<String, Object> p, String key, String fallback) {
        Object v = p.get(key);
        return v == null ? fallback : v.toString();
    }

    private static int asInt(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "wecom." + key + " must be an integer, got: " + v, e);
        }
    }
}
