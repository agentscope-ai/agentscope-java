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
import io.agentscope.claw2.runtime.gateway.HarnessGateway;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SessionEntry;
import io.agentscope.claw2.runtime.session.SessionKind;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.claw2.web.toolbus.ToolEventBus;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
 * <p>Each ChatGPT-style browser tab is a conversation addressed by {@code sessionKey} (a
 * caller-supplied conversation id). That id is placed on a thread peer so the gateway routing key
 * includes {@code |t:<conversationId>} and inbox can list many MAIN sessions per agent. Slash
 * command {@code /new} mints a fresh conversation id; {@code /reset} clears the current one.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String LOCAL_USER_ID = "__anonymous__";

    /** Conversation ids are concatenated into {@code |t:} / {@code |x:} routing keys. */
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

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

    /**
     * Request body for both endpoints. {@code sessionKey} is the caller-supplied conversation id
     * (not the internal {@code SessionEntry.sessionKey()}).
     */
    public record ChatRequest(String message, String sessionKey) {}

    /** Response for the synchronous endpoint. */
    public record ChatResponse(String reply, String sessionKey) {}

    /**
     * Response for {@link #currentSession}. {@code exists} is {@code true} when a session entry has
     * already been created (i.e. the user has sent at least one message). Chat restore fetches
     * turns by conversation id directly; {@code exists} is informational.
     */
    public record CurrentSessionResponse(String sessionKey, boolean exists) {}

    /** SSE streaming endpoint. */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId, @RequestBody ChatRequest req) {
        ChatIdentity identity = resolveChatIdentity(req.sessionKey());
        CommandResult cmd = handleSlashCommand(agentId, req.message(), identity);
        if (cmd != null) {
            Map<String, Object> doneFrame = new LinkedHashMap<>();
            doneFrame.put("type", "done");
            doneFrame.put(
                    "sessionKey",
                    cmd.newSessionKey != null ? cmd.newSessionKey : identity.echoKey());
            return Flux.just(
                    sse("token", Map.of("type", "token", "data", cmd.message)),
                    sse("done", doneFrame));
        }

        String gateKey = resolveGateKey(agentId, identity.conversationId());
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
                executeChat(agentId, req.message(), identity.conversationId())
                        .map(
                                reply -> {
                                    String text =
                                            reply.getTextContent() != null
                                                    ? reply.getTextContent()
                                                    : "";
                                    done.tryEmitValue(true);
                                    Map<String, Object> doneFrame = new LinkedHashMap<>();
                                    doneFrame.put("type", "done");
                                    doneFrame.put("sessionKey", identity.echoKey());
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

    /**
     * Returns whether a conversation already has a registered session. The {@code sessionKey}
     * field echoes the caller's conversation id (never the internal storage key).
     */
    @GetMapping("/session")
    public Mono<CurrentSessionResponse> currentSession(
            @PathVariable String agentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    String sessionKey) {
        return Mono.fromCallable(
                () -> {
                    ChatIdentity identity = resolveChatIdentity(sessionKey, false);
                    if (identity.echoKey() == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    if (sessionAgentManager.getSession(identity.echoKey()).isPresent()) {
                        return new CurrentSessionResponse(identity.echoKey(), true);
                    }
                    if (findSessionKeyByConversationId(identity.echoKey()) != null) {
                        return new CurrentSessionResponse(identity.echoKey(), true);
                    }
                    String gateKey = resolveGateKey(agentId, identity.conversationId());
                    boolean exists = gateKey != null && findSessionKeyByGate(gateKey) != null;
                    return new CurrentSessionResponse(identity.echoKey(), exists);
                });
    }

    /** Synchronous (non-streaming) chat. Blocks until the agent produces a reply. */
    @PostMapping("/send")
    public Mono<ChatResponse> send(@PathVariable String agentId, @RequestBody ChatRequest req) {
        ChatIdentity identity = resolveChatIdentity(req.sessionKey());
        CommandResult cmd = handleSlashCommand(agentId, req.message(), identity);
        if (cmd != null) {
            return Mono.just(
                    new ChatResponse(
                            cmd.message,
                            cmd.newSessionKey != null ? cmd.newSessionKey : identity.echoKey()));
        }
        return executeChat(agentId, req.message(), identity.conversationId())
                .map(
                        reply -> {
                            String text =
                                    reply.getTextContent() != null ? reply.getTextContent() : "";
                            return new ChatResponse(text, identity.echoKey());
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
     * Builds the inbound message used for Chat UI turns. A non-blank {@code conversationId} is
     * placed on a thread peer so {@code dmScope=MAIN} still yields a distinct {@code |t:} routing
     * key per conversation.
     */
    static InboundMessage buildConversationInbound(
            String gatewayAgentId, String conversationId, List<Msg> messages) {
        List<Msg> payload = messages != null ? messages : List.of();
        if (conversationId != null && !conversationId.isBlank()) {
            String threadId = conversationId.trim();
            requireSafeConversationId(threadId);
            return InboundMessage.builder(ChatUiChannel.CHANNEL_ID, Peer.thread(threadId), payload)
                    .senderId(LOCAL_USER_ID)
                    .parentPeer(Peer.direct(LOCAL_USER_ID))
                    .preferredAgentId(gatewayAgentId)
                    .build();
        }
        return InboundMessage.builder(ChatUiChannel.CHANNEL_ID, Peer.direct(LOCAL_USER_ID), payload)
                .senderId(LOCAL_USER_ID)
                .preferredAgentId(gatewayAgentId)
                .build();
    }

    String resolveGateKey(String agentId, String conversationId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            return resolveRoute(agentId, "__probe__", conversationId).context().canonicalKey();
        } catch (Exception e) {
            return null;
        }
    }

    private RouteResult resolveRoute(String agentId, String probeText, String conversationId) {
        String gatewayAgentId = catalogService.resolveGatewayAgentId(agentId);
        ChatUiChannel chatui = lookupChatUi();
        InboundMessage inbound =
                buildConversationInbound(
                        gatewayAgentId,
                        conversationId,
                        List.of(Msg.builder().role(MsgRole.USER).textContent(probeText).build()));
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
     * Looks up a MAIN session by the {@code |t:} conversation id, without recomputing the routing
     * key. Inbox already lists by this id; Chat restore must use the same match.
     */
    private String findSessionKeyByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        String wanted = conversationId.trim();
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) {
                continue;
            }
            if (wanted.equals(SessionController.extractConversationId(e.gateKey()))) {
                return e.sessionKey();
            }
        }
        return null;
    }

    /**
     * Handles {@code /new} (mint a fresh conversation) and {@code /reset} (clear the current one).
     * Returns {@code null} for ordinary messages or unknown slash commands.
     */
    private CommandResult handleSlashCommand(
            String agentId, String message, ChatIdentity identity) {
        if (message == null) return null;
        String m = message.trim();
        if (!m.startsWith("/")) return null;
        String cmd = m.split("\\s+", 2)[0].toLowerCase();

        return switch (cmd) {
            case "/new" ->
                    new CommandResult(
                            "Started a fresh conversation. Your next message opens a new chat.",
                            UUID.randomUUID().toString());
            case "/reset" -> {
                String gateKey = resolveGateKey(agentId, identity.conversationId());
                if (gateKey == null) {
                    yield new CommandResult("No active session to reset.", identity.echoKey());
                }
                String sessionKey = findSessionKeyByGate(gateKey);
                if (sessionKey == null && identity.echoKey() != null) {
                    sessionKey =
                            sessionAgentManager.getSession(identity.echoKey()).isPresent()
                                    ? identity.echoKey()
                                    : null;
                }
                if (sessionKey == null) {
                    yield new CommandResult(
                            "No active session yet — your next message will start a fresh"
                                    + " conversation.",
                            identity.echoKey());
                }
                boolean ok = sessionAgentManager.resetSession(sessionKey);
                yield new CommandResult(
                        ok
                                ? "AgentStateStore reset. Conversation history cleared; the next"
                                        + " message starts a fresh turn."
                                : "No matching session found for reset.",
                        identity.echoKey());
            }
            default -> null;
        };
    }

    /** Internal carrier for slash-command results. */
    private record CommandResult(String message, String newSessionKey) {}

    /**
     * Resolves the conversation id to route with. A value that already matches an internal storage
     * key (legacy UI) keeps the old direct-peer MAIN routing so the original session is reused.
     */
    private ChatIdentity resolveChatIdentity(String requested) {
        return resolveChatIdentity(requested, true);
    }

    private ChatIdentity resolveChatIdentity(String requested, boolean mintIfAbsent) {
        String key = normalizedConversationId(requested);
        if (key != null && sessionAgentManager.getSession(key).isPresent()) {
            return new ChatIdentity(null, key, false);
        }
        if (key != null) {
            requireSafeConversationId(key);
            return new ChatIdentity(key, key, true);
        }
        if (!mintIfAbsent) {
            return new ChatIdentity(null, null, false);
        }
        String minted = UUID.randomUUID().toString();
        return new ChatIdentity(minted, minted, true);
    }

    private static String normalizedConversationId(String key) {
        return (key != null && !key.isBlank()) ? key.trim() : null;
    }

    /**
     * Rejects ids that would forge {@code |t:} / {@code |x:} segments in the gateway routing key.
     * Legacy internal storage keys are accepted earlier via {@link #resolveChatIdentity} and never
     * reach this check.
     */
    static void requireSafeConversationId(String conversationId) {
        if (conversationId == null || !CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversation id");
        }
    }

    private record ChatIdentity(String conversationId, String echoKey, boolean threadRouted) {}

    private Mono<Msg> executeChat(String agentId, String message, String conversationId) {
        RouteResult route = resolveRoute(agentId, message, conversationId);
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
