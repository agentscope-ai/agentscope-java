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
package io.agentscope.claw2.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.channel.Channel;
import io.agentscope.claw2.runtime.channel.ChannelRouter;
import io.agentscope.claw2.runtime.channel.InboundMessage;
import io.agentscope.claw2.runtime.channel.RouteResult;
import io.agentscope.claw2.runtime.channel.chatui.ChatUiChannel;
import io.agentscope.claw2.runtime.gateway.ChannelManager;
import io.agentscope.claw2.runtime.gateway.HarnessGateway;
import io.agentscope.claw2.runtime.gateway.MsgContext;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SessionEntry;
import io.agentscope.claw2.runtime.session.SessionKind;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.claw2.web.toolbus.ToolEventBus;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Chat endpoints, scoped to a specific agent.
 *
 * <ul>
 *   <li>{@code POST /api/agents/{agentId}/chat/stream} — SSE stream of {@code token | tool_call |
 *       tool_result | done | error} events.
 *   <li>{@code POST /api/agents/{agentId}/chat/send} — synchronous reply (no streaming).
 *   <li>{@code GET  /api/agents/{agentId}/chat/session} — current session key for rehydration.
 * </ul>
 *
 * <p>Each agent has a single per-process session keyed by its id. Slash commands {@code /new} and
 * {@code /reset} clear the conversation history before the agent is invoked.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HarnessGateway gateway;
    private final SessionAgentManager sessionAgentManager;
    private final AgentCatalogService catalogService;
    private final ToolEventBus toolEventBus;
    private final ChannelManager channelManager;
    private final ChannelRouter router = new ChannelRouter(null);

    public ChatController(
            ClawBootstrap bootstrap,
            AgentCatalogService catalogService,
            ToolEventBus toolEventBus) {
        this.gateway = bootstrap.gateway();
        this.sessionAgentManager = bootstrap.gateway().sessionAgentManager();
        this.catalogService = catalogService;
        this.toolEventBus = toolEventBus;
        this.channelManager = bootstrap.channelManager();
    }

    /** Request body for both endpoints. {@code sessionKey} is reserved for future routing use. */
    public record ChatRequest(String message, String sessionKey) {}

    /** Response for the synchronous endpoint. */
    public record ChatResponse(String reply, String sessionKey) {}

    /**
     * Response for {@link #currentSession}. {@code exists} is {@code true} when a session entry has
     * already been created (i.e. the user has sent at least one message); the frontend uses this to
     * decide whether to fetch turns on mount.
     */
    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /** SSE streaming endpoint. */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId, @RequestBody ChatRequest req) {
        CommandResult cmd = handleSlashCommand(agentId, req.message());
        if (cmd != null) {
            return Flux.just(
                    sse("token", Map.of("type", "token", "data", cmd.message)),
                    sse("done", Map.of("type", "done")));
        }

        String gateKey = resolveGateKey(agentId);
        String existingSessionKey = findSessionKeyByGate(gateKey);
        Sinks.One<Boolean> done = Sinks.one();
        Flux<ServerSentEvent<String>> toolEvents =
                existingSessionKey != null
                        ? toolEventBus
                                .subscribe(existingSessionKey)
                                .takeUntilOther(done.asMono().timeout(Duration.ofMinutes(10)))
                                .map(this::toToolFrame)
                                .onErrorResume(ex -> Flux.empty())
                        : Flux.empty();

        Mono<Flux<ServerSentEvent<String>>> agentCall =
                executeChat(agentId, req.message())
                        .map(
                                reply -> {
                                    String text =
                                            reply.getTextContent() != null
                                                    ? reply.getTextContent()
                                                    : "";
                                    done.tryEmitValue(true);
                                    Map<String, Object> doneFrame = new LinkedHashMap<>();
                                    doneFrame.put("type", "done");
                                    String resolved = findSessionKeyByGate(gateKey);
                                    if (resolved != null) {
                                        doneFrame.put("sessionKey", resolved);
                                    }
                                    return Flux.just(
                                            sse("token", Map.of("type", "token", "data", text)),
                                            sse("done", doneFrame));
                                })
                        .onErrorResume(
                                ex -> {
                                    log.warn(
                                            "Chat stream error: agentId={}, error={}",
                                            agentId,
                                            ex.getMessage());
                                    done.tryEmitValue(false);
                                    return Mono.just(
                                            Flux.just(
                                                    sse(
                                                            "error",
                                                            Map.of(
                                                                    "type",
                                                                    "error",
                                                                    "error",
                                                                    ex.getMessage()))));
                                });

        return Flux.merge(toolEvents, Flux.from(agentCall.flatMapMany(f -> f)));
    }

    /** Returns the current session key for {@code agentId}, used by the UI on mount. */
    @GetMapping("/session")
    public Mono<CurrentSessionResponse> currentSession(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    String gateKey = resolveGateKey(agentId);
                    if (gateKey == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    String sessionKey = findSessionKeyByGate(gateKey);
                    if (sessionKey == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    return new CurrentSessionResponse(sessionKey, true);
                });
    }

    /** Synchronous (non-streaming) chat. Blocks until the agent produces a reply. */
    @PostMapping("/send")
    public Mono<ChatResponse> send(@PathVariable String agentId, @RequestBody ChatRequest req) {
        CommandResult cmd = handleSlashCommand(agentId, req.message());
        if (cmd != null) {
            return Mono.just(new ChatResponse(cmd.message, null));
        }
        String gateKey = resolveGateKey(agentId);
        return executeChat(agentId, req.message())
                .map(
                        reply -> {
                            String text =
                                    reply.getTextContent() != null ? reply.getTextContent() : "";
                            String sessionKey = findSessionKeyByGate(gateKey);
                            return new ChatResponse(text, sessionKey);
                        });
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private ServerSentEvent<String> toToolFrame(ToolEventBus.ToolEvent e) {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean isResult = "TOOL_RESULT".equalsIgnoreCase(e.eventType());
        data.put("type", isResult ? "tool_result" : "tool_call");
        data.put("toolName", e.toolName());
        if (e.data() != null) {
            String payload;
            try {
                payload = MAPPER.writeValueAsString(e.data());
            } catch (JsonProcessingException ex) {
                payload = String.valueOf(e.data());
            }
            data.put(isResult ? "toolResult" : "toolInput", payload);
        }
        return sse(isResult ? "tool_result" : "tool_call", data);
    }

    /**
     * Computes the gateway routing key for the given agent by running the same {@link
     * ChannelRouter} pass that {@link ChatUiChannel#dispatch} will use, then taking the
     * {@link MsgContext#canonicalKey()} of the resolved context. This guarantees the key matches
     * the one created by an in-flight dispatch even when chatui bindings override the requested
     * agent. It is <em>not</em> the {@code SessionEntry.sessionKey()} stored on disk; use
     * {@link #findSessionKeyByGate} to translate.
     */
    String resolveGateKey(String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            return resolveRoute(agentId, "__probe__").context().canonicalKey();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds the inbound for a chat request and runs it through the chatui channel's router. The
     * router-resolved {@link RouteResult} drives both session-key lookup and {@link
     * HarnessGateway#run} so bindings, channel-default, and requested-agent hints all use a single
     * source of truth.
     */
    private RouteResult resolveRoute(String agentId, String probeText) {
        String gatewayAgentId = catalogService.resolveGatewayAgentId(agentId);
        ChatUiChannel chatui = lookupChatUi();
        InboundMessage inbound =
                InboundMessage.builder(
                                ChatUiChannel.CHANNEL_ID,
                                io.agentscope.claw2.runtime.channel.Peer.direct("__anonymous__"),
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .textContent(probeText)
                                                .build()))
                        .senderId("__anonymous__")
                        .requestedAgentId(gatewayAgentId)
                        .build();
        return router.resolveRoute(chatui.config(), inbound);
    }

    private ChatUiChannel lookupChatUi() {
        if (channelManager != null) {
            Channel ch = channelManager.getChannel(ChatUiChannel.CHANNEL_ID).orElse(null);
            if (ch instanceof ChatUiChannel chatui) {
                return chatui;
            }
        }
        throw new IllegalStateException(
                "chatui channel not registered with ChannelManager; cannot route chat request");
    }

    /**
     * Translates a gateway routing key into the real {@code SessionEntry.sessionKey()} by scanning
     * registered MAIN sessions. Returns {@code null} when no session has been registered yet.
     */
    private String findSessionKeyByGate(String gateKey) {
        if (gateKey == null) return null;
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) continue;
            if (!Objects.equals(gateKey, e.gateKey())) continue;
            return e.sessionKey();
        }
        return null;
    }

    /**
     * Handles {@code /new} and {@code /reset}. Returns {@code null} for ordinary messages or
     * unknown slash commands.
     */
    private CommandResult handleSlashCommand(String agentId, String message) {
        if (message == null) return null;
        String m = message.trim();
        if (!m.startsWith("/")) return null;
        String cmd = m.split("\\s+", 2)[0].toLowerCase();

        return switch (cmd) {
            case "/new", "/reset" -> {
                String gateKey = resolveGateKey(agentId);
                if (gateKey == null) {
                    yield new CommandResult("No active session to reset.");
                }
                String sessionKey = findSessionKeyByGate(gateKey);
                if (sessionKey == null) {
                    yield new CommandResult(
                            "No active session yet — your next message will start a fresh"
                                    + " conversation.");
                }
                boolean ok = sessionAgentManager.resetSession(sessionKey);
                yield new CommandResult(
                        ok
                                ? "AgentStateStore reset. Conversation history cleared; the next"
                                        + " message starts a fresh turn."
                                : "No matching session found for reset.");
            }
            default -> null;
        };
    }

    /** Internal carrier for slash-command results. */
    private record CommandResult(String message) {}

    private Mono<Msg> executeChat(String agentId, String message) {
        RouteResult route = resolveRoute(agentId, message);
        return gateway.run(route.context(), List.of(messageOf(message)), route.outboundAddress());
    }

    private static Msg messageOf(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private ServerSentEvent<String> sse(String eventType, Object data) {
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            json = "{\"type\":\"" + eventType + "\"}";
        }
        return ServerSentEvent.<String>builder().event(eventType).data(json).build();
    }
}
