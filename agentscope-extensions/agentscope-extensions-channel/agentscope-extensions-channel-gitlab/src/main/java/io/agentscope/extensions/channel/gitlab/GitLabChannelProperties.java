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
package io.agentscope.extensions.channel.gitlab;

import java.util.Map;
import java.util.Objects;

/**
 * Provider-specific configuration for a single GitLab channel instance, sourced from the
 * {@code properties} block of {@code channels.<id>} in {@code agentscope.json}.
 *
 * @param token Project / Group / Personal Access Token used for the REST API outbound and
 *     bot-identity lookup. Required, with {@code api} scope.
 * @param webhookToken shared token configured on the project webhook; matched against
 *     {@code X-Gitlab-Token} via constant-time equality. Required.
 * @param apiBase override for the GitLab REST API base; defaults to
 *     {@code https://gitlab.com/api/v4}.
 * @param webhookPath HTTP path Spring exposes the webhook under; defaults to
 *     {@code /api/channels/gitlab/{channelId}/webhook}
 */
public record GitLabChannelProperties(
        String token, String webhookToken, String apiBase, String webhookPath) {

    public GitLabChannelProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("gitlab.token is required");
        }
        if (webhookToken == null || webhookToken.isBlank()) {
            throw new IllegalArgumentException("gitlab.webhookToken is required");
        }
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://gitlab.com/api/v4";
        }
        // Strip a trailing /api/v4 if the caller supplied the bare host — we always append the
        // path ourselves. Keeping the canonical form means /api/v4 must be present.
        if (!apiBase.endsWith("/api/v4") && !apiBase.contains("/api/v")) {
            apiBase = apiBase.replaceAll("/+$", "") + "/api/v4";
        }
    }

    /** Reads a {@link GitLabChannelProperties} from an arbitrary properties map. */
    public static GitLabChannelProperties from(String channelId, Map<String, Object> props) {
        Objects.requireNonNull(channelId, "channelId");
        Map<String, Object> p = props != null ? props : Map.of();
        String defaultPath = "/api/channels/gitlab/" + channelId + "/webhook";
        return new GitLabChannelProperties(
                asString(p, "token"),
                asString(p, "webhookToken"),
                asStringOr(p, "apiBase", null),
                asStringOr(p, "webhookPath", defaultPath));
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
