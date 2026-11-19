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
package io.agentscope.extensions.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import java.time.Duration;
import java.util.Objects;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Fetches and caches a WeCom {@code access_token} for one {@code corpid + corpsecret} pair.
 * Tokens are valid for ~7200 s; this provider proactively refreshes at ~80% of TTL so a single
 * worker hot path never sees a forced refresh.
 *
 * <p>The cache defaults to a process-local {@link InMemoryAccessTokenStore}; pass a
 * shared-storage {@link AccessTokenStore} to share tokens across instances.
 */
public final class WeComAccessTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String corpId;
    private final String secret;
    private final AccessTokenStore store;

    public WeComAccessTokenProvider(String apiBase, String corpId, String secret) {
        this(apiBase, corpId, secret, new InMemoryAccessTokenStore());
    }

    /**
     * Constructor variant that lets the application choose where the token is cached — for
     * example a shared-storage {@link AccessTokenStore} so one refresh or invalidation serves
     * all instances.
     *
     * @param store cache for the fetched token; must be thread-safe
     */
    public WeComAccessTokenProvider(
            String apiBase, String corpId, String secret, AccessTokenStore store) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.corpId = corpId;
        this.secret = secret;
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
        return client.get()
                .uri(
                        uri ->
                                uri.path("/cgi-bin/gettoken")
                                        .queryParam("corpid", corpId)
                                        .queryParam("corpsecret", secret)
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .map(this::parseAndStore);
    }

    private String parseAndStore(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            int errcode = node.path("errcode").asInt(0);
            if (errcode != 0) {
                throw new IllegalStateException(
                        "WeCom gettoken failed: errcode="
                                + errcode
                                + ", errmsg="
                                + node.path("errmsg").asText());
            }
            String token = node.path("access_token").asText();
            int expiresIn = node.path("expires_in").asInt(7200);
            long refreshAt = System.currentTimeMillis() + (long) (expiresIn * 800L);
            store.putIfNewer(new CachedAccessToken(token, refreshAt));
            return token;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse WeCom gettoken response: " + e.getMessage(), e);
        }
    }

    /** Forces the next {@link #token()} call to refresh. Useful for tests / error recovery. */
    public void invalidate() {
        store.clear();
    }
}
