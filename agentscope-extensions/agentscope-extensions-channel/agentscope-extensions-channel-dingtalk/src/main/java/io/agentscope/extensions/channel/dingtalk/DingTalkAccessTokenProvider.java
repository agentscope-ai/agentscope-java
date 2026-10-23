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
package io.agentscope.extensions.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Fetches and caches a DingTalk OpenAPI {@code accessToken} for one {@code appKey + appSecret}
 * pair. Tokens are valid for ~7200 s; this provider proactively refreshes at ~80% of TTL.
 *
 * <p>Uses the new OpenAPI endpoint {@code POST /v1.0/oauth2/accessToken}. The legacy
 * {@code /gettoken} endpoint at {@code oapi.dingtalk.com} is not used.
 *
 * <p>The cache defaults to a process-local {@link InMemoryAccessTokenStore}; pass a
 * shared-storage {@link AccessTokenStore} to share tokens across instances.
 */
public final class DingTalkAccessTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String appKey;
    private final String appSecret;
    private final AccessTokenStore store;

    public DingTalkAccessTokenProvider(String apiBase, String appKey, String appSecret) {
        this(apiBase, appKey, appSecret, new InMemoryAccessTokenStore());
    }

    /**
     * Constructor variant that lets the application choose where the token is cached — for
     * example a shared-storage {@link AccessTokenStore} so one refresh or invalidation serves
     * all instances.
     *
     * @param store cache for the fetched token; must be thread-safe
     */
    public DingTalkAccessTokenProvider(
            String apiBase, String appKey, String appSecret, AccessTokenStore store) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Returns a valid access token, refreshing in-band when the cached one is missing or close to
     * expiring.
     */
    public Mono<String> token() {
        CachedAccessToken cached = store.get();
        if (cached != null && cached.refreshAtMs() > System.currentTimeMillis()) {
            return Mono.just(cached.value());
        }
        return refresh();
    }

    private Mono<String> refresh() {
        Map<String, Object> body = Map.of("appKey", appKey, "appSecret", appSecret);
        return client.post()
                .uri("/v1.0/oauth2/accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .map(this::parseAndStore);
    }

    private String parseAndStore(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            String token = node.path("accessToken").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                        "DingTalk accessToken response missing accessToken: " + body);
            }
            int expiresIn = node.path("expireIn").asInt(7200);
            long refreshAt = System.currentTimeMillis() + (long) (expiresIn * 800L);
            store.putIfNewer(new CachedAccessToken(token, refreshAt));
            return token;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse DingTalk accessToken response: " + e.getMessage(), e);
        }
    }

    /** Forces the next {@link #token()} call to refresh. */
    public void invalidate() {
        store.clear();
    }
}
