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

import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Bridges inbound IM channel turns (DingTalk, Feishu, WeCom, GitHub, GitLab, ...) into the
 * managed-session model across plane boundaries:
 *
 * <ol>
 *   <li>{@code POST /api/internal/sessions/find-or-create} on the <b>control plane</b> resolves
 *       (or creates and version-pins) the session for the channel conversation;
 *   <li>{@code POST /api/sessions/{id}/events} on the <b>data plane</b> posts the user message,
 *       which schedules the harness turn asynchronously;
 *   <li>{@code GET /api/sessions/{id}/events?after=seq} on the <b>data plane</b> is polled until
 *       the turn reaches a terminal status, and the last {@code agent.message} text is returned
 *       as the channel reply.
 * </ol>
 *
 * <p>All calls authenticate with the shared internal token; the acting owner is carried in
 * {@code X-Builder-Internal-User} so the receiving plane attributes the operation to the channel
 * tenant. The bridge performs blocking polls on {@code boundedElastic} — one in-flight turn per
 * channel conversation, so contention stays bounded by inbound message rate.
 */
@Component
public class ManagedSessionChannelBridge {

    private static final Logger log = LoggerFactory.getLogger(ManagedSessionChannelBridge.class);

    private static final ParameterizedTypeReference<List<SessionEventDto>> EVENT_LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient controlPlane;
    private final WebClient dataPlane;
    private final long replyTimeoutMs;
    private final long pollIntervalMs;

    public ManagedSessionChannelBridge(
            @Qualifier("controlPlaneWebClient") WebClient controlPlane,
            @Qualifier("dataPlaneWebClient") WebClient dataPlane,
            @Value("${builder.scheduler.reply-timeout-ms:120000}") long replyTimeoutMs,
            @Value("${builder.scheduler.poll-interval-ms:1000}") long pollIntervalMs) {
        this.controlPlane = controlPlane;
        this.dataPlane = dataPlane;
        this.replyTimeoutMs = replyTimeoutMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Posts {@code text} as a user message on the found-or-created managed session and waits for
     * the turn to finish, returning the assistant reply text.
     *
     * @param ownerId Builder tenant that owns the channel / agent (not the IM peer)
     * @param agentId target managed agent id
     * @param externalKey stable conversation key ({@link ChannelExternalKeys})
     * @param text user message text
     */
    public Mono<String> dispatchAndAwaitReply(
            String ownerId, String agentId, String externalKey, String text) {
        return Mono.fromCallable(() -> doDispatch(ownerId, agentId, externalKey, text))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** @deprecated use {@link #dispatchAndAwaitReply(String, String, String, String)} */
    @Deprecated
    public Mono<String> dispatchAndAwaitReply(String ownerId, String agentId, String text) {
        return dispatchAndAwaitReply(ownerId, agentId, null, text);
    }

    private String doDispatch(String ownerId, String agentId, String externalKey, String text) {
        ManagedSessionDto session = findOrCreateSession(ownerId, agentId, externalKey);
        long after = postUserMessage(ownerId, session.id(), text);
        return awaitReply(ownerId, session.id(), after);
    }

    /** Resolves the active session for the conversation, creating one on first contact. */
    private ManagedSessionDto findOrCreateSession(
            String ownerId, String agentId, String externalKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownerId", ownerId);
        body.put("agentId", agentId);
        if (externalKey != null && !externalKey.isBlank()) {
            body.put("externalKey", externalKey);
        }
        return controlPlane
                .post()
                .uri("/api/internal/sessions/find-or-create")
                .header(InternalTokenAuthFilter.INTERNAL_USER_HEADER, ownerId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ManagedSessionDto.class)
                .block(Duration.ofSeconds(30));
    }

    /**
     * Posts the user message; returns the highest persisted event sequence so the reply poll can
     * start strictly after the inbound events.
     */
    private long postUserMessage(String ownerId, String sessionId, String text) {
        Map<String, Object> body =
                Map.of(
                        "events",
                        List.of(
                                Map.of(
                                        "type",
                                        SessionEventTypes.USER_MESSAGE,
                                        "payload",
                                        Map.of("text", text))));
        List<SessionEventDto> persisted =
                dataPlane
                        .post()
                        .uri("/api/sessions/{id}/events", sessionId)
                        .header(InternalTokenAuthFilter.INTERNAL_USER_HEADER, ownerId)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(EVENT_LIST)
                        .block(Duration.ofSeconds(30));
        long max = 0L;
        if (persisted != null) {
            for (SessionEventDto e : persisted) {
                max = Math.max(max, e.seq());
            }
        }
        return max;
    }

    /**
     * Polls the persisted event log until the turn terminates. Returns the last {@code
     * agent.message} text seen, or a fallback notice when the turn ends without one.
     */
    private String awaitReply(String ownerId, String sessionId, long after) {
        long deadline = System.currentTimeMillis() + replyTimeoutMs;
        String reply = null;
        while (System.currentTimeMillis() < deadline) {
            List<SessionEventDto> events;
            final long afterSeq = after;
            try {
                events =
                        dataPlane
                                .get()
                                .uri(
                                        uriBuilder ->
                                                uriBuilder
                                                        .path("/api/sessions/{id}/events")
                                                        .queryParam("after", afterSeq)
                                                        .build(sessionId))
                                .header(InternalTokenAuthFilter.INTERNAL_USER_HEADER, ownerId)
                                .retrieve()
                                .bodyToMono(EVENT_LIST)
                                .block(Duration.ofSeconds(30));
            } catch (Exception ex) {
                log.warn("session event poll failed (session={}): {}", sessionId, ex.getMessage());
                events = List.of();
            }
            if (events != null) {
                for (SessionEventDto e : events) {
                    after = Math.max(after, e.seq());
                    if (SessionEventTypes.AGENT_MESSAGE.equals(e.type())) {
                        String text = payloadText(e.payload());
                        if (text != null && !text.isBlank()) {
                            reply = text;
                        }
                    } else if (isTerminal(e.type())) {
                        return finishReply(e.type(), reply);
                    }
                }
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return reply != null
                ? reply
                : "(still processing — the agent will keep running; ask again in a moment)";
    }

    private static String finishReply(String terminalType, String reply) {
        if (reply != null) {
            return reply;
        }
        return switch (terminalType) {
            case SessionEventTypes.SESSION_REQUIRES_ACTION ->
                    "(the agent is waiting for a tool confirmation — confirm it from the console)";
            case SessionEventTypes.SESSION_ERROR -> "(the agent run failed — check session events)";
            default -> "(the agent finished without a text reply)";
        };
    }

    private static boolean isTerminal(String type) {
        return SessionEventTypes.SESSION_STATUS_IDLE.equals(type)
                || SessionEventTypes.SESSION_STATUS_TERMINATED.equals(type)
                || SessionEventTypes.SESSION_REQUIRES_ACTION.equals(type)
                || SessionEventTypes.SESSION_ERROR.equals(type);
    }

    private static String payloadText(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object text = payload.get("text");
        return text != null ? String.valueOf(text) : null;
    }
}
