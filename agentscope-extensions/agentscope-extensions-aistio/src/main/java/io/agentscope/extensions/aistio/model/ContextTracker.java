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

import io.agentscope.extensions.aistio.model.ContextSnapshot.ContextMessage;
import io.agentscope.extensions.aistio.model.ContextSnapshot.ToolInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * Incrementally maintains one session's effective-context view and its {@code contextHash} from the
 * Level-2 event stream.
 *
 * <p>A {@code compaction} event resets the view down to a single summary message, matching what the
 * framework itself will send to the model afterwards.
 *
 * <p>This is an approximation rebuilt from events; the authoritative context still comes from
 * {@code FrameworkAdapter.extractContext()}. Its value is that it is always available in memory, so
 * Level-1 snapshots and change detection cost nothing.
 *
 * <p>Not thread-safe: the bridge serializes access under its own lock.
 */
public final class ContextTracker {

    private final String sessionId;
    private final String framework;
    private final List<ContextMessage> messages = new ArrayList<>();

    private String systemPrompt = "";
    private String effectiveSystemPrompt = "";
    private List<ToolInfo> tools = List.of();
    private int maxTokens;

    private boolean compacted;
    private String compactionSummary = "";
    private int originalMessageCount;
    private long compactedAt;
    private int tokensIn;
    private int tokensOut;

    /** Tokens currently occupying the effective context window (for pressure).
     * Set from the latest model-call inputTokens — never a sum across messages. */
    private int contextUsedTokens;

    private int messageCount;
    private String lastHash;
    private String model = "";

    public ContextTracker(String sessionId, String framework) {
        this.sessionId = sessionId;
        this.framework = framework == null ? "" : framework;
        this.lastHash = snapshot().computeHash();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getContextHash() {
        return lastHash;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public int getEffectiveMessageCount() {
        return messages.size();
    }

    public int getTokensIn() {
        return tokensIn;
    }

    public int getTokensOut() {
        return tokensOut;
    }

    public int getContextUsedTokens() {
        return contextUsedTokens;
    }

    /** Tokens in the live effective context; used for pressure = used / maxTokens. */
    public void setContextUsedTokens(int contextUsedTokens) {
        this.contextUsedTokens = Math.max(0, contextUsedTokens);
    }

    public boolean isCompacted() {
        return compacted;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    /** Context window size of the model behind this session, used for pressure ratios. */
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.max(0, maxTokens);
    }

    public String getModel() {
        return model;
    }

    /** Model name from agent config, when known. */
    public void setModel(String model) {
        this.model = model == null ? "" : model;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    /**
     * Last middleware-composed system prompt observed for this session (may differ from the builder
     * base prompt). Empty until the first turn completes {@code onSystemPrompt}.
     */
    public void setEffectiveSystemPrompt(String effectiveSystemPrompt) {
        this.effectiveSystemPrompt = effectiveSystemPrompt == null ? "" : effectiveSystemPrompt;
    }

    public String getEffectiveSystemPrompt() {
        return effectiveSystemPrompt;
    }

    /** Prefer the last effective prompt; fall back to the builder base. */
    public String resolveSystemPrompt() {
        return effectiveSystemPrompt.isEmpty() ? systemPrompt : effectiveSystemPrompt;
    }

    public void setTools(List<ToolInfo> tools) {
        this.tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * Consumes one event.
     *
     * @return {@code true} when the context hash changed, which is what gates Level-4 pushes
     */
    public boolean onEvent(SessionEvent event) {
        if (!sessionId.equals(event.getSessionId())) {
            return false;
        }
        tokensIn += event.getTokensIn();
        tokensOut += event.getTokensOut();

        switch (event.getEventType()) {
            case SessionEvent.MESSAGE -> {
                String role =
                        event.getRole().isEmpty() ? SessionEvent.ROLE_ASSISTANT : event.getRole();
                messages.add(ContextMessage.of(role, event.getContent()));
                messageCount++;
            }
            case SessionEvent.TOOL_CALL -> {
                String text =
                        event.getContent().isEmpty() ? event.getToolName() : event.getContent();
                messages.add(ContextMessage.of(SessionEvent.ROLE_ASSISTANT, text));
            }
            case SessionEvent.TOOL_RESULT -> {
                String text =
                        event.getToolOutput().isEmpty()
                                ? event.getContent()
                                : event.getToolOutput();
                messages.add(ContextMessage.of(SessionEvent.ROLE_TOOL, text));
            }
            case SessionEvent.COMPACTION -> {
                originalMessageCount = messageCount;
                compacted = true;
                compactionSummary = event.getContent();
                compactedAt = event.getOccurredAt();
                messages.clear();
                messages.add(
                        new ContextMessage(SessionEvent.ROLE_SYSTEM, event.getContent(), true));
            }
            default -> {
                // session_start / session_end do not alter the context view.
                return false;
            }
        }

        String newHash = snapshot().computeHash();
        boolean changed = !newHash.equals(lastHash);
        lastHash = newHash;
        return changed;
    }

    public ContextSnapshot snapshot() {
        return ContextSnapshot.builder(sessionId)
                .contextHash(lastHash)
                .systemPrompt(resolveSystemPrompt())
                .messages(List.copyOf(messages))
                .tools(tools)
                .compacted(compacted)
                .compactionSummary(compactionSummary)
                .originalMessageCount(originalMessageCount)
                .compactedAt(compactedAt)
                .totalTokens(tokensIn + tokensOut)
                .maxTokens(maxTokens)
                .framework(framework)
                .model(model)
                .build();
    }
}
