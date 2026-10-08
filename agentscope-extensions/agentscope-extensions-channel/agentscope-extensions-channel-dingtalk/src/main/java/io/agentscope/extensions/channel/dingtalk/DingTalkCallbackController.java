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
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Handles inbound bot message delivery (POST) for DingTalk channels running in http callback mode.
 *
 * <p>Each {@link DingTalkChannel} registers itself with the controller at start-up; the controller
 * dispatches incoming requests by {@code channelId} extracted from the URL path. This avoids
 * Spring-side dynamic mapping registration and keeps wiring trivially testable.
 */
@RestController
@RequestMapping("/api/channels/dingtalk")
public class DingTalkCallbackController {

    private static final Logger log = LoggerFactory.getLogger(DingTalkCallbackController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Upper bound on a callback body accepted for parsing; robot messages are a few KB at most. */
    private static final int MAX_BODY_LENGTH = 64 * 1024;

    /** Cap on exception-message snippets carried into logs; Jackson messages embed parsed source. */
    private static final int MAX_SNIPPET_LENGTH = 200;

    private final DingTalkChannelRegistry registry;

    public DingTalkCallbackController() {
        this(DingTalkChannelRegistry.instance());
    }

    /** Visible for tests — allows injecting a fresh registry. */
    DingTalkCallbackController(DingTalkChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Inbound bot message. DingTalk POSTs the bot-message JSON to this endpoint with {@code
     * timestamp}/{@code sign} headers; when the robot is configured with an {@code aesKey} the body
     * is a {@code {"encrypt": ...}} envelope instead. The message is verified, decrypted when
     * needed, and handed to the channel's shared intake pipeline (msgId dedup, mapping, bot-loop
     * guard, dispatch). Always returns {@code 200} so DingTalk does not retry, unless the signature
     * is invalid, the body is unusable, or the channel is unknown.
     */
    @PostMapping(
            value = "/{channelId}/callback",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<String>> dispatch(
            @PathVariable String channelId,
            @RequestHeader(value = "timestamp", required = false) String timestamp,
            @RequestHeader(value = "sign", required = false) String sign,
            @RequestBody String body) {
        DingTalkChannel channel = registry.get(channelId);
        if (channel == null) {
            // An unauthenticated probe must not distinguish an unknown channel id from a bad
            // signature, so both answer 401; the detail is kept out of the warn stream.
            log.debug("DingTalk dispatch: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        if (isBlank(timestamp) || isBlank(sign) || !channel.crypto().verify(timestamp, sign)) {
            log.warn(
                    "DingTalk dispatch: signature verification failed (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        JsonNode payload;
        try {
            payload = extractPayload(channel, body);
        } catch (RuntimeException e) {
            log.warn(
                    "DingTalk dispatch: failed to extract payload (channelId='{}'): {}",
                    channelId,
                    truncate(e.getMessage()));
            return Mono.just(ResponseEntity.badRequest().body(""));
        }
        // The intake pipeline acks benign cases internally (duplicates, non-text payloads, loop
        // guard); the reply itself is delivered through the outbound API, not this response.
        channel.onInboundPayload(payload);
        return Mono.just(ResponseEntity.ok(""));
    }

    private static JsonNode extractPayload(DingTalkChannel channel, String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Request body is empty");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalStateException(
                    "Request body exceeds " + MAX_BODY_LENGTH + " characters");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Request body is not valid JSON: " + e.getMessage(), e);
        }
        JsonNode encrypt = root.get("encrypt");
        boolean hasEnvelope = encrypt != null && encrypt.isTextual() && !encrypt.asText().isBlank();
        // Fail closed: a channel configured with an aesKey only ever receives envelopes from
        // DingTalk, so a plain body indicates a mismatched or forged request, not a message to
        // dispatch. The reverse mismatch (envelope without aesKey) fails inside decrypt().
        if (channel.crypto().hasAesKey() && !hasEnvelope) {
            throw new IllegalStateException(
                    "Channel expects an encrypted callback body but none was present");
        }
        if (!hasEnvelope) {
            return root;
        }
        String plain = channel.crypto().decrypt(encrypt.asText());
        try {
            return MAPPER.readTree(plain);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Decrypted payload is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_SNIPPET_LENGTH
                ? message
                : message.substring(0, MAX_SNIPPET_LENGTH) + "...(" + message.length() + " chars)";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
