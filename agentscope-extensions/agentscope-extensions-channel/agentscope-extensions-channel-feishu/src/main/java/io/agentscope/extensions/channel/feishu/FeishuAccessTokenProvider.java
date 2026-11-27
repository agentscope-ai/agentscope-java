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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.CachedAccessToken;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Fetches and caches a Feishu {@code tenant_access_token} for one {@code app_id + app_secret}
 * pair. Tokens are valid for ~7200 s; this provider proactively refreshes at ~80% of TTL.
 *
 * <p>The cache defaults to a process-local {@link InMemoryAccessTokenStore}; pass a
 * shared-storage {@link AccessTokenStore} to share tokens across instances.
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal">tenant_access_token (internal)</a>
 */
public final class FeishuAccessTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient client;
    private final String appId;
    private final String appSecret;
    private final AccessTokenStore store;
    private final Duration responseTimeout;

    public FeishuAccessTokenProvider(String apiBase, String appId, String appSecret) {
        this(apiBase, appId, appSecret, new InMemoryAccessTokenStore(), DEFAULT_RESPONSE_TIMEOUT);
    }

    /**
     * Constructor variant that lets the application choose where the token is cached — for
     * example a shared-storage {@link AccessTokenStore} so one refresh or invalidation serves
     * all instances.
     *
     * @param store cache for the fetched token; must be thread-safe
     */
    public FeishuAccessTokenProvider(
            String apiBase, String appId, String appSecret, AccessTokenStore store) {
        this(apiBase, appId, appSecret, store, DEFAULT_RESPONSE_TIMEOUT);
    }

    /**
     * Constructor variant with an explicit bound for one token request. Production keeps the
     * 10s default; tests against a loopback server on starved CI runners pass a longer bound.
     *
     * @param responseTimeout positive bound for one token request
     */
    public FeishuAccessTokenProvider(
            String apiBase, String appId, String appSecret, Duration responseTimeout) {
        this(apiBase, appId, appSecret, new InMemoryAccessTokenStore(), responseTimeout);
    }

    /**
     * @param responseTimeout positive bound for one token request
     */
    public FeishuAccessTokenProvider(
            String apiBase,
            String appId,
            String appSecret,
            AccessTokenStore store,
            Duration responseTimeout) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.appId = appId;
        this.appSecret = appSecret;
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(responseTimeout, "responseTimeout");
        if (responseTimeout.isZero() || responseTimeout.isNegative())
            throw new IllegalArgumentException("responseTimeout must be positive");
        this.responseTimeout = responseTimeout;
    }

    /** Returns a valid tenant_access_token, refreshing in-band when missing or near-expiry. */
    public Mono<String> token() {
        CachedAccessToken cached = store.get();
        if (cached != null && cached.refreshAtMs() > System.currentTimeMillis()) {
            return Mono.just(cached.value());
        }
        return refresh();
    }

    private Mono<String> refresh() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        return client.post()
                .uri("/open-apis/auth/v3/tenant_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(responseTimeout)
                .map(this::parseAndStore);
    }

    private String parseAndStore(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            int code = node.path("code").asInt(0);
            if (code != 0) {
                throw new IllegalStateException(
                        "Feishu tenant_access_token failed: code="
                                + code
                                + ", msg="
                                + node.path("msg").asText());
            }
            String token = node.path("tenant_access_token").asText();
            int expiresIn = node.path("expire").asInt(7200);
            long refreshAt = System.currentTimeMillis() + (long) (expiresIn * 800L);
            store.putIfNewer(new CachedAccessToken(token, refreshAt));
            return token;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse Feishu tenant_access_token response: " + e.getMessage(), e);
        }
    }

    /** Forces the next {@link #token()} call to refresh. Useful for tests / error recovery. */
    public void invalidate() {
        store.clear();
    }
}
