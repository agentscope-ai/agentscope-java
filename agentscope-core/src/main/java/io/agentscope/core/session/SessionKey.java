package io.agentscope.core.session;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Logical identity; storage namespaces remain the responsibility of the workspace backend. */
public record SessionKey(String userId, String agentId, String sessionId) {
    public SessionKey {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(sessionId, "sessionId");
        if (agentId.isBlank() || sessionId.isBlank())
            throw new IllegalArgumentException("Empty session identity");
    }

    public String storagePath() {
        return "agents/"
                + encode(agentId)
                + "/sessions/"
                + encode(userId == null ? "" : userId)
                + "/"
                + encode(sessionId);
    }

    private static String encode(String value) {
        return "s_"
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
