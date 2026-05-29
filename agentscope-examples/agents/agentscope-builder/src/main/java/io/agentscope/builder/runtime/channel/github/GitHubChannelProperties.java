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
package io.agentscope.builder.runtime.channel.github;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-specific configuration for a single GitHub channel instance, sourced from the
 * {@code properties} block of {@code channels.<id>} in {@code agentscope.json}.
 *
 * @param token Personal Access Token (PAT, fine-grained or classic) used for the REST API outbound
 *     and bot-identity lookup. Required.
 * @param webhookSecret shared secret configured on the webhook; used to verify
 *     {@code X-Hub-Signature-256}. Required.
 * @param apiBase override for the GitHub REST API base; defaults to
 *     {@code https://api.github.com} (set to {@code https://github.your-corp.com/api/v3} for
 *     GitHub Enterprise Server)
 * @param webhookPath HTTP path Spring exposes the webhook under; defaults to
 *     {@code /api/channels/github/{channelId}/webhook}
 * @param botUserLogin optional override for the bot account's login (e.g. {@code my-bot}). When
 *     unset, the channel resolves the login automatically via {@code GET /user} at startup.
 */
public record GitHubChannelProperties(
        String token,
        String webhookSecret,
        String apiBase,
        String webhookPath,
        String botUserLogin) {

    public GitHubChannelProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("github.token is required");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("github.webhookSecret is required");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.github.com";
        }
    }

    /** Reads a {@link GitHubChannelProperties} from an arbitrary properties map. */
    public static GitHubChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String defaultPath = "/api/channels/github/" + channelId + "/webhook";
        return new GitHubChannelProperties(
                asString(p, "token"),
                asString(p, "webhookSecret"),
                asStringOr(p, "apiBase", null),
                asStringOr(p, "webhookPath", defaultPath),
                asStringOr(p, "botUserLogin", null));
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
