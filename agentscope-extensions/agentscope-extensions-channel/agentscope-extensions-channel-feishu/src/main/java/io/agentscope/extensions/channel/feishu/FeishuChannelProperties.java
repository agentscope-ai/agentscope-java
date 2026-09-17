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
package io.agentscope.extensions.channel.feishu;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-specific configuration for a single Feishu (飞书 / Lark) channel instance, sourced from
 * the {@code properties} block of {@code channels.<id>} in {@code agentscope.json}. In a
 * multi-tenant deployment the same record is the unit a {@link FeishuCredentialResolver} returns
 * per tenant key.
 *
 * @param appId Feishu custom-app id (cli_xxx)
 * @param appSecret Feishu custom-app secret
 * @param encryptKey optional Encrypt Key configured in the developer console; when non-blank the
 *     callback body is AES-256-CBC encrypted and must be decrypted before parsing
 * @param verificationToken optional Verification Token configured in the developer console; when
 *     non-blank the {@code token} field of the URL verification challenge is matched against it
 * @param callbackPath HTTP path Spring exposes the callback under; defaults to
 *     {@code /api/channels/feishu/{channelId}/callback}. Unused when the record comes from a
 *     credential resolver — the callback route is fixed and the tenant key is carried in the path.
 * @param apiBase override for the Feishu Open API base URL; default {@code https://open.feishu.cn}
 */
public record FeishuChannelProperties(
        String appId,
        String appSecret,
        String encryptKey,
        String verificationToken,
        String callbackPath,
        String apiBase) {

    public FeishuChannelProperties {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("feishu.appId is required");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("feishu.appSecret is required");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://open.feishu.cn";
        }
    }

    /** Reads a {@link FeishuChannelProperties} from an arbitrary properties map. */
    public static FeishuChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String defaultCallback = "/api/channels/feishu/" + channelId + "/callback";
        return new FeishuChannelProperties(
                asString(p, "appId"),
                asString(p, "appSecret"),
                asStringOr(p, "encryptKey", null),
                asStringOr(p, "verificationToken", null),
                asStringOr(p, "callbackPath", defaultCallback),
                asStringOr(p, "apiBase", null));
    }

    /** Whether AES-256-CBC payload decryption is enabled for this channel. */
    public boolean isEncrypted() {
        return encryptKey != null && !encryptKey.isBlank();
    }

    /**
     * Masks the credential fields. A resolver result reaches application and framework logging —
     * inside an {@code Optional}, an exception message, a controller advice logger — where the
     * generated rendering would print them in full. {@code appId} and the routing fields stay
     * visible, since they are what makes a log line locatable.
     *
     * <p>The rendering is written out by hand rather than derived from the record components, so a
     * field added here must be added below too: otherwise it is absent from every log line instead
     * of visibly masked.
     */
    @Override
    public String toString() {
        return "FeishuChannelProperties[appId="
                + appId
                + ", appSecret=***, encryptKey=***, verificationToken=***, callbackPath="
                + callbackPath
                + ", apiBase="
                + apiBase
                + "]";
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static String asStringOr(Map<String, Object> p, String key, String fallback) {
        Object v = p.get(key);
        return v == null ? fallback : v.toString();
    }
}
