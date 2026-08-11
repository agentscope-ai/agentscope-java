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
package io.agentscope.extensions.aistio.model;

import io.agentscope.aistio.proto.SessionEventMsg;
import java.nio.charset.StandardCharsets;

/**
 * One Level-2 session event, mirroring the ASDP {@code SessionEventMsg}.
 *
 * <p>The event stream only carries summaries — {@code content} and {@code toolOutput} are truncated
 * to {@link #MAX_SUMMARY_LEN} characters. Full message bodies are pulled on demand over the Level-3
 * HTTP contract instead of being pushed.
 *
 * <p>{@code seq} increases monotonically within a session and is the control plane's idempotency
 * key; it is assigned by the bridge, not by adapters.
 */
public final class SessionEvent {

    public static final String SESSION_START = "session_start";
    public static final String MESSAGE = "message";
    public static final String TOOL_CALL = "tool_call";
    public static final String TOOL_RESULT = "tool_result";
    public static final String SESSION_END = "session_end";
    public static final String COMPACTION = "compaction";

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";

    /** Summary length cap, aligned with the Python SDK and sdk-design §3.3. */
    public static final int MAX_SUMMARY_LEN = 500;

    /** Cap on serialized tool input, so a large payload cannot dominate the stream. */
    public static final int MAX_TOOL_INPUT_BYTES = 4096;

    private final String sessionId;
    private final String eventType;
    private final long occurredAt;
    private final String role;
    private final String content;
    private final String toolName;
    private final byte[] toolInput;
    private final String toolOutput;
    private final int tokensIn;
    private final int tokensOut;
    private final int durationMs;
    private final byte[] frameworkMeta;

    private int seq;

    private SessionEvent(Builder builder) {
        this.sessionId = orEmpty(builder.sessionId);
        this.eventType = orEmpty(builder.eventType);
        this.occurredAt = builder.occurredAt > 0 ? builder.occurredAt : System.currentTimeMillis();
        this.role = orEmpty(builder.role);
        this.content = truncate(builder.content, MAX_SUMMARY_LEN);
        this.toolName = orEmpty(builder.toolName);
        this.toolInput = truncate(builder.toolInput, MAX_TOOL_INPUT_BYTES);
        this.toolOutput = truncate(builder.toolOutput, MAX_SUMMARY_LEN);
        this.tokensIn = Math.max(0, builder.tokensIn);
        this.tokensOut = Math.max(0, builder.tokensOut);
        this.durationMs = Math.max(0, builder.durationMs);
        this.frameworkMeta = builder.frameworkMeta;
        this.seq = builder.seq;
    }

    public static Builder builder(String sessionId, String eventType) {
        return new Builder().sessionId(sessionId).eventType(eventType);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getEventType() {
        return eventType;
    }

    public long getOccurredAt() {
        return occurredAt;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolOutput() {
        return toolOutput;
    }

    public int getTokensIn() {
        return tokensIn;
    }

    public int getTokensOut() {
        return tokensOut;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public int getSeq() {
        return seq;
    }

    /** Assigns the per-session sequence number; called by the bridge on ingest. */
    public void setSeq(int seq) {
        this.seq = seq;
    }

    public SessionEventMsg toProto() {
        SessionEventMsg.Builder b =
                SessionEventMsg.newBuilder()
                        .setSessionId(sessionId)
                        .setSeq(seq)
                        .setEventType(eventType)
                        .setOccurredAt(occurredAt)
                        .setRole(role)
                        .setContent(content)
                        .setToolName(toolName)
                        .setToolOutput(toolOutput)
                        .setTokensIn(tokensIn)
                        .setTokensOut(tokensOut)
                        .setDurationMs(durationMs);
        if (toolInput != null) {
            b.setToolInput(com.google.protobuf.ByteString.copyFrom(toolInput));
        }
        if (frameworkMeta != null) {
            b.setFrameworkMeta(com.google.protobuf.ByteString.copyFrom(frameworkMeta));
        }
        return b.build();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static byte[] truncate(byte[] value, int limit) {
        if (value == null || value.length <= limit) {
            return value;
        }
        byte[] out = new byte[limit];
        System.arraycopy(value, 0, out, 0, limit);
        return out;
    }

    /** Mutable builder for {@link SessionEvent}. */
    public static final class Builder {
        private String sessionId;
        private String eventType;
        private long occurredAt;
        private String role;
        private String content;
        private String toolName;
        private byte[] toolInput;
        private String toolOutput;
        private int tokensIn;
        private int tokensOut;
        private int durationMs;
        private byte[] frameworkMeta;
        private int seq;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder occurredAt(long occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder toolInput(byte[] toolInput) {
            this.toolInput = toolInput;
            return this;
        }

        public Builder toolInputJson(String json) {
            this.toolInput = json == null ? null : json.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        public Builder toolOutput(String toolOutput) {
            this.toolOutput = toolOutput;
            return this;
        }

        public Builder tokens(int tokensIn, int tokensOut) {
            this.tokensIn = tokensIn;
            this.tokensOut = tokensOut;
            return this;
        }

        public Builder durationMs(int durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder frameworkMeta(byte[] frameworkMeta) {
            this.frameworkMeta = frameworkMeta;
            return this;
        }

        public SessionEvent build() {
            return new SessionEvent(this);
        }
    }
}
