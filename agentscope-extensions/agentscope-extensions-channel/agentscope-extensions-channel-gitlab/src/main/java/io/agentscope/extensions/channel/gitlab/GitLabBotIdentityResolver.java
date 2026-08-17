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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Resolves the bot's GitLab user id (and username) for the configured token by calling
 * {@code GET /user}. Used for bot-loop self-detection — when a Note Hook is fired by the bot
 * itself, the channel must drop it to avoid infinite loops.
 */
public final class GitLabBotIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(GitLabBotIdentityResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String token;
    private final AtomicReference<Identity> identity = new AtomicReference<>(Identity.UNKNOWN);

    public GitLabBotIdentityResolver(String apiBase, String token) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.token = token;
    }

    /**
     * Synchronously attempts to resolve {@code GET /user} and cache the response. Errors are
     * logged at WARN; startup continues.
     */
    public void refresh() {
        try {
            String body =
                    client.get()
                            .uri("/user")
                            .header("PRIVATE-TOKEN", token)
                            .accept(MediaType.APPLICATION_JSON)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(10))
                            .block();
            if (body == null) {
                return;
            }
            JsonNode node = MAPPER.readTree(body);
            long id = node.path("id").asLong(-1);
            String username = node.path("username").asText(null);
            if (id > 0 && username != null) {
                identity.set(new Identity(id, username));
                log.info("GitLab bot identity resolved: id={}, username={}", id, username);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve GitLab bot identity: {}", e.getMessage());
        }
    }

    /** Returns the bot's numeric user id, or {@code -1} if unresolved. */
    public long botUserId() {
        return identity.get().id;
    }

    /** Returns the bot's username, or null if unresolved. */
    public String botUsername() {
        return identity.get().username;
    }

    private record Identity(long id, String username) {
        static final Identity UNKNOWN = new Identity(-1L, null);
    }
}
