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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound request body for {@code POST /api/webhook/{channelId}/inbound}.
 *
 * <p>Two {@link #replyMode} flavors are supported:
 * <ul>
 *   <li>{@code "callback"} (default) — the channel posts the reply to {@link #callbackUrl} signed
 *       with the channel's shared secret. The original POST returns 202 with the {@code inboundId}.
 *   <li>{@code "poll"} — the reply is parked in an in-memory ring keyed by {@code inboundId} and
 *       fetched via {@code GET /api/webhook/{channelId}/outbound/{inboundId}} (long-poll).
 * </ul>
 *
 * @param externalUserId opaque sender identity from the calling system (maps to {@code senderId}).
 *     Required.
 * @param externalSessionId optional sub-conversation key — when present, becomes part of the peer
 *     id so concurrent threads from the same user don't share a session. When absent, the peer id
 *     equals {@code externalUserId}.
 * @param message user-visible text payload forwarded to the agent. Required.
 * @param replyMode {@code "callback"} or {@code "poll"}. Defaults to {@code "poll"} when omitted.
 * @param callbackUrl callback URL for {@code replyMode="callback"}. Required in callback mode.
 * @param preferredAgentId optional explicit agent override; mirrors {@code InboundMessage
 *     .preferredAgentId} as a tier-0 short-circuit before binding evaluation.
 * @param inboundId optional client-supplied idempotency key. When omitted, the server generates one
 *     (returned in the response). Repeated POSTs with the same {@code inboundId} within the
 *     channel's idempotency TTL are dropped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookInboundRequest(
        String externalUserId,
        String externalSessionId,
        String message,
        String replyMode,
        String callbackUrl,
        String preferredAgentId,
        String inboundId) {

    public static final String REPLY_MODE_CALLBACK = "callback";
    public static final String REPLY_MODE_POLL = "poll";

    /** Returns the effective reply mode, defaulting to {@link #REPLY_MODE_POLL}. */
    public String effectiveReplyMode() {
        if (replyMode == null || replyMode.isBlank()) {
            return REPLY_MODE_POLL;
        }
        return replyMode.toLowerCase();
    }
}
