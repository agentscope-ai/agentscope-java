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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Lightweight sequential multiagent fan-out: runs the same task message against a list of agents,
 * one after another, through the same gateway chat-dispatch path used by {@code ChatController}
 * (each agent gets its own per-{@code (userId, agentId)} managed session).
 *
 * <p>This is intentionally a thin, ad-hoc coordinator rather than a full orchestration engine —
 * agents that need to delegate further sub-work do so on their own via {@code SessionsTool} (see
 * {@link AgentCatalogService#getOrInstantiateRunningAgent}, which injects the shared {@code
 * SessionsTool} into every dynamically-built agent). This coordinator only handles top-level,
 * ordered fan-out across a caller-supplied list of agents.
 */
@RestController
@RequestMapping("/api/multiagent")
public class MultiagentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MultiagentCoordinator.class);

    private final ChatUiChannel chatUiChannel;
    private final AgentCatalogService catalogService;

    public MultiagentCoordinator(ChatUiChannel chatUiChannel, AgentCatalogService catalogService) {
        this.chatUiChannel = chatUiChannel;
        this.catalogService = catalogService;
    }

    /**
     * Request body. {@code environmentId} is currently unused — the chat-dispatch path this
     * coordinator reuses always resolves the agent's default managed instance rather than a
     * per-environment build; reserved for a future managed-session-backed variant.
     */
    public record RunRequest(List<String> agentIds, String message, String environmentId) {}

    /** Outcome of running the task message against one agent. */
    public record AgentRunResult(String agentId, String status, String reply, String error) {}

    /** Aggregated response: one result per requested agent, in request order. */
    public record RunResponse(List<AgentRunResult> results) {}

    /** Runs {@code message} against each of {@code agentIds} in order, waiting for each to finish. */
    @PostMapping("/run")
    public Mono<RunResponse> run(@RequestBody RunRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        validate(req);
        return Flux.fromIterable(req.agentIds())
                .concatMap(agentId -> runOne(userId, agentId, req.message()))
                .collectList()
                .map(RunResponse::new);
    }

    private Mono<AgentRunResult> runOne(String userId, String agentId, String message) {
        return Mono.fromCallable(() -> catalogService.resolveGatewayAgentId(userId, agentId))
                .flatMap(
                        gatewayAgentId -> {
                            Msg userMsg =
                                    Msg.builder().role(MsgRole.USER).textContent(message).build();
                            InboundMessage inbound =
                                    InboundMessage.dmFor(
                                            ChatUiChannel.CHANNEL_ID,
                                            userId,
                                            gatewayAgentId,
                                            List.of(userMsg));
                            return chatUiChannel.dispatch(inbound);
                        })
                .map(
                        reply ->
                                new AgentRunResult(
                                        agentId,
                                        "ok",
                                        reply.getTextContent() != null
                                                ? reply.getTextContent()
                                                : "",
                                        null))
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "Multiagent run failed: agentId={}, error={}",
                                    agentId,
                                    ex.getMessage());
                            return Mono.just(
                                    new AgentRunResult(agentId, "error", null, describeError(ex)));
                        });
    }

    private static String describeError(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return rse.getReason();
        }
        return ex.getMessage();
    }

    private static void validate(RunRequest req) {
        if (req == null || req.agentIds() == null || req.agentIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentIds is required");
        }
        if (req.message() == null || req.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
    }
}
