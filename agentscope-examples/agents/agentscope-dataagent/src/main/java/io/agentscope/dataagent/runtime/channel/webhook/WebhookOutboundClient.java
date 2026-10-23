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
package io.agentscope.dataagent.runtime.channel.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Posts agent replies to a caller-supplied {@code callbackUrl} for webhook channels operating in
 * {@code replyMode=callback}.
 *
 * <p>Each request body is the JSON object
 * <pre>{"channelId":"...","inboundId":"...","reply":"..."}</pre>
 * and is signed with the same shared secret as the inbound side — the signature is delivered in
 * {@code X-DataAgent-Sig} as the lower-case hex digest of HMAC-SHA256 over the raw body.
 */
public final class WebhookOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboundClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String channelId;
    private final String sharedSecret;
    private final HttpClient http;

    public WebhookOutboundClient(String channelId, String sharedSecret) {
        this.channelId = channelId;
        this.sharedSecret = sharedSecret;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /**
     * Posts the given reply text to the callback URL. Returns an empty Mono on success; logs and
     * swallows failures so a downstream callback outage cannot fail the dispatch pipeline.
     */
    public Mono<Void> deliver(String callbackUrl, String inboundId, String replyText) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Mono.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channelId", channelId);
        body.put("inboundId", inboundId);
        body.put("reply", replyText == null ? "" : replyText);

        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException("Failed to serialise callback body", e));
        }

        HttpRequest.Builder rb =
                HttpRequest.newBuilder(URI.create(callbackUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (sharedSecret != null && !sharedSecret.isBlank()) {
            rb.header("X-DataAgent-Sig", WebhookSignature.hmacHex(sharedSecret, payload));
        }
        HttpRequest req = rb.build();

        return Mono.fromCallable(() -> http.send(req, HttpResponse.BodyHandlers.discarding()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(
                        resp -> {
                            if (resp.statusCode() >= 300) {
                                log.warn(
                                        "Webhook channel '{}' callback to {} returned {}",
                                        channelId,
                                        callbackUrl,
                                        resp.statusCode());
                            }
                        })
                .doOnError(
                        err ->
                                log.warn(
                                        "Webhook channel '{}' callback to {} failed: {}",
                                        channelId,
                                        callbackUrl,
                                        err.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /** Extracts the user-visible text from a list of agent replies, newline-joined. */
    public static String renderReplyText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Msg m : messages) {
            String t = m.getTextContent();
            if (t == null || t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }
}
