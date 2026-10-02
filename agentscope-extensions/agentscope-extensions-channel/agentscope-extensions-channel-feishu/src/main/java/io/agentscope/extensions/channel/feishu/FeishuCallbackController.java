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
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * Handles the Feishu URL verification handshake and event-subscription callbacks.
 *
 * <p>Single endpoint at {@code POST /api/channels/feishu/{channelId}/callback}. The body is JSON;
 * either a plain envelope or {@code {"encrypt":"<base64>"}}. The controller:
 *
 * <ol>
 *   <li>Decrypts the envelope when {@code encryptKey} is configured
 *   <li>Verifies the {@code X-Lark-Signature} header when present
 *   <li>Echoes the URL verification {@code challenge} on the verification handshake
 *   <li>Deduplicates by {@code header.event_id}
 *   <li>Applies the bot-loop guard and dispatches to the channel
 * </ol>
 */
@RestController
@RequestMapping("/api/channels/feishu")
public class FeishuCallbackController {

    private static final Logger log = LoggerFactory.getLogger(FeishuCallbackController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FeishuChannelRegistry registry;

    public FeishuCallbackController() {
        this(FeishuChannelRegistry.instance());
    }

    /** Visible for tests — allows injecting a fresh registry. */
    FeishuCallbackController(FeishuChannelRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @PostMapping(
            value = "/{channelId}/callback",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> callback(
            @PathVariable String channelId,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestBody String rawBody) {
        FeishuChannel channel = registry.get(channelId);
        if (channel == null) {
            log.warn("Feishu callback: no channel registered for id='{}'", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }

        // 1. Optional signature verification on the raw body. When the channel is encrypted and a
        //    signature is supplied, the signature header MUST match.
        if (channel.properties().isEncrypted()
                && signature != null
                && !channel.crypto().verifySignature(signature, timestamp, nonce, rawBody)) {
            log.warn("Feishu callback: signature mismatch (channelId='{}')", channelId);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        // 2. Parse outer body; if it's encrypted, decrypt and re-parse.
        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(rawBody);
            if (envelope.has("encrypt") && channel.crypto() != null) {
                String plaintext = channel.crypto().decrypt(envelope.get("encrypt").asText());
                envelope = MAPPER.readTree(plaintext);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "Feishu callback: decrypt/parse failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        } catch (Exception e) {
            log.warn(
                    "Feishu callback: parse failed (channelId='{}'): {}",
                    channelId,
                    e.getMessage());
            return Mono.just(ResponseEntity.badRequest().body(""));
        }

        // 3. URL verification handshake — echo {"challenge":"…"}.
        Optional<String> challenge = FeishuInboundMapper.extractUrlChallenge(envelope);
        if (challenge.isPresent()) {
            String tokenFromBody = envelope.path("token").asText(null);
            String configured = channel.properties().verificationToken();
            if (configured != null && !configured.isBlank() && !configured.equals(tokenFromBody)) {
                log.warn(
                        "Feishu callback: verification token mismatch (channelId='{}')", channelId);
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }
            try {
                String body = MAPPER.writeValueAsString(Map.of("challenge", challenge.get()));
                return Mono.just(ResponseEntity.ok(body));
            } catch (Exception e) {
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(""));
            }
        }

        // 4. Idempotency: drop duplicate event_id silently with 200 so Feishu stops retrying.
        Optional<String> eventId = FeishuInboundMapper.extractEventId(envelope);
        if (eventId.isPresent()
                && !channel.idempotency().firstSeen(channelId + "|" + eventId.get())) {
            log.debug(
                    "Feishu callback: duplicate event_id={} (channelId='{}')",
                    eventId.get(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 5. Map to InboundMessage; non-text or malformed -> ack silently.
        Optional<InboundMessage> inbound = channel.mapper().map(envelope);
        if (inbound.isEmpty()) {
            return Mono.just(ResponseEntity.ok("{}"));
        }
        InboundMessage in = inbound.get();

        // 6. Bot-loop guard on the conversation peer key.
        if (!channel.botLoopGuard().allow(in.peer().key())) {
            log.warn(
                    "Feishu callback: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return Mono.just(ResponseEntity.ok("{}"));
        }

        // 7. Dispatch on the channel; channel handles outbound delivery.
        return channel.dispatch(in)
                .then(Mono.just(ResponseEntity.ok("{}")))
                .onErrorResume(
                        err -> {
                            log.warn(
                                    "Feishu callback: agent run failed (channelId='{}'): {}",
                                    channelId,
                                    err.getMessage());
                            return Mono.just(ResponseEntity.ok("{}"));
                        });
    }
}
