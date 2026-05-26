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
package io.agentscope.builder.runtime.outbound;

import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * HTTP entry point for agent-initiated / external outbound messages.
 *
 * <pre>
 * POST /api/outbound/send
 * { "channelId": "dingtalk-prod", "peerKind": "DIRECT", "peerId": "staff_xyz",
 *   "agentId": "support", "markdown": "..." }
 * </pre>
 *
 * <p>When {@code agentId} is set on the payload, the caller must hold at least {@link Tier#RUN}
 * on that agent — otherwise the request is rejected with 403 before reaching the channel.
 * {@link OutboundService} performs a second, channel-routing-based check that prevents one
 * agent from posting through a binding owned by a different agent.
 */
@RestController
@RequestMapping("/api/outbound")
public class OutboundController {

    private final OutboundService outboundService;
    private final AgentAccessGuard guard;

    public OutboundController(BuilderBootstrap bootstrap, AgentAccessGuard guard) {
        this.outboundService = new OutboundService(bootstrap.channelManager());
        this.guard = guard;
    }

    @PostMapping("/send")
    public Mono<ResponseEntity<Map<String, Object>>> send(
            @RequestBody OutboundRequest req, Authentication auth) {
        return Mono.fromRunnable(
                        () -> {
                            String agentId = req != null ? req.agentId() : null;
                            if (agentId != null && !agentId.isBlank()) {
                                String userId = (String) auth.getPrincipal();
                                guard.require(userId, agentId, Tier.RUN);
                            }
                            outboundService.send(req);
                        })
                .thenReturn(ResponseEntity.ok(Map.<String, Object>of("status", "ok")))
                .onErrorResume(
                        ResponseStatusException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(e.getStatusCode())
                                                .body(
                                                        Map.of(
                                                                "status",
                                                                "error",
                                                                "error",
                                                                e.getReason() == null
                                                                        ? e.getMessage()
                                                                        : e.getReason()))))
                .onErrorResume(
                        IllegalArgumentException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                                .body(
                                                        Map.of(
                                                                "status",
                                                                "error",
                                                                "error",
                                                                e.getMessage()))))
                .onErrorResume(
                        IllegalStateException.class,
                        e ->
                                Mono.just(
                                        ResponseEntity.status(HttpStatus.NOT_FOUND)
                                                .body(
                                                        Map.of(
                                                                "status",
                                                                "error",
                                                                "error",
                                                                e.getMessage()))));
    }
}
