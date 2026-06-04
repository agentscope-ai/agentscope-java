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
package io.agentscope.builder.web.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.runtime.channel.InboundMessage;
import io.agentscope.builder.runtime.channel.chatui.ChatUiChannel;
import io.agentscope.builder.runtime.gateway.MsgContext;
import io.agentscope.builder.runtime.session.SessionAgentManager;
import io.agentscope.builder.runtime.session.SessionEntry;
import io.agentscope.builder.runtime.session.SessionKind;
import io.agentscope.builder.web.audit.ActivityEvent;
import io.agentscope.builder.web.audit.AgentActivityStore;
import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.identity.IdentityLinkStore;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.toolbus.ToolEventBus;
import io.agentscope.builder.web.usage.UsageStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
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
 * </ul>
 *
 * <p>Each user gets an isolated session per {@code (userId, agentId)} pair (the agent id is part of
 * the {@link MsgContext#canonicalKey()}). Slash commands {@code /new}, {@code /reset},
 * {@code /identity}, and {@code /dock_<channel> <id>} are intercepted before the agent is invoked.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatUiChannel chatUiChannel;
    private final SessionAgentManager sessionAgentManager;
    private final AgentCatalogService catalogService;
    private final IdentityLinkStore identityLinks;
    private final UsageStore usageStore;
    private final ToolEventBus toolEventBus;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    /**
     * AgentStateStore keys for which we have already recorded a RUN_SESSION event. Each (userId, agentId)
     * pair gets one entry per process lifetime so the activity log shows one row per session, not
     * one per turn.
     */
    private final Set<String> startedSessions = ConcurrentHashMap.newKeySet();

    public ChatController(
            ChatUiChannel chatUiChannel,
            BuilderBootstrap builderBootstrap,
            AgentCatalogService catalogService,
            IdentityLinkStore identityLinks,
            UsageStore usageStore,
            ToolEventBus toolEventBus,
            AgentAccessGuard guard,
            AgentActivityStore activity) {
        this.chatUiChannel = chatUiChannel;
        this.sessionAgentManager = builderBootstrap.gateway().sessionAgentManager();
        this.catalogService = catalogService;
        this.identityLinks = identityLinks;
        this.usageStore = usageStore;
        this.toolEventBus = toolEventBus;
        this.guard = guard;
        this.activity = activity;
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

    /**
     * SSE streaming endpoint. Emits, in order:
     *
     * <ul>
     *   <li>{@code tool_call} — for each tool the agent invokes (real time)
     *   <li>{@code tool_result} — currently inferred by the bus implementation
     *   <li>{@code token} — the full reply text (currently emitted as a single chunk)
     *   <li>{@code done} — end of run, optionally carrying the resolved {@code sessionKey}
     *   <li>{@code error} — terminates the run on failure
     * </ul>
     *
     * <p>The frontend consumes these via {@code chat.ts}, parsing the {@code data:} payload as JSON
     * with shape {@code { type, data?, toolName?, toolInput?, toolResult?, error?, sessionKey? }}.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        recordRunSession(def, agentId, userId);

        // Slash commands short-circuit the agent and produce a synthetic single-token reply.
        CommandResult cmd = handleSlashCommand(userId, agentId, req.message());
        if (cmd != null) {
            return Flux.just(
                    sse("token", Map.of("type", "token", "data", cmd.message)),
                    sse("done", Map.of("type", "done")));
        }

        // Subscribe to tool events. The bus filters by the *real* sessionKey, but on the first
        // turn the session has not yet been registered — fall back to a gateKey-resolved lookup
        // each time an event arrives so we pick up the sessionKey as soon as the gateway creates
        // it.
        String gateKey = resolveGateKey(userId, agentId);
        String existingSessionKey = findSessionKeyByGate(userId, gateKey);
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
                executeChat(userId, agentId, req.message())
                        .map(
                                reply -> {
                                    String text =
                                            reply.getTextContent() != null
                                                    ? reply.getTextContent()
                                                    : "";
                                    done.tryEmitValue(true);
                                    Map<String, Object> doneFrame = new LinkedHashMap<>();
                                    doneFrame.put("type", "done");
                                    String resolved = findSessionKeyByGate(userId, gateKey);
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
                                            "Chat stream error: userId={}, agentId={}, error={}",
                                            userId,
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
     * Returns the deterministic session key for the current (userId, agentId) pair. Used by the
     * Chat tab on mount to rehydrate prior turns before any new message is sent. {@code exists}
     * reflects whether a session entry has been registered yet — when {@code false}, callers should
     * skip the turns fetch since the harness has nothing to return.
     */
    @GetMapping("/session")
    public Mono<CurrentSessionResponse> currentSession(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        guard.require(userId, agentId, Tier.RUN);
        return Mono.fromCallable(
                () -> {
                    String gateKey = resolveGateKey(userId, agentId);
                    if (gateKey == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    String sessionKey = findSessionKeyByGate(userId, gateKey);
                    if (sessionKey == null) {
                        return new CurrentSessionResponse(null, false);
                    }
                    return new CurrentSessionResponse(sessionKey, true);
                });
    }

    /** Synchronous (non-streaming) chat. Blocks until the agent produces a reply. */
    @PostMapping("/send")
    public Mono<ChatResponse> send(
            @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
        recordRunSession(def, agentId, userId);
        CommandResult cmd = handleSlashCommand(userId, agentId, req.message());
        if (cmd != null) {
            return Mono.just(new ChatResponse(cmd.message, null));
        }
        String gateKey = resolveGateKey(userId, agentId);
        return executeChat(userId, agentId, req.message())
                .map(
                        reply -> {
                            String text =
                                    reply.getTextContent() != null ? reply.getTextContent() : "";
                            String sessionKey = findSessionKeyByGate(userId, gateKey);
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
     * Emits a single {@code RUN_SESSION} event the first time a (userId, agentId) pair starts a
     * chat in this process. Subsequent turns within the same session are silent. Resetting the
     * session via {@code /reset} clears the cached marker so a fresh session is logged again.
     */
    private void recordRunSession(AgentDefinition def, String agentId, String userId) {
        if (def == null || def.ownerId() == null) {
            // Globals have no per-agent activity log.
            return;
        }
        // Use the gateKey here as a per-(user, agent) dedupe id — the real sessionKey may not
        // exist yet on the very first turn.
        String dedupeKey = resolveGateKey(userId, agentId);
        if (dedupeKey == null) return;
        if (!startedSessions.add(dedupeKey)) return;
        activity.record(
                def.ownerId(),
                agentId,
                activity.actor(userId),
                ActivityEvent.Action.RUN_SESSION,
                dedupeKey,
                null);
    }

    /**
     * Computes the gateway routing key for a given (userId, agentId) pair. This is the
     * {@link MsgContext#canonicalKey()} the gateway uses to look up (or create) the underlying
     * session — it is <em>not</em> the {@code SessionEntry.sessionKey()} the storage layer uses.
     * Use {@link #findSessionKeyByGate} to translate to the real sessionKey.
     *
     * <p>Uses {@link ChatUiChannel#previewRoute} so the key matches exactly what {@link
     * #executeChat} will produce when it dispatches through the same channel.
     */
    private String resolveGateKey(String userId, String agentId) {
        if (agentId == null || agentId.isBlank()) return null;
        try {
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            InboundMessage probe =
                    InboundMessage.dmFor(
                            ChatUiChannel.CHANNEL_ID, userId, gatewayAgentId, List.of());
            return chatUiChannel.previewRoute(probe).context().canonicalKey();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Translates a gateway routing key into the real {@code SessionEntry.sessionKey()}, by
     * scanning registered MAIN sessions for the matching {@code gateKey}. Returns {@code null}
     * when no session has been registered yet (e.g. before the first turn).
     */
    private String findSessionKeyByGate(String userId, String gateKey) {
        if (gateKey == null) return null;
        for (SessionEntry e : sessionAgentManager.allSessions()) {
            if (e.kind() != SessionKind.MAIN) continue;
            if (!Objects.equals(gateKey, e.gateKey())) continue;
            if (userId != null && !Objects.equals(userId, e.userId())) continue;
            return e.sessionKey();
        }
        return null;
    }

    /**
     * If {@code message} is a recognised slash command, applies the side-effect (e.g. resets the
     * session for this user+agent pair) and returns a synthetic confirmation reply. Returns
     * {@code null} for ordinary messages.
     */
    private CommandResult handleSlashCommand(String userId, String agentId, String message) {
        if (message == null) return null;
        String m = message.trim();
        if (!m.startsWith("/")) return null;
        String[] parts = m.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/new":
            case "/reset":
                {
                    String gateKey = resolveGateKey(userId, agentId);
                    if (gateKey == null) {
                        return new CommandResult("No active session to reset.");
                    }
                    String sessionKey = findSessionKeyByGate(userId, gateKey);
                    if (sessionKey == null) {
                        // No session registered yet — there's nothing to clear, which from the
                        // user's perspective is effectively a fresh start. Drop the dedupe
                        // marker so the next real message logs a fresh RUN_SESSION event.
                        startedSessions.remove(gateKey);
                        return new CommandResult(
                                "No active session yet — your next message will start a fresh"
                                        + " conversation.");
                    }
                    boolean ok = sessionAgentManager.resetSession(sessionKey);
                    startedSessions.remove(gateKey);
                    return new CommandResult(
                            ok
                                    ? "AgentStateStore reset. Conversation history cleared; the"
                                            + " next message starts a fresh turn."
                                    : "No matching session found for reset.");
                }
            case "/identity":
                {
                    Map<String, String> links = identityLinks.linksFor(userId);
                    if (links.isEmpty()) {
                        return new CommandResult(
                                "No identity links yet. Use `/dock_<channel> <externalId>` to add"
                                        + " one — e.g. `/dock_slack U7F9LZK1A`.");
                    }
                    StringBuilder sb = new StringBuilder("Your identity links:\n");
                    links.forEach(
                            (ch, id) ->
                                    sb.append("  - ")
                                            .append(ch)
                                            .append(" -> ")
                                            .append(id)
                                            .append('\n'));
                    return new CommandResult(sb.toString());
                }
            default:
                if (cmd.startsWith("/dock_")) {
                    String channel = cmd.substring("/dock_".length());
                    if (channel.isBlank() || arg.isBlank()) {
                        return new CommandResult(
                                "Usage: `/dock_<channel> <externalId>` — e.g."
                                        + " `/dock_slack U7F9LZK1A`.");
                    }
                    identityLinks.link(userId, channel, arg);
                    return new CommandResult(
                            "Linked your identity on `" + channel + "` to `" + arg + "`.");
                }
                return null;
        }
    }

    /** Internal carrier for slash-command results. */
    private record CommandResult(String message) {}

    /**
     * Core dispatch logic. Always routes through {@link ChatUiChannel#dispatch} so that the
     * {@link io.agentscope.builder.runtime.channel.ChannelRouter} runs uniformly — including for
     * the path-mapped Web UI calls. The URL-supplied {@code agentId} is passed as
     * {@link InboundMessage#preferredAgentId()} so it short-circuits the binding-tier evaluation
     * (the user explicitly picked this agent) while still letting the router derive sessionScope
     * and outbound addressing from any matching binding.
     *
     * <p>When {@code agentId} is blank (defensive — controller always supplies one), falls back to
     * pure binding-driven routing: the chatui channel's default agent or matching binding wins.
     */
    private Mono<Msg> executeChat(String userId, String agentId, String message) {
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(message).build();
        long startMs = System.currentTimeMillis();

        InboundMessage inbound;
        if (agentId == null || agentId.isBlank()) {
            inbound = InboundMessage.dm(ChatUiChannel.CHANNEL_ID, userId, List.of(userMsg));
        } else {
            String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
            inbound =
                    InboundMessage.dmFor(
                            ChatUiChannel.CHANNEL_ID, userId, gatewayAgentId, List.of(userMsg));
        }
        Mono<Msg> call = chatUiChannel.dispatch(inbound);

        final String recordedAgentId = agentId != null ? agentId : "(default)";
        return call.doOnSuccess(
                reply ->
                        usageStore.record(
                                userId, recordedAgentId, System.currentTimeMillis() - startMs));
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
