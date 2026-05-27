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
package io.agentscope.claw2.runtime.channel.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Resolves the bot's GitHub user id (and login) for the configured PAT by calling
 * {@code GET /user}. Used for bot-loop self-detection in the webhook handler — when a
 * webhook delivers a comment authored by the bot itself, the channel must drop it instead of
 * looping forever.
 *
 * <p>The lookup is best-effort: a network failure does not block startup. The resolved id is
 * cached for the lifetime of the channel and accessed via {@link #botUserId()}.
 */
public final class GitHubBotIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(GitHubBotIdentityResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String token;
    private final AtomicReference<Identity> identity = new AtomicReference<>(Identity.UNKNOWN);

    public GitHubBotIdentityResolver(String apiBase, String token, String botLoginOverride) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.token = token;
        if (botLoginOverride != null && !botLoginOverride.isBlank()) {
            this.identity.set(new Identity(-1L, botLoginOverride));
        }
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
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .header(HttpHeaders.USER_AGENT, "agentscope-claw")
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
            String login = node.path("login").asText(null);
            if (id > 0 && login != null) {
                identity.set(new Identity(id, login));
                log.info("GitHub bot identity resolved: id={}, login={}", id, login);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve GitHub bot identity: {}", e.getMessage());
        }
    }

    /** Returns the bot's numeric user id, or {@code -1} if unresolved. */
    public long botUserId() {
        return identity.get().id;
    }

    /** Returns the bot's login (handle), or null if unresolved. */
    public String botLogin() {
        return identity.get().login;
    }

    private record Identity(long id, String login) {
        static final Identity UNKNOWN = new Identity(-1L, null);
    }
}
