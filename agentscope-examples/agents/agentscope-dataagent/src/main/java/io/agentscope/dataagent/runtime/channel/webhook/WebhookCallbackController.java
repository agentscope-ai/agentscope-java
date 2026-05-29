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
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * HTTP surface for the generic-webhook channel:
 *
 * <ul>
 *   <li>{@code POST /api/webhook/{channelId}/inbound} — verifies HMAC + IP allow-list, then
 *       dispatches via {@link WebhookChannel#ingest(WebhookInboundRequest)}.
 *   <li>{@code GET /api/webhook/{channelId}/outbound/{inboundId}} — long-poll for the reply parked
 *       by a previous {@code replyMode=poll} inbound. Returns {@code 204 No Content} when the
 *       poll timeout elapses, allowing the client to re-poll.
 * </ul>
 *
 * <p>Both endpoints are public — authentication is provided by the per-channel shared secret,
 * not by the global Spring Security filter. {@code SecurityConfig} permits {@code /api/webhook/**}.
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WebhookCallbackController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataAgentBootstrap bootstrap;

    public WebhookCallbackController(DataAgentBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @PostMapping(
            value = "/{channelId}/inbound",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> inbound(
            @PathVariable("channelId") String channelId,
            @RequestHeader(value = "X-DataAgent-Sig", required = false) String signature,
            @RequestBody byte[] rawBody,
            ServerHttpRequest request) {
        WebhookChannel ch = resolveChannel(channelId);
        if (ch == null) {
            return Mono.just(
                    ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("status", "error", "error", "unknown channel")));
        }
        WebhookChannelProperties props = ch.properties();

        if (props.hasIpAllowList()) {
            String remote = clientIp(request);
            if (remote == null || !props.allowedIps().contains(remote)) {
                log.warn(
                        "Webhook channel '{}' rejected inbound from disallowed ip='{}'",
                        channelId,
                        remote);
                return Mono.just(
                        ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("status", "error", "error", "ip not allowed")));
            }
        }

        if (props.requiresSignature()) {
            String expected = WebhookSignature.hmacHex(props.sharedSecret(), rawBody);
            if (!WebhookSignature.constantTimeEquals(expected, signature)) {
                log.warn("Webhook channel '{}' inbound signature mismatch", channelId);
                return Mono.just(
                        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Map.of("status", "error", "error", "bad signature")));
            }
        }

        WebhookInboundRequest body;
        try {
            body = MAPPER.readValue(rawBody, WebhookInboundRequest.class);
        } catch (Exception e) {
            return Mono.just(
                    ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(
                                    Map.of(
                                            "status",
                                            "error",
                                            "error",
                                            "invalid json: " + e.getMessage())));
        }

        return ch.ingest(body)
                .map(
                        result ->
                                ResponseEntity.accepted()
                                        .body(
                                                Map.<String, Object>of(
                                                        "status", result.status(),
                                                        "inboundId", result.inboundId(),
                                                        "replyMode", result.replyMode())));
    }

    @GetMapping(
            value = "/{channelId}/outbound/{inboundId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> outbound(
            @PathVariable("channelId") String channelId,
            @PathVariable("inboundId") String inboundId) {
        WebhookChannel ch = resolveChannel(channelId);
        if (ch == null) {
            return Mono.just(
                    ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("status", "error", "error", "unknown channel")));
        }
        return ch.awaitReply(inboundId)
                .map(
                        reply ->
                                ResponseEntity.ok(
                                        Map.<String, Object>of(
                                                "status", "ok",
                                                "inboundId", inboundId,
                                                "reply", reply)))
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NO_CONTENT).build());
    }

    private WebhookChannel resolveChannel(String channelId) {
        return bootstrap
                .channelManager()
                .getChannel(channelId)
                .filter(c -> c instanceof WebhookChannel)
                .map(c -> (WebhookChannel) c)
                .orElse(null);
    }

    private String clientIp(ServerHttpRequest request) {
        List<String> fwd = request.getHeaders().get("X-Forwarded-For");
        if (fwd != null && !fwd.isEmpty()) {
            String first = fwd.get(0);
            int comma = first.indexOf(',');
            return comma >= 0 ? first.substring(0, comma).trim() : first.trim();
        }
        if (request.getRemoteAddress() == null) return null;
        return request.getRemoteAddress().getAddress().getHostAddress();
    }
}
