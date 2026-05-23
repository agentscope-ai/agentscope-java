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
package io.agentscope.harness.claw.app.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.claw.ClawBootstrap;
import io.agentscope.harness.claw.app.binding.UserBindingStore;
import io.agentscope.harness.claw.app.identity.IdentityLinkStore;
import io.agentscope.harness.claw.app.toolbus.ToolEventBus;
import io.agentscope.harness.claw.app.usage.UsageStore;
import io.agentscope.harness.claw.channel.chatui.ChatUiChannel;
import io.agentscope.harness.claw.gateway.MsgContext;
import io.agentscope.harness.claw.session.SessionAgentManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * REST controller for chat endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/chat/stream} — SSE streaming chat
 *   <li>{@code POST /api/chat/send} — synchronous chat
 * </ul>
 *
 * <p>This product ships a single business agent (DataAgent); the request body carries only the
 * user message and the controller always routes to {@link ClawBootstrap#mainAgentId()}.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatUiChannel chatUiChannel;
    private final SessionAgentManager sessionAgentManager;
    private final UserBindingStore userBindings;
    private final IdentityLinkStore identityLinks;
    private final UsageStore usageStore;
    private final ToolEventBus toolEventBus;
    private final ClawBootstrap clawBootstrap;

    public ChatController(
            ChatUiChannel chatUiChannel,
            ClawBootstrap clawBootstrap,
            UserBindingStore userBindings,
            IdentityLinkStore identityLinks,
            UsageStore usageStore,
            ToolEventBus toolEventBus) {
        this.chatUiChannel = chatUiChannel;
        this.sessionAgentManager = clawBootstrap.gateway().sessionAgentManager();
        this.userBindings = userBindings;
        this.identityLinks = identityLinks;
        this.usageStore = usageStore;
        this.toolEventBus = toolEventBus;
        this.clawBootstrap = clawBootstrap;
    }

    public record ChatRequest(String message) {}

    public record ChatResponse(String reply, String sessionKey) {}

    /**
     * SSE streaming endpoint. Emits:
     *
     * <ul>
     *   <li>{@code RUN_STARTED} — with run id
     *   <li>{@code TOOL_CALL} — per tool invocation (real-time)
     *   <li>{@code RUN_DONE} — full reply text
     *   <li>{@code ERROR} — on failure
     * </ul>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        String runId = "r-" + UUID.randomUUID();

        CommandResult cmd = handleSlashCommand(userId, req.message());
        if (cmd != null) {
            return Flux.just(
                    sse("RUN_STARTED", Map.of("runId", runId)),
                    sse("RUN_DONE", Map.of("runId", runId, "reply", cmd.message)));
        }

        String sessionKeyForFilter = resolveSessionKey(userId);

        ServerSentEvent<String> started = sse("RUN_STARTED", Map.of("runId", runId));
        Sinks.One<Boolean> done = Sinks.one();

        Flux<ServerSentEvent<String>> toolEvents =
                sessionKeyForFilter != null
                        ? toolEventBus
                                .subscribe(sessionKeyForFilter)
                                .takeUntilOther(done.asMono().timeout(Duration.ofMinutes(10)))
                                .map(
                                        e -> {
                                            Map<String, Object> data = new LinkedHashMap<>();
                                            data.put("type", "TOOL_CALL");
                                            data.put("runId", runId);
                                            data.put("toolName", e.toolName());
                                            if (e.data() != null) data.put("input", e.data());
                                            return sse("TOOL_CALL", data);
                                        })
                                .onErrorResume(ex -> Flux.empty())
                        : Flux.empty();

        Mono<ServerSentEvent<String>> agentCall =
                executeChat(userId, req.message())
                        .map(
                                reply -> {
                                    String text =
                                            reply.getTextContent() != null
                                                    ? reply.getTextContent()
                                                    : "";
                                    done.tryEmitValue(true);
                                    return sse("RUN_DONE", Map.of("runId", runId, "reply", text));
                                })
                        .onErrorResume(
                                ex -> {
                                    log.warn(
                                            "Chat stream error: userId={}, error={}",
                                            userId,
                                            ex.getMessage());
                                    done.tryEmitValue(false);
                                    return Mono.just(
                                            sse("ERROR", Map.of("message", ex.getMessage())));
                                });

        return Flux.merge(Flux.just(started), toolEvents, agentCall.flux());
    }

    /** Synchronous (non-streaming) chat endpoint. */
    @PostMapping("/send")
    public Mono<ChatResponse> send(@RequestBody ChatRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        CommandResult cmd = handleSlashCommand(userId, req.message());
        if (cmd != null) {
            return Mono.just(new ChatResponse(cmd.message, null));
        }
        return executeChat(userId, req.message())
                .map(
                        reply -> {
                            String text =
                                    reply.getTextContent() != null ? reply.getTextContent() : "";
                            return new ChatResponse(text, null);
                        });
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    /**
     * Returns the user's preferred reply language for the chat UI channel, or {@code null} if no
     * preference is set. Used to inject a small language hint into the outgoing message.
     */
    private String resolveLanguagePreference(String userId) {
        return userBindings
                .resolveForChannel(userId, ChatUiChannel.CHANNEL_ID)
                .map(UserBindingStore.UserBinding::language)
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
    }

    /**
     * Builds the session key the {@link ChatUiChannel} will use for this user. Mirrors the routing
     * that {@link io.agentscope.harness.claw.channel.ChannelRouter} performs for {@code
     * DmScope.PER_PEER} so the tool-event SSE filter matches the events the agent actually emits.
     */
    private String resolveSessionKey(String userId) {
        String agentId = clawBootstrap.mainAgentId();
        if (agentId == null || agentId.isBlank()) return null;
        try {
            MsgContext ctx =
                    new MsgContext(
                            ChatUiChannel.CHANNEL_ID,
                            null,
                            userId,
                            null,
                            null,
                            Map.of("agentId", agentId),
                            userId);
            return ctx.canonicalKey();
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<Msg> executeChat(String userId, String message) {
        String language = resolveLanguagePreference(userId);
        String decoratedMessage =
                language != null ? "[Reply in " + language + "]\n" + message : message;
        long startMs = System.currentTimeMillis();

        return chatUiChannel
                .send(userId, decoratedMessage)
                .doOnSuccess(
                        reply ->
                                usageStore.record(
                                        userId,
                                        clawBootstrap.mainAgentId(),
                                        System.currentTimeMillis() - startMs));
    }

    private CommandResult handleSlashCommand(String userId, String message) {
        if (message == null) return null;
        String m = message.trim();
        if (!m.startsWith("/")) return null;
        String[] parts = m.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/new", "/reset" -> {
                String key = resolveSessionKey(userId);
                if (key == null) {
                    return new CommandResult("No active session to reset.");
                }
                boolean ok = sessionAgentManager.resetSession(key);
                return new CommandResult(
                        ok
                                ? "✓ Session reset. The next message starts a fresh turn."
                                : "No matching session found for reset.");
            }
            case "/identity" -> {
                Map<String, String> links = identityLinks.linksFor(userId);
                if (links.isEmpty()) {
                    return new CommandResult(
                            "No identity links yet. Use `/dock_<channel> <externalId>` to add"
                                    + " one.");
                }
                StringBuilder sb = new StringBuilder("Your identity links:\n");
                links.forEach(
                        (ch, id) ->
                                sb.append("  • ").append(ch).append(" → ").append(id).append('\n'));
                return new CommandResult(sb.toString());
            }
            default -> {
                if (cmd.startsWith("/dock_")) {
                    String channel = cmd.substring("/dock_".length());
                    if (channel.isBlank() || arg.isBlank()) {
                        return new CommandResult(
                                "Usage: `/dock_<channel> <externalId>` — e.g. `/dock_slack"
                                        + " U7F9LZK1A`.");
                    }
                    identityLinks.link(userId, channel, arg);
                    return new CommandResult(
                            "✓ Linked your identity on `" + channel + "` to `" + arg + "`.");
                }
                return null;
            }
        }
    }

    private record CommandResult(String message) {}

    private ServerSentEvent<String> sse(String eventType, Object data) {
        String json;
        try {
            json =
                    MAPPER.writeValueAsString(
                            data instanceof Map<?, ?> m
                                    ? addType(m, eventType)
                                    : Map.of("type", eventType, "data", data));
        } catch (JsonProcessingException e) {
            json = "{\"type\":\"" + eventType + "\"}";
        }
        return ServerSentEvent.<String>builder().event(eventType).data(json).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> addType(Map<?, ?> m, String type) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("type", type);
        m.forEach((k, v) -> out.put(k.toString(), v));
        return out;
    }
}
