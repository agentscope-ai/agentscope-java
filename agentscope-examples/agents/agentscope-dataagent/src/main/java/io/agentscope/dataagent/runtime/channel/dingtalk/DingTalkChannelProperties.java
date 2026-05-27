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
package io.agentscope.dataagent.runtime.channel.dingtalk;

import java.util.Map;
import java.util.Objects;

/**
 * Configuration for a single DingTalk (钉钉) channel instance using the Stream protocol over a
 * persistent WebSocket connection.
 *
 * @param appKey enterprise internal app key
 * @param appSecret enterprise internal app secret
 * @param robotCode robot code used as outbound sender id; required for replies
 * @param apiBase override for the DingTalk OpenAPI base; default
 *     {@code https://api.dingtalk.com}
 * @param oapiBase override for the legacy OAPI base used by {@code gettoken}; default
 *     {@code https://oapi.dingtalk.com}
 * @param streamRegisterUrl override for the Stream gateway registration endpoint; default
 *     {@code https://api.dingtalk.com/v1.0/gateway/connections/open}
 */
public record DingTalkChannelProperties(
        String appKey,
        String appSecret,
        String robotCode,
        String apiBase,
        String oapiBase,
        String streamRegisterUrl) {

    public static final String DEFAULT_API_BASE = "https://api.dingtalk.com";
    public static final String DEFAULT_OAPI_BASE = "https://oapi.dingtalk.com";
    public static final String DEFAULT_STREAM_REGISTER_URL =
            DEFAULT_API_BASE + "/v1.0/gateway/connections/open";

    public DingTalkChannelProperties {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("dingtalk.appKey is required");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("dingtalk.appSecret is required");
        }
        if (robotCode == null || robotCode.isBlank()) {
            throw new IllegalArgumentException("dingtalk.robotCode is required");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = DEFAULT_API_BASE;
        }
        if (oapiBase == null || oapiBase.isBlank()) {
            oapiBase = DEFAULT_OAPI_BASE;
        }
        if (streamRegisterUrl == null || streamRegisterUrl.isBlank()) {
            streamRegisterUrl = DEFAULT_STREAM_REGISTER_URL;
        }
    }

    /** Reads a {@link DingTalkChannelProperties} out of an arbitrary properties map. */
    public static DingTalkChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        return new DingTalkChannelProperties(
                asString(p, "appKey"),
                asString(p, "appSecret"),
                asString(p, "robotCode"),
                asString(p, "apiBase"),
                asString(p, "oapiBase"),
                asString(p, "streamRegisterUrl"));
    }

    private static String asString(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }
}
