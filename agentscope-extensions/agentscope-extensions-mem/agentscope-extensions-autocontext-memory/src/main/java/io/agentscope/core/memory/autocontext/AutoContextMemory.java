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
package io.agentscope.core.memory.autocontext;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Auto context compressor that keeps a working buffer plus offloaded originals. */
public class AutoContextMemory implements Memory, ContextOffLoader {

    private static final String STATE_KEY = "auto_context_state";

    private final AutoContextConfig autoContextConfig;
    private final PromptConfig customPrompt;
    private volatile Model model;
    private final List<Msg> workingMemoryStorage = new ArrayList<>();
    private final List<Msg> originalMemoryStorage = new ArrayList<>();
    private final Map<String, List<Msg>> offloadContext = new HashMap<>();
    private final List<CompressionEvent> compressionEvents = new ArrayList<>();

    public AutoContextMemory(AutoContextConfig autoContextConfig, Model model) {
        this.autoContextConfig = Objects.requireNonNull(autoContextConfig, "autoContextConfig");
        this.model = model;
        this.customPrompt = autoContextConfig.getCustomPrompt();
    }

    public synchronized void setModel(Model model) {
        this.model = model;
    }

    public synchronized AutoContextConfig getAutoContextConfig() {
        return autoContextConfig;
    }

    /**
     * Reconciles runtime context back into the working buffer.
     *
     * <p>The incoming context is expected to be either the same session prefix plus newly appended
     * tail messages, or a rebuilt working context after a reset/reload. When the prefix still
     * matches, only the new tail is appended into both working/original buffers; when it no longer
     * matches, the working buffer is replaced and the original history is seeded only if it was
     * still empty.
     */
    public synchronized void mergeWithContext(List<Msg> context) {
        if (context == null || context.isEmpty()) {
            return;
        }
        if (workingMemoryStorage.isEmpty()) {
            workingMemoryStorage.addAll(copyMessages(context));
            if (originalMemoryStorage.isEmpty()) {
                originalMemoryStorage.addAll(copyMessages(context));
            }
            return;
        }
        if (context.size() < workingMemoryStorage.size()
                || !startsWith(context, workingMemoryStorage)) {
            workingMemoryStorage.clear();
            workingMemoryStorage.addAll(copyMessages(context));
            if (originalMemoryStorage.isEmpty()) {
                originalMemoryStorage.addAll(copyMessages(context));
            }
            return;
        }
        if (context.size() > workingMemoryStorage.size()) {
            List<Msg> tail =
                    new ArrayList<>(context.subList(workingMemoryStorage.size(), context.size()));
            workingMemoryStorage.addAll(tail);
            originalMemoryStorage.addAll(tail);
        }
    }

    public synchronized AutoContextState snapshot() {
        AutoContextState snapshot = new AutoContextState();
        snapshot.setWorkingMessages(workingMemoryStorage);
        snapshot.setOriginalMessages(originalMemoryStorage);
        snapshot.setOffloadContext(offloadContext);
        snapshot.setCompressionEvents(compressionEvents);
        return snapshot;
    }

    public synchronized void restore(AutoContextState snapshot) {
        if (snapshot == null) {
            return;
        }
        workingMemoryStorage.clear();
        workingMemoryStorage.addAll(copyMessages(snapshot.getWorkingMessages()));
        originalMemoryStorage.clear();
        originalMemoryStorage.addAll(copyMessages(snapshot.getOriginalMessages()));
        offloadContext.clear();
        offloadContext.putAll(copyMsgMap(snapshot.getOffloadContext()));
        compressionEvents.clear();
        compressionEvents.addAll(
                snapshot.getCompressionEvents() != null
                        ? snapshot.getCompressionEvents()
                        : List.of());
    }

    @Override
    public synchronized void saveTo(AgentStateStore stateStore, String userId, String sessionId) {
        if (stateStore == null) {
            return;
        }
        stateStore.save(userId, sessionId, STATE_KEY, snapshot());
    }

    public synchronized void saveTo(
            AgentStateStore stateStore, String userId, String sessionId, String key) {
        if (stateStore == null) {
            return;
        }
        stateStore.save(userId, sessionId, key, snapshot());
    }

    @Override
    public synchronized void loadFrom(AgentStateStore stateStore, String userId, String sessionId) {
        loadFrom(stateStore, userId, sessionId, STATE_KEY);
    }

    public synchronized void loadFrom(
            AgentStateStore stateStore, String userId, String sessionId, String key) {
        if (stateStore == null) {
            return;
        }
        stateStore.get(userId, sessionId, key, AutoContextState.class).ifPresent(this::restore);
    }

    @Override
    public synchronized void addMessage(Msg message) {
        if (message == null) {
            return;
        }
        workingMemoryStorage.add(message);
        originalMemoryStorage.add(message);
    }

    @Override
    public synchronized List<Msg> getMessages() {
        return new ArrayList<>(workingMemoryStorage);
    }

    @Override
    public synchronized void deleteMessage(int index) {
        if (index < 0 || index >= workingMemoryStorage.size()) {
            return;
        }
        workingMemoryStorage.remove(index);
    }

    public synchronized List<Msg> getOriginalMemoryMsgs() {
        return new ArrayList<>(originalMemoryStorage);
    }

    public synchronized List<Msg> getInteractionMsgs() {
        List<Msg> interactions = new ArrayList<>();
        for (Msg msg : originalMemoryStorage) {
            if (msg.getRole() == MsgRole.USER || MsgUtils.isFinalAssistantResponse(msg)) {
                interactions.add(msg);
            }
        }
        return interactions;
    }

    public synchronized Map<String, List<Msg>> getOffloadContext() {
        return offloadContext;
    }

    public synchronized List<CompressionEvent> getCompressionEvents() {
        return compressionEvents;
    }

    public boolean compressIfNeeded() {
        if (!isCompressionTriggered()) {
            return false;
        }

        if (compressToolGroups()) {
            return true;
        }
        if (offloadLargeMessages()) {
            return true;
        }
        if (compressPreviousRounds()) {
            return true;
        }
        return compressCurrentRound();
    }

    public synchronized Mono<Boolean> compressIfNeededAsync() {
        return Mono.fromCallable(this::compressIfNeeded).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public synchronized void offload(String uuid, List<Msg> messages) {
        if (uuid == null || uuid.isBlank() || messages == null) {
            return;
        }
        offloadContext.put(uuid, new ArrayList<>(messages));
    }

    @Override
    public synchronized List<Msg> reload(String uuid) {
        List<Msg> messages = offloadContext.get(uuid);
        return messages == null ? new ArrayList<>() : new ArrayList<>(messages);
    }

    @Override
    public synchronized void clear(String uuid) {
        if (uuid != null) {
            offloadContext.remove(uuid);
        }
    }

    @Override
    public synchronized void clear() {
        workingMemoryStorage.clear();
        originalMemoryStorage.clear();
        offloadContext.clear();
        compressionEvents.clear();
    }

    public synchronized void setWorkingMessages(List<Msg> messages) {
        workingMemoryStorage.clear();
        workingMemoryStorage.addAll(copyMessages(messages));
    }

    private synchronized boolean isCompressionTriggered() {
        if (workingMemoryStorage.isEmpty()) {
            return false;
        }
        int tokenCount = TokenCounterUtil.calculateToken(workingMemoryStorage);
        boolean msgCountReached =
                workingMemoryStorage.size() >= autoContextConfig.getMsgThreshold();
        boolean tokenReached =
                tokenCount
                        >= (int)
                                (autoContextConfig.getMaxToken()
                                        * autoContextConfig.getTokenRatio());
        if (!msgCountReached && !tokenReached) {
            return false;
        }
        return tokenCount >= autoContextConfig.getMinCompressionTokenThreshold();
    }

    private boolean compressToolGroups() {
        boolean changed = false;
        int cursor = 0;
        while (true) {
            CompressionCandidate candidate = planToolGroupCompression(cursor);
            if (candidate == null) {
                break;
            }
            if (TokenCounterUtil.calculateToken(candidate.source())
                    < autoContextConfig.getMinCompressionTokenThreshold()) {
                cursor = candidate.end() + 1;
                continue;
            }
            String summary =
                    summarizeMessages(
                            candidate.source(), candidate.prompt(), candidate.currentRound());
            if (applyCompression(candidate, summary)) {
                changed = true;
                cursor = candidate.start() + 1;
            } else {
                cursor = candidate.end() + 1;
            }
        }
        return changed;
    }

    private boolean offloadLargeMessages() {
        CompressionCandidate candidate = planLargeMessageOffload();
        if (candidate == null) {
            return false;
        }
        String summary =
                summarizeMessages(candidate.source(), candidate.prompt(), candidate.currentRound());
        return applyCompression(candidate, summary);
    }

    private boolean compressPreviousRounds() {
        boolean changed = false;
        int guard = 0;
        while (guard++ < 32) {
            CompressionCandidate candidate = planPreviousRoundCompression();
            if (candidate == null) {
                break;
            }
            String summary =
                    summarizeMessages(
                            candidate.source(), candidate.prompt(), candidate.currentRound());
            if (!applyCompression(candidate, summary)) {
                break;
            }
            changed = true;
        }
        return changed;
    }

    private boolean compressCurrentRound() {
        CompressionCandidate candidate = planCurrentRoundCompression();
        if (candidate == null) {
            return false;
        }
        String summary =
                summarizeMessages(candidate.source(), candidate.prompt(), candidate.currentRound());
        return applyCompression(candidate, summary);
    }

    private synchronized CompressionCandidate planToolGroupCompression(int cursor) {
        int upperLimit = Math.max(0, workingMemoryStorage.size() - autoContextConfig.getLastKeep());
        if (cursor >= upperLimit) {
            return null;
        }
        IntRange range = findToolGroup(cursor, upperLimit);
        if (range == null) {
            return null;
        }
        List<Msg> source =
                new ArrayList<>(workingMemoryStorage.subList(range.start, range.end + 1));
        return new CompressionCandidate(
                range.start,
                range.end,
                CompressionEvent.TOOL_INVOCATION_COMPRESS,
                PromptProvider.getPreviousRoundToolCompressPrompt(customPrompt),
                source,
                false,
                true,
                false);
    }

    private synchronized CompressionCandidate planLargeMessageOffload() {
        int limit = Math.max(0, workingMemoryStorage.size() - autoContextConfig.getLastKeep());
        for (int i = 0; i < limit; i++) {
            Msg msg = workingMemoryStorage.get(i);
            if (MsgUtils.calculateMessageCharCount(msg)
                    < autoContextConfig.getLargePayloadThreshold()) {
                continue;
            }
            return new CompressionCandidate(
                    i,
                    i,
                    msg.getRole() == MsgRole.TOOL
                            ? CompressionEvent.LARGE_MESSAGE_OFFLOAD
                            : CompressionEvent.LARGE_MESSAGE_OFFLOAD_WITH_PROTECTION,
                    PromptProvider.getCurrentRoundLargeMessagePrompt(customPrompt),
                    List.of(msg),
                    false,
                    false,
                    true);
        }
        return null;
    }

    private synchronized CompressionCandidate planPreviousRoundCompression() {
        if (workingMemoryStorage.size() <= autoContextConfig.getLastKeep() + 1) {
            return null;
        }
        int end = findPrefixCompressionEnd();
        if (end < 0) {
            return null;
        }
        List<Msg> source = new ArrayList<>(workingMemoryStorage.subList(0, end + 1));
        if (TokenCounterUtil.calculateToken(source)
                < autoContextConfig.getMinCompressionTokenThreshold()) {
            return null;
        }
        return new CompressionCandidate(
                0,
                end,
                CompressionEvent.PREVIOUS_ROUND_CONVERSATION_SUMMARY,
                PromptProvider.getPreviousRoundSummaryPrompt(customPrompt),
                source,
                false,
                false,
                false);
    }

    private synchronized CompressionCandidate planCurrentRoundCompression() {
        int latestUserIndex = -1;
        for (int i = workingMemoryStorage.size() - 1; i >= 0; i--) {
            if (workingMemoryStorage.get(i).getRole() == MsgRole.USER) {
                latestUserIndex = i;
                break;
            }
        }
        if (latestUserIndex < 0 || latestUserIndex >= workingMemoryStorage.size() - 1) {
            return null;
        }
        int end = workingMemoryStorage.size() - 1;
        if (MsgUtils.isToolUseMessage(workingMemoryStorage.get(end))) {
            end--;
        }
        if (end <= latestUserIndex) {
            return null;
        }
        List<Msg> source =
                new ArrayList<>(workingMemoryStorage.subList(latestUserIndex + 1, end + 1));
        if (TokenCounterUtil.calculateToken(source)
                < autoContextConfig.getMinCompressionTokenThreshold()) {
            return null;
        }
        return new CompressionCandidate(
                latestUserIndex + 1,
                end,
                CompressionEvent.CURRENT_ROUND_MESSAGE_COMPRESS,
                PromptProvider.getCurrentRoundCompressPrompt(customPrompt),
                source,
                true,
                true,
                false);
    }

    private boolean applyCompression(CompressionCandidate candidate, String summary) {
        if (candidate == null) {
            return false;
        }
        String resolvedSummary =
                summary == null || summary.isBlank()
                        ? fallbackSummary(candidate.source())
                        : summary.trim();
        String uuid = UUID.randomUUID().toString();
        Msg compressedMessage =
                candidate.singleMessageOffload()
                        ? compressSingleMessage(candidate.source().get(0), resolvedSummary, uuid)
                        : buildSummaryMessage(
                                candidate.source(),
                                resolvedSummary,
                                uuid,
                                candidate.appendOffloadTag());
        synchronized (this) {
            if (!matchesCandidate(candidate)) {
                return false;
            }
            offloadContext.put(uuid, copyMessages(candidate.source()));
            MsgUtils.replaceMsg(
                    workingMemoryStorage, candidate.start(), candidate.end(), compressedMessage);
            recordCompressionEvent(
                    candidate.eventType(),
                    candidate.start(),
                    candidate.end(),
                    candidate.source(),
                    compressedMessage,
                    buildCompressionMetadata(compressedMessage));
        }
        return true;
    }

    private String summarizeMessages(List<Msg> messages, String prompt, boolean currentRound) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        Model currentModel = model;
        if (currentModel == null) {
            return fallbackSummary(messages);
        }
        try {
            List<Msg> input = new ArrayList<>();
            input.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("user")
                            .content(TextBlock.builder().text(prompt).build())
                            .build());
            input.addAll(messages);
            input.add(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("user")
                            .content(
                                    TextBlock.builder()
                                            .text(Prompts.COMPRESSION_MESSAGE_LIST_END)
                                            .build())
                            .build());
            if (currentRound) {
                int chars = MsgUtils.calculateMessagesCharCount(messages);
                int target =
                        Math.max(
                                1,
                                (int)
                                        (chars
                                                * autoContextConfig
                                                        .getCurrentRoundCompressionRatio()));
                input.add(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .name("user")
                                .content(
                                        TextBlock.builder()
                                                .text(
                                                        String.format(
                                                                "\n"
                                                                        + "Compress to about %d"
                                                                        + " characters.",
                                                                target))
                                                .build())
                                .build());
            }
            List<ChatResponse> responses =
                    currentModel.stream(input, null, GenerateOptions.builder().build())
                            .collectList()
                            .block();
            if (responses == null || responses.isEmpty()) {
                return fallbackSummary(messages);
            }
            ChatResponse last = responses.get(responses.size() - 1);
            String text = extractResponseText(last);
            return text == null || text.isBlank() ? fallbackSummary(messages) : text.trim();
        } catch (Exception e) {
            return fallbackSummary(messages);
        }
    }

    private String extractResponseText(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private Msg buildSummaryMessage(
            List<Msg> source, String summary, String uuid, boolean appendOffloadTag) {
        MsgRole role = determineSummaryRole(source);
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> compressMeta = new HashMap<>();
        compressMeta.put("offloaduuid", uuid);
        if (appendOffloadTag) {
            compressMeta.put("compressed_current_round", Boolean.TRUE);
        }
        metadata.put("_compress_meta", compressMeta);
        ChatUsage usage = null;
        String content = summary;
        if (appendOffloadTag) {
            content = content + "\n" + String.format(Prompts.CONTEXT_OFFLOAD_TAG_FORMAT, uuid);
        }
        return Msg.builder()
                .role(role)
                .name(determineName(role))
                .content(TextBlock.builder().text(content).build())
                .metadata(metadata)
                .usage(usage)
                .build();
    }

    private Msg compressSingleMessage(Msg message, String summary, String uuid) {
        List<ContentBlock> content = new ArrayList<>();
        boolean summaryInserted = false;
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock && !summaryInserted) {
                content.add(TextBlock.builder().text(summary).build());
                summaryInserted = true;
            } else if (block instanceof ToolResultBlock toolResult) {
                content.add(
                        ToolResultBlock.builder()
                                .name(toolResult.getName())
                                .id(toolResult.getId())
                                .output(List.of(TextBlock.builder().text(summary).build()))
                                .build());
            } else {
                content.add(block);
            }
        }
        if (!summaryInserted) {
            content.add(TextBlock.builder().text(summary).build());
        }
        Map<String, Object> metadata =
                message.getMetadata() == null
                        ? new HashMap<>()
                        : new HashMap<>(message.getMetadata());
        Map<String, Object> compressMeta = new HashMap<>();
        compressMeta.put("offloaduuid", uuid);
        metadata.put("_compress_meta", compressMeta);
        return Msg.builder()
                .id(message.getId())
                .name(message.getName())
                .role(message.getRole())
                .content(content)
                .metadata(metadata)
                .timestamp(message.getTimestamp())
                .usage(message.getUsage())
                .build();
    }

    private Map<String, Object> buildCompressionMetadata(Msg msg) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tokenBefore", 0);
        metadata.put("tokenAfter", 0);
        if (msg != null && msg.getUsage() != null) {
            metadata.put("inputToken", msg.getUsage().getInputTokens());
            metadata.put("outputToken", msg.getUsage().getOutputTokens());
            metadata.put("time", msg.getUsage().getTime());
        }
        return metadata;
    }

    private void recordCompressionEvent(
            String eventType,
            int startIndex,
            int endIndex,
            List<Msg> source,
            Msg compressedMessage,
            Map<String, Object> metadata) {
        int tokenBefore = TokenCounterUtil.calculateToken(source);
        int tokenAfter = TokenCounterUtil.calculateToken(List.of(compressedMessage));
        metadata.put("tokenBefore", tokenBefore);
        metadata.put("tokenAfter", tokenAfter);
        CompressionEvent event =
                new CompressionEvent(
                        eventType,
                        System.currentTimeMillis(),
                        Math.max(1, endIndex - startIndex + 1),
                        startIndex > 0 && startIndex - 1 < workingMemoryStorage.size()
                                ? workingMemoryStorage.get(startIndex - 1).getId()
                                : null,
                        startIndex < workingMemoryStorage.size() - 1
                                ? workingMemoryStorage.get(startIndex + 1).getId()
                                : null,
                        compressedMessage != null ? compressedMessage.getId() : null,
                        metadata);
        compressionEvents.add(event);
    }

    private boolean matchesCandidate(CompressionCandidate candidate) {
        if (candidate.start() < 0
                || candidate.end() < candidate.start()
                || candidate.end() >= workingMemoryStorage.size()) {
            return false;
        }
        List<Msg> currentSlice =
                new ArrayList<>(
                        workingMemoryStorage.subList(candidate.start(), candidate.end() + 1));
        return currentSlice.equals(candidate.source());
    }

    private IntRange findToolGroup(int startIndex, int upperLimit) {
        int start = -1;
        for (int i = startIndex; i < upperLimit; i++) {
            Msg msg = workingMemoryStorage.get(i);
            if (MsgUtils.isToolMessage(msg)) {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                int end = i - 1;
                if (end - start + 1 >= autoContextConfig.getMinConsecutiveToolMessages()) {
                    return new IntRange(start, end);
                }
                start = -1;
            }
        }
        if (start >= 0 && upperLimit - start >= autoContextConfig.getMinConsecutiveToolMessages()) {
            return new IntRange(start, upperLimit - 1);
        }
        return null;
    }

    private int findPrefixCompressionEnd() {
        int end = workingMemoryStorage.size() - autoContextConfig.getLastKeep() - 1;
        if (end < 0) {
            return -1;
        }
        while (end > 0 && MsgUtils.isToolResultMessage(workingMemoryStorage.get(end))) {
            end--;
        }
        while (end > 0 && MsgUtils.isToolUseMessage(workingMemoryStorage.get(end))) {
            end--;
        }
        return end;
    }

    private String fallbackSummary(List<Msg> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Compressed ").append(messages.size()).append(" messages.");
        int previewLimit = autoContextConfig.getOffloadSinglePreview();
        String preview = buildPreview(messages);
        if (!preview.isBlank()) {
            sb.append(" ").append(truncate(preview, previewLimit));
        }
        return sb.toString();
    }

    private String buildPreview(List<Msg> messages) {
        StringBuilder sb = new StringBuilder();
        for (Msg msg : messages) {
            if (msg == null) {
                continue;
            }
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) {
                text = msg.getContent() != null ? msg.getContent().toString() : "";
            }
            if (!text.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(" | ");
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars);
    }

    private MsgRole determineSummaryRole(List<Msg> source) {
        if (source == null || source.isEmpty()) {
            return MsgRole.ASSISTANT;
        }
        boolean allTool = true;
        for (Msg msg : source) {
            if (msg.getRole() != MsgRole.TOOL) {
                allTool = false;
                break;
            }
        }
        return allTool ? MsgRole.TOOL : MsgRole.ASSISTANT;
    }

    private String determineName(MsgRole role) {
        return switch (role) {
            case USER -> "user";
            case TOOL -> "tool";
            case SYSTEM, ASSISTANT -> "assistant";
        };
    }

    private boolean startsWith(List<Msg> full, List<Msg> prefix) {
        if (full == null || prefix == null || full.size() < prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!Objects.equals(full.get(i), prefix.get(i))) {
                return false;
            }
        }
        return true;
    }

    private List<Msg> copyMessages(List<Msg> messages) {
        return messages == null ? new ArrayList<>() : new ArrayList<>(messages);
    }

    private Map<String, List<Msg>> copyMsgMap(Map<String, List<Msg>> input) {
        Map<String, List<Msg>> result = new HashMap<>();
        if (input == null) {
            return result;
        }
        for (Map.Entry<String, List<Msg>> entry : input.entrySet()) {
            result.put(entry.getKey(), copyMessages(entry.getValue()));
        }
        return result;
    }

    private record IntRange(int start, int end) {}

    private record CompressionCandidate(
            int start,
            int end,
            String eventType,
            String prompt,
            List<Msg> source,
            boolean currentRound,
            boolean appendOffloadTag,
            boolean singleMessageOffload) {}
}
