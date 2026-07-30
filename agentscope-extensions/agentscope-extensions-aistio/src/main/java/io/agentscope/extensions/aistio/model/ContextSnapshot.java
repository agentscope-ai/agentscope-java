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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.protobuf.ByteString;
import io.agentscope.aistio.proto.ContextReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Level-4 effective-context snapshot: what the agent's next model call will actually see.
 *
 * <p>Carries both encodings — {@link #toProto()} for the ASDP {@code ContextReport} upstream push,
 * and {@link #toJsonMap()} for {@code GET /agentscope/sessions/{id}/context}.
 *
 * <p>{@link #computeHash()} produces the same digest as the Go control plane and the Python SDK:
 * SHA-256 over recursively key-sorted, separator-free JSON of the effective content, truncated to
 * {@link #HASH_LEN} hex characters. The control plane compares hashes to detect context drift
 * without fetching the body.
 */
public final class ContextSnapshot {

    /** Number of leading hex characters of the SHA-256 digest kept as the context hash. */
    public static final int HASH_LEN = 16;

    private static final ObjectMapper CANONICAL_MAPPER =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final String sessionId;
    private final String systemPrompt;
    private final List<ContextMessage> messages;
    private final List<ToolInfo> tools;
    private final boolean compacted;
    private final String compactionSummary;
    private final int originalMessageCount;
    private final long compactedAt;
    private final int totalTokens;
    private final int maxTokens;
    private final String framework;
    private final String model;
    private final byte[] frameworkState;
    private final long capturedAt;

    private String contextHash;

    private ContextSnapshot(Builder builder) {
        this.sessionId = builder.sessionId == null ? "" : builder.sessionId;
        this.systemPrompt = builder.systemPrompt == null ? "" : builder.systemPrompt;
        this.messages = List.copyOf(builder.messages);
        this.tools = List.copyOf(builder.tools);
        this.compacted = builder.compacted;
        this.compactionSummary = builder.compactionSummary == null ? "" : builder.compactionSummary;
        this.originalMessageCount = builder.originalMessageCount;
        this.compactedAt = builder.compactedAt;
        this.totalTokens = builder.totalTokens;
        this.maxTokens = builder.maxTokens;
        this.framework = builder.framework == null ? "" : builder.framework;
        this.model = builder.model == null ? "" : builder.model;
        this.frameworkState = builder.frameworkState;
        this.capturedAt = builder.capturedAt > 0 ? builder.capturedAt : System.currentTimeMillis();
        this.contextHash = builder.contextHash == null ? "" : builder.contextHash;
    }

    public static Builder builder(String sessionId) {
        return new Builder().sessionId(sessionId);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public List<ContextMessage> getMessages() {
        return messages;
    }

    public List<ToolInfo> getTools() {
        return tools;
    }

    public boolean isCompacted() {
        return compacted;
    }

    public String getCompactionSummary() {
        return compactionSummary;
    }

    public int getOriginalMessageCount() {
        return originalMessageCount;
    }

    public long getCompactedAt() {
        return compactedAt;
    }

    public String getFramework() {
        return framework;
    }

    public String getModel() {
        return model;
    }

    public byte[] getFrameworkState() {
        return frameworkState;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public String getContextHash() {
        return contextHash.isEmpty() ? computeHash() : contextHash;
    }

    /** Recomputes and stores {@link #getContextHash()}, returning the new value. */
    public String refreshHash() {
        this.contextHash = computeHash();
        return this.contextHash;
    }

    public String computeHash() {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("systemPrompt", systemPrompt);
        List<Object> msgs = new ArrayList<>(messages.size());
        for (ContextMessage m : messages) {
            msgs.add(m.toCanonicalMap());
        }
        canonical.put("messages", msgs);
        List<Object> toolList = new ArrayList<>(tools.size());
        for (ToolInfo t : tools) {
            toolList.add(t.toCanonicalMap());
        }
        canonical.put("tools", toolList);
        byte[] payload;
        try {
            payload = CANONICAL_MAPPER.writeValueAsBytes(canonical);
        } catch (JsonProcessingException e) {
            payload = canonical.toString().getBytes(StandardCharsets.UTF_8);
        }
        return sha256Hex(payload).substring(0, HASH_LEN);
    }

    public ContextReport toProto() {
        ContextReport.Builder b =
                ContextReport.newBuilder()
                        .setSessionId(sessionId)
                        .setContextHash(getContextHash())
                        .setCapturedAt(capturedAt)
                        .setSystemPrompt(systemPrompt)
                        .setMessages(ByteString.copyFrom(jsonBytes(messagesAsMaps())))
                        .setTools(ByteString.copyFrom(jsonBytes(toolsAsMaps())))
                        .setIsCompacted(compacted)
                        .setCompactionSummary(compactionSummary)
                        .setOriginalMessageCount(originalMessageCount)
                        .setCompactedAt(compactedAt)
                        .setTotalTokens(totalTokens)
                        .setMaxTokens(maxTokens)
                        .setFramework(framework);
        if (frameworkState != null) {
            b.setFrameworkState(ByteString.copyFrom(frameworkState));
        }
        return b.build();
    }

    /** Response body for {@code GET /agentscope/sessions/{id}/context}. */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("contextHash", getContextHash());
        out.put("capturedAt", Instant.ofEpochMilli(capturedAt).toString());
        out.put("messages", messagesAsMaps());
        if (!systemPrompt.isEmpty()) {
            out.put("systemPrompt", systemPrompt);
        }
        if (!tools.isEmpty()) {
            out.put("tools", toolsAsMaps());
        }
        if (compacted) {
            out.put("isCompacted", true);
        }
        if (!compactionSummary.isEmpty()) {
            out.put("compactionSummary", compactionSummary);
        }
        if (originalMessageCount > 0) {
            out.put("originalMessageCount", originalMessageCount);
        }
        if (compactedAt > 0) {
            out.put("compactedAt", Instant.ofEpochMilli(compactedAt).toString());
        }
        if (totalTokens > 0) {
            out.put("totalTokens", totalTokens);
        }
        if (maxTokens > 0) {
            out.put("maxTokens", maxTokens);
        }
        if (!framework.isEmpty()) {
            out.put("framework", framework);
        }
        if (!model.isEmpty()) {
            out.put("model", model);
        }
        if (frameworkState != null && frameworkState.length > 0) {
            try {
                out.put("frameworkState", CANONICAL_MAPPER.readValue(frameworkState, Object.class));
            } catch (Exception e) {
                out.put("frameworkState", new String(frameworkState, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private List<Object> messagesAsMaps() {
        List<Object> out = new ArrayList<>(messages.size());
        for (ContextMessage m : messages) {
            out.add(m.toCanonicalMap());
        }
        return out;
    }

    private List<Object> toolsAsMaps() {
        List<Object> out = new ArrayList<>(tools.size());
        for (ToolInfo t : tools) {
            out.add(t.toCanonicalMap());
        }
        return out;
    }

    private static byte[] jsonBytes(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            return "[]".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String sha256Hex(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** One message in the effective context. */
    public record ContextMessage(String role, String content, boolean isCompaction) {

        public static ContextMessage of(String role, String content) {
            return new ContextMessage(role, content, false);
        }

        Map<String, Object> toCanonicalMap() {
            Map<String, Object> map = new TreeMap<>();
            map.put("role", role == null ? "" : role);
            map.put("content", content == null ? "" : content);
            if (isCompaction) {
                map.put("isCompaction", true);
            }
            return map;
        }
    }

    /** One tool currently exposed to the agent. */
    public record ToolInfo(String name, String description, Map<String, Object> parameters) {

        Map<String, Object> toCanonicalMap() {
            Map<String, Object> map = new TreeMap<>();
            map.put("name", name == null ? "" : name);
            if (description != null && !description.isEmpty()) {
                map.put("description", description);
            }
            if (parameters != null && !parameters.isEmpty()) {
                map.put("parameters", parameters);
            }
            return map;
        }
    }

    /** Mutable builder for {@link ContextSnapshot}. */
    public static final class Builder {
        private String sessionId;
        private String systemPrompt;
        private List<ContextMessage> messages = List.of();
        private List<ToolInfo> tools = List.of();
        private boolean compacted;
        private String compactionSummary;
        private int originalMessageCount;
        private long compactedAt;
        private int totalTokens;
        private int maxTokens;
        private String framework;
        private String model;
        private byte[] frameworkState;
        private long capturedAt;
        private String contextHash;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder messages(List<ContextMessage> messages) {
            this.messages = messages == null ? List.of() : messages;
            return this;
        }

        public Builder tools(List<ToolInfo> tools) {
            this.tools = tools == null ? List.of() : tools;
            return this;
        }

        public Builder compacted(boolean compacted) {
            this.compacted = compacted;
            return this;
        }

        public Builder compactionSummary(String compactionSummary) {
            this.compactionSummary = compactionSummary;
            return this;
        }

        public Builder originalMessageCount(int originalMessageCount) {
            this.originalMessageCount = originalMessageCount;
            return this;
        }

        public Builder compactedAt(long compactedAt) {
            this.compactedAt = compactedAt;
            return this;
        }

        public Builder totalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder framework(String framework) {
            this.framework = framework;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder frameworkState(byte[] frameworkState) {
            this.frameworkState = frameworkState;
            return this;
        }

        public Builder capturedAt(long capturedAt) {
            this.capturedAt = capturedAt;
            return this;
        }

        public Builder contextHash(String contextHash) {
            this.contextHash = contextHash;
            return this;
        }

        public ContextSnapshot build() {
            return new ContextSnapshot(this);
        }
    }
}
