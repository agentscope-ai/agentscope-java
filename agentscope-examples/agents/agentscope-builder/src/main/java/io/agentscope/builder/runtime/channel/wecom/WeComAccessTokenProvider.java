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
package io.agentscope.builder.runtime.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Fetches and caches a WeCom {@code access_token} for one {@code corpid + corpsecret} pair.
 * Tokens are valid for ~7200 s; this provider proactively refreshes at ~80% of TTL so a single
 * worker hot path never sees a forced refresh.
 */
public final class WeComAccessTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient client;
    private final String corpId;
    private final String secret;
    private final AtomicReference<TokenSlot> slot = new AtomicReference<>(TokenSlot.EMPTY);

    public WeComAccessTokenProvider(String apiBase, String corpId, String secret) {
        this.client = WebClient.builder().baseUrl(apiBase).build();
        this.corpId = corpId;
        this.secret = secret;
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
            slot.set(new TokenSlot(token, refreshAt));
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
        slot.set(TokenSlot.EMPTY);
    }

    private record TokenSlot(String value, long refreshAtMs) {
        static final TokenSlot EMPTY = new TokenSlot(null, 0L);
    }
}
