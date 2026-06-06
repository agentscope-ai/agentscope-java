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
package io.agentscope.harness.coding.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Fetches and caches a Feishu {@code tenant_access_token} for one {@code app_id + app_secret}
 * pair. Tokens are valid for ~7200 s; this provider proactively refreshes at ~80% of TTL.
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/authentication-management/access-token/tenant_access_token_internal">tenant_access_token (internal)</a>
 */
public final class FeishuAccessTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String appId;
    private final String appSecret;
    private final AtomicReference<TokenSlot> slot = new AtomicReference<>(TokenSlot.EMPTY);

    public FeishuAccessTokenProvider(String apiBase, String appId, String appSecret) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.appId = appId;
        this.appSecret = appSecret;
    }

    /** Returns a valid tenant_access_token, refreshing in-band when missing or near-expiry. */
    public Mono<String> token() {
        TokenSlot s = slot.get();
        long now = System.currentTimeMillis();
        if (s.value != null && s.refreshAtMs > now) {
            return Mono.just(s.value);
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
                .timeout(Duration.ofSeconds(10))
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
            slot.set(new TokenSlot(token, refreshAt));
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
        slot.set(TokenSlot.EMPTY);
    }

    private record TokenSlot(String value, long refreshAtMs) {
        static final TokenSlot EMPTY = new TokenSlot(null, 0L);
    }
}
