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
package io.agentscope.claw2.runtime.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Fetches and caches a DingTalk OpenAPI {@code accessToken} for one {@code appKey + appSecret}
 * pair. Tokens are valid for ~7200 s; this provider proactively refreshes at ~80% of TTL.
 *
 * <p>Uses the new OpenAPI endpoint {@code POST /v1.0/oauth2/accessToken}. The legacy
 * {@code /gettoken} endpoint at {@code oapi.dingtalk.com} is not used.
 */
public final class DingTalkAccessTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String appKey;
    private final String appSecret;
    private final AtomicReference<TokenSlot> slot = new AtomicReference<>(TokenSlot.EMPTY);

    public DingTalkAccessTokenProvider(String apiBase, String appKey, String appSecret) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    /**
     * Returns a valid access token, refreshing in-band when the cached one is missing or close to
     * expiring.
     */
    public Mono<String> token() {
        TokenSlot s = slot.get();
        long now = System.currentTimeMillis();
        if (s.value != null && s.refreshAtMs > now) {
            return Mono.just(s.value);
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
            slot.set(new TokenSlot(token, refreshAt));
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
        slot.set(TokenSlot.EMPTY);
    }

    private record TokenSlot(String value, long refreshAtMs) {
        static final TokenSlot EMPTY = new TokenSlot(null, 0L);
    }
}
