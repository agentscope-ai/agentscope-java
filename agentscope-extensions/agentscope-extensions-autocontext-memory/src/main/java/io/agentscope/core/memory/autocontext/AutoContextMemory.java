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

import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.PlanState;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.StateModule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * AutoContextMemory - Intelligent context memory management system.
 *
 * <p>AutoContextMemory implements the {@link Memory} interface and provides automated
 * context compression, offloading, and summarization to optimize LLM context window usage.
 * When conversation history exceeds configured thresholds, the system automatically applies
 * multiple compression strategies to reduce context size while preserving important information.
 *
 * <p>Key features:
 * <ul>
 *   <li>Automatic compression when message count or token count exceeds thresholds</li>
 *   <li>Six progressive compression strategies (from lightweight to heavyweight)</li>
 *   <li>Intelligent summarization using LLM models</li>
 *   <li>Content offloading to external storage</li>
 *   <li>Tool call interface preservation during compression</li>
 *   <li>Dual storage mechanism (working storage and original storage)</li>
 * </ul>
 *
 * <p>Compression strategies (applied in order):
 * <ol>
 *   <li>Compress historical tool invocations</li>
 *   <li>Offload large messages (with lastKeep protection)</li>
 *   <li>Offload large messages (without protection)</li>
 *   <li>Summarize historical conversation rounds</li>
 *   <li>Summarize large messages in current round (with LLM summary and offload)</li>
 *   <li>Compress current round messages</li>
 * </ol>
 *
 * <p>Storage architecture:
 * <ul>
 *   <li>Working Memory Storage: Stores compressed messages for actual conversations</li>
 *   <li>Original Memory Storage: Stores complete, uncompressed message history</li>
 * </ul>
 */
public class AutoContextMemory implements StateModule, Memory, ContextOffLoader {

    private static final Logger log = LoggerFactory.getLogger(AutoContextMemory.class);

    /**
     * Working memory storage for compressed and offloaded messages.
     * This storage is used for actual conversations and may contain compressed summaries.
     */
    private List<Msg> workingMemoryStorage;

    /**
     * Original memory storage for complete, uncompressed message history.
     * This storage maintains the full conversation history in its original form (append-only).
     */
    private List<Msg> originalMemoryStorage;

    private Map<String, List<Msg>> offloadContext = new HashMap<>();

    /**
     * List of compression events that occurred during context management.
     * Records information about each compression operation including timing, token reduction,
     * and message positioning.
     */
    private List<CompressionEvent> compressionEvents;

    /**
     * Auto context configuration containing thresholds and settings.
     * Defines compression triggers, storage options, and offloading behavior.
     */
    private final AutoContextConfig autoContextConfig;

    /**
     * LLM model used for generating summaries and compressing content.
     * Required for intelligent compression and summarization operations.
     */
    private Model model;

    /**
     * Optional PlanNotebook instance for plan-aware compression.
     * When provided, compression prompts will be adjusted based on current plan state
     * to preserve plan-related information.
     *
     * <p>Note: This field is set via {@link #attachPlanNote(PlanNotebook)} method,
     * typically called after ReActAgent is created and has a PlanNotebook instance.
     */
    private PlanNotebook planNotebook;

    /**
     * Custom prompt configuration from AutoContextConfig.
     * If null, default prompts from {@link Prompts} will be used.
     */
    private final PromptConfig customPrompt;

    /**
     * Creates a new AutoContextMemory instance with the specified configuration and model.
     *
     * @param autoContextConfig the configuration for auto context management
     * @param model the LLM model to use for compression and summarization
     */
    public AutoContextMemory(AutoContextConfig autoContextConfig, Model model) {
        this.model = model;
        this.autoContextConfig = autoContextConfig;
        this.customPrompt = autoContextConfig.getCustomPrompt();
        workingMemoryStorage = new ArrayList<>();
        originalMemoryStorage = new ArrayList<>();
        offloadContext = new HashMap<>();
        compressionEvents = new ArrayList<>();
    }

    @Override
    public void addMessage(Msg message) {
        workingMemoryStorage.add(message);
        originalMemoryStorage.add(message);
    }

    @Override
    public List<Msg> getMessages() {
        // Read-only: return a copy of working memory messages without triggering compression
        return new ArrayList<>(workingMemoryStorage);
    }

    /**
     * Compresses the working memory if thresholds are reached.
     *
     * <p>This method checks if compression is needed based on message count and token count
     * thresholds, and applies compression strategies if necessary. The compression modifies
     * the working memory storage in place.
     *
     * <p>This method should be called at a deterministic point in the execution flow,
     * typically via a PreReasoningHook, to ensure compression happens before LLM reasoning.
     *
     * <p>Compression strategies are applied in order until one succeeds:
     * <ol>
     *   <li>Compress previous round tool invocations</li>
     *   <li>Offload previous round large messages (with lastKeep protection)</li>
     *   <li>Offload previous round large messages (without lastKeep protection)</li>
     *   <li>Summarize previous round conversations</li>
     *   <li>Summarize and offload current round large messages</li>
     *   <li>Summarize current round messages</li>
     * </ol>
     *
     * @return true if compression was performed, false if no compression was needed
     */
    public boolean compressIfNeeded() {
        List<Msg> currentContextMessages = new ArrayList<>(workingMemoryStorage);

        // Check if compression is needed
        boolean msgCountReached = currentContextMessages.size() >= autoContextConfig.msgThreshold;
        int calculateToken = TokenCounterUtil.calculateToken(currentContextMessages);
        int thresholdToken = (int) (autoContextConfig.maxToken * autoContextConfig.tokenRatio);
        boolean tokenCounterReached = calculateToken >= thresholdToken;

        if (!msgCountReached && !tokenCounterReached) {
            return false;
        }

        // Compression triggered - log threshold information
        log.info(
                "Compression triggered - msgCount: {}/{}, tokenCount: {}/{}",
                currentContextMessages.size(),
                autoContextConfig.msgThreshold,
                calculateToken,
                thresholdToken);

        // Strategy 1: Compress previous round tool invocations
        log.info("Strategy 1: Checking for previous round tool invocations to compress");
        int toolIters = 5;
        boolean toolCompressed = false;
        int compressionCount = 0;
        while (toolIters > 0) {
            toolIters--;
            List<Msg> currentMsgs = new ArrayList<>(workingMemoryStorage);
            Pair<Integer, Integer> toolMsgIndices =
                    extractPrevToolMsgsForCompress(currentMsgs, autoContextConfig.getLastKeep());
            if (toolMsgIndices != null) {
                summaryToolsMessages(currentMsgs, toolMsgIndices);
                replaceWorkingMessage(currentMsgs);
                toolCompressed = true;
                compressionCount++;
            } else {
                break;
            }
        }
        if (toolCompressed) {
            log.info(
                    "Strategy 1: APPLIED - Compressed {} tool invocation groups", compressionCount);
            return true;
        } else {
            log.info("Strategy 1: SKIPPED - No compressible tool invocations found");
        }

        // Strategy 2: Offload previous round large messages (with lastKeep protection)
        log.info(
                "Strategy 2: Checking for previous round large messages (with lastKeep"
                        + " protection)");
        boolean hasOffloadedLastKeep = offloadingLargePayload(currentContextMessages, true);
        if (hasOffloadedLastKeep) {
            log.info(
                    "Strategy 2: APPLIED - Offloaded previous round large messages (with lastKeep"
                            + " protection)");
            replaceWorkingMessage(currentContextMessages);
            return true;
        } else {
            log.info("Strategy 2: SKIPPED - No large messages found or protected by lastKeep");
        }

        // Strategy 3: Offload previous round large messages (without lastKeep protection)
        log.info(
                "Strategy 3: Checking for previous round large messages (without lastKeep"
                        + " protection)");
        boolean hasOffloaded = offloadingLargePayload(currentContextMessages, false);
        if (hasOffloaded) {
            log.info("Strategy 3: APPLIED - Offloaded previous round large messages");
            replaceWorkingMessage(currentContextMessages);
            return true;
        } else {
            log.info("Strategy 3: SKIPPED - No large messages found");
        }

        // Strategy 4: Summarize previous round conversations
        log.info("Strategy 4: Checking for previous round conversations to summarize");
        boolean hasSummarized = summaryPreviousRoundMessages(currentContextMessages);
        if (hasSummarized) {
            log.info("Strategy 4: APPLIED - Summarized previous round conversations");
            replaceWorkingMessage(currentContextMessages);
            return true;
        } else {
            log.info("Strategy 4: SKIPPED - No previous round conversations to summarize");
        }

        // Strategy 5: Summarize and offload current round large messages
        log.info("Strategy 5: Checking for current round large messages to summarize");
        boolean currentRoundLargeSummarized =
                summaryCurrentRoundLargeMessages(currentContextMessages);
        if (currentRoundLargeSummarized) {
            log.info("Strategy 5: APPLIED - Summarized and offloaded current round large messages");
            replaceWorkingMessage(currentContextMessages);
            return true;
        } else {
            log.info("Strategy 5: SKIPPED - No current round large messages found");
        }

        // Strategy 6: Summarize current round messages
        log.info("Strategy 6: Checking for current round messages to summarize");
        boolean currentRoundSummarized = summaryCurrentRoundMessages(currentContextMessages);
        if (currentRoundSummarized) {
            log.info("Strategy 6: APPLIED - Summarized current round messages");
            replaceWorkingMessage(currentContextMessages);
            return true;
        } else {
            log.info("Strategy 6: SKIPPED - No current round messages to summarize");
        }

        log.warn("All compression strategies exhausted but context still exceeds threshold");
        return false;
    }

    private List<Msg> replaceWorkingMessage(List<Msg> newMessages) {
        workingMemoryStorage.clear();
        for (Msg msg : newMessages) {
            workingMemoryStorage.add(msg);
        }
        return new ArrayList<>(workingMemoryStorage);
    }

    /**
     * Records a compression event that occurred during context management.
     *
     * @param eventType the type of compression event
     * @param startIndex the start index of the compressed message range in allMessages
     * @param endIndex the end index of the compressed message range in allMessages
     * @param allMessages the complete message list (before compression)
     * @param compressedMessage the compressed message (null if not a compression type)
     * @param metadata additional metadata for the event (may contain inputToken, outputToken, etc.)
     */
    private void recordCompressionEvent(
            String eventType,
            int startIndex,
            int endIndex,
            List<Msg> allMessages,
            Msg compressedMessage,
            Map<String, Object> metadata) {
        int compressedMessageCount = endIndex - startIndex + 1;
        String previousMessageId = startIndex > 0 ? allMessages.get(startIndex - 1).getId() : null;
        String nextMessageId =
                endIndex < allMessages.size() - 1 ? allMessages.get(endIndex + 1).getId() : null;
        String compressedMessageId = compressedMessage != null ? compressedMessage.getId() : null;

        CompressionEvent event =
                new CompressionEvent(
                        eventType,
                        System.currentTimeMillis(),
                        compressedMessageCount,
                        previousMessageId,
                        nextMessageId,
                        compressedMessageId,
                        metadata != null ? new HashMap<>(metadata) : new HashMap<>());

        compressionEvents.add(event);
    }

    /**
     * Summarize current round of conversation messages.
     *
     * <p>This method is called when historical messages have been compressed and offloaded,
     * but the context still exceeds the limit. This indicates that the current round's content
     * is too large and needs compression.
     *
     * <p>Strategy:
     * 1. Find the latest user message
     * 2. Merge and compress all messages after it (typically tool calls and tool results,
     *    usually no assistant message yet)
     * 3. Preserve tool call interfaces (name, parameters)
     * 4. Compress tool results, merging multiple results and keeping key information
     *
     * @param rawMessages the list of messages to process
     * @return true if summary was actually performed, false otherwise
     */
    private boolean summaryCurrentRoundMessages(List<Msg> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return false;
        }

        // Step 1: Find the latest user message
        int latestUserIndex = -1;
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                latestUserIndex = i;
                break;
            }
        }

        // If no user message found, nothing to summarize
        if (latestUserIndex < 0) {
            return false;
        }

        // Step 2: Check if there are messages after the user message
        if (latestUserIndex >= rawMessages.size() - 1) {
            return false;
        }

        // Step 3: Extract messages after the latest user message
        int startIndex = latestUserIndex + 1;
        int endIndex = rawMessages.size() - 1;

        // Ensure tool use and tool result are paired: if the last message is ToolUse,
        // move endIndex back by one to exclude the incomplete tool invocation
        if (endIndex >= startIndex) {
            Msg lastMsg = rawMessages.get(endIndex);
            if (MsgUtils.isToolUseMessage(lastMsg)) {
                endIndex--;
                // If no messages left after adjustment, cannot compress
                if (endIndex < startIndex) {
                    return false;
                }
            }
        }

        List<Msg> messagesToCompress = new ArrayList<>();
        for (int i = startIndex; i <= endIndex; i++) {
            messagesToCompress.add(rawMessages.get(i));
        }

        log.info(
                "Compressing current round messages: userIndex={}, messageCount={}",
                latestUserIndex,
                messagesToCompress.size());

        // Step 4: Merge and compress messages (typically tool calls and results)
        Msg compressedMsg = mergeAndCompressCurrentRoundMessages(messagesToCompress);

        // Build metadata for compression event
        Map<String, Object> metadata = new HashMap<>();
        if (compressedMsg.getChatUsage() != null) {
            metadata.put("inputToken", compressedMsg.getChatUsage().getInputTokens());
            metadata.put("outputToken", compressedMsg.getChatUsage().getOutputTokens());
            metadata.put("time", compressedMsg.getChatUsage().getTime());
        }

        // Record compression event (before replacing messages to preserve indices)
        recordCompressionEvent(
                CompressionEvent.CURRENT_ROUND_MESSAGE_COMPRESS,
                startIndex,
                endIndex,
                rawMessages,
                compressedMsg,
                metadata);

        // Step 5: Replace original messages with compressed one
        rawMessages.subList(startIndex, endIndex + 1).clear();
        rawMessages.add(startIndex, compressedMsg);

        log.info(
                "Replaced {} messages with 1 compressed message at index {}",
                messagesToCompress.size(),
                startIndex);
        return true;
    }

    /**
     * Summarize large messages in the current round that exceed the threshold.
     *
     * <p>This method is called to compress large messages in the current round (messages after
     * the latest user message) that exceed the largePayloadThreshold. Unlike simple offloading
     * which only provides a preview, this method uses LLM to generate intelligent summaries
     * while preserving critical information.
     *
     * <p>Strategy:
     * 1. Find the latest user message
     * 2. Check messages after it for content exceeding largePayloadThreshold
     * 3. For each large message, generate an LLM summary and offload the original
     * 4. Replace large messages with summarized versions
     *
     * @param rawMessages the list of messages to process
     * @return true if any messages were summarized and offloaded, false otherwise
     */
    private boolean summaryCurrentRoundLargeMessages(List<Msg> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return false;
        }

        // Step 1: Find the latest user message
        int latestUserIndex = -1;
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                latestUserIndex = i;
                break;
            }
        }

        // If no user message found, nothing to process
        if (latestUserIndex < 0) {
            return false;
        }

        // Step 2: Check if there are messages after the user message
        if (latestUserIndex >= rawMessages.size() - 1) {
            return false;
        }

        // Step 3: Process messages after the latest user message
        // Process in reverse order to avoid index shifting issues when replacing
        boolean hasSummarized = false;
        long threshold = autoContextConfig.largePayloadThreshold;

        for (int i = rawMessages.size() - 1; i > latestUserIndex; i--) {
            Msg msg = rawMessages.get(i);
            String textContent = msg.getTextContent();

            // Check if message content exceeds threshold
            if (textContent == null || textContent.length() <= threshold) {
                continue;
            }

            // Step 4: Offload the original message
            String uuid = UUID.randomUUID().toString();
            List<Msg> offloadMsg = new ArrayList<>();
            offloadMsg.add(msg);
            offload(uuid, offloadMsg);
            log.info(
                    "Offloaded current round large message: index={}, size={} chars, uuid={}",
                    i,
                    textContent.length(),
                    uuid);

            // Step 5: Generate summary using LLM
            Msg summaryMsg = generateLargeMessageSummary(msg, uuid);

            // Build metadata for compression event
            Map<String, Object> metadata = new HashMap<>();
            if (summaryMsg.getChatUsage() != null) {
                metadata.put("inputToken", summaryMsg.getChatUsage().getInputTokens());
                metadata.put("outputToken", summaryMsg.getChatUsage().getOutputTokens());
                metadata.put("time", summaryMsg.getChatUsage().getTime());
            }

            // Record compression event
            recordCompressionEvent(
                    CompressionEvent.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY,
                    i,
                    i,
                    rawMessages,
                    summaryMsg,
                    metadata);

            // Step 6: Replace the original message with summary
            rawMessages.set(i, summaryMsg);
            hasSummarized = true;

            log.info(
                    "Replaced large message at index {} with summarized version (uuid: {})",
                    i,
                    uuid);
        }

        return hasSummarized;
    }

    /**
     * Generate a summary of a large message using the model.
     *
     * @param message the message to summarize
     * @param offloadUuid the UUID of offloaded message
     * @return a summary message preserving the original role and name
     */
    private Msg generateLargeMessageSummary(Msg message, String offloadUuid) {
        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("large_message_summary");

        String offloadHint =
                offloadUuid != null ? String.format(Prompts.OFFLOAD_HINT, offloadUuid) : "";

        List<Msg> newMessages = new ArrayList<>();
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(
                                                PromptProvider.getCurrentRoundLargeMessagePrompt(
                                                        customPrompt))
                                        .build())
                        .build());
        newMessages.add(message);
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.COMPRESSION_MESSAGE_LIST_END)
                                        .build())
                        .build());
        // Insert plan-aware hint message at the end to leverage recency effect
        addPlanAwareHintIfNeeded(newMessages);

        Msg block =
                model.stream(newMessages, null, options)
                        .concatMap(chunk -> processChunk(chunk, context))
                        .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                        .onErrorResume(InterruptedException.class, Mono::error)
                        .block();

        if (block != null && block.getChatUsage() != null) {
            log.info(
                    "Large message summary completed, input tokens: {}, output tokens: {}",
                    block.getChatUsage().getInputTokens(),
                    block.getChatUsage().getOutputTokens());
        }

        // Build metadata with compression information
        Map<String, Object> compressMeta = new HashMap<>();
        if (offloadUuid != null) {
            compressMeta.put("offloaduuid", offloadUuid);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_compress_meta", compressMeta);

        // Preserve _chat_usage from the block if available
        if (block != null && block.getChatUsage() != null) {
            metadata.put(MessageMetadataKeys.CHAT_USAGE, block.getChatUsage());
        }

        // Create summary message preserving original role and name
        return Msg.builder()
                .role(message.getRole())
                .name(message.getName())
                .content(
                        TextBlock.builder()
                                .text(
                                        String.format(
                                                "<compressed_large_message>%s</compressed_large_message>%s",
                                                block != null ? block.getTextContent() : "",
                                                offloadHint))
                                .build())
                .metadata(metadata)
                .build();
    }

    /**
     * Merge and compress current round messages (typically tool calls and tool results).
     *
     * @param messages the messages to merge and compress
     * @return compressed message
     */
    private Msg mergeAndCompressCurrentRoundMessages(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        // Offload original messages
        String uuid = UUID.randomUUID().toString();
        List<Msg> originalMessages = new ArrayList<>(messages);
        offload(uuid, originalMessages);

        // Use model to generate a compressed summary from message list
        return generateCurrentRoundSummaryFromMessages(messages, uuid);
    }

    @Override
    public void offload(String uuid, List<Msg> messages) {
        offloadContext.put(uuid, messages);
    }

    @Override
    public List<Msg> reload(String uuid) {
        List<Msg> messages = offloadContext.get(uuid);
        return messages != null ? messages : new ArrayList<>();
    }

    @Override
    public void clear(String uuid) {
        offloadContext.remove(uuid);
    }

    /**
     * Generate a compressed summary of current round messages using the model.
     *
     * @param messages the messages to summarize
     * @param offloadUuid the UUID of offloaded content (if any)
     * @return compressed message
     */
    private Msg generateCurrentRoundSummaryFromMessages(List<Msg> messages, String offloadUuid) {
        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("current_round_compress");

        // Calculate original character count (including TextBlock, ToolUseBlock, ToolResultBlock)
        int originalCharCount = MsgUtils.calculateMessagesCharCount(messages);

        // Get compression ratio and calculate target character count
        double compressionRatio = autoContextConfig.getCurrentRoundCompressionRatio();
        int compressionRatioPercent = (int) Math.round(compressionRatio * 100);
        int targetCharCount = (int) Math.round(originalCharCount * compressionRatio);

        String offloadHint =
                offloadUuid != null ? String.format(Prompts.OFFLOAD_HINT, offloadUuid) : "";

        // Build character count requirement message
        String charRequirement =
                String.format(
                        Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_CHAR_REQUIREMENT,
                        originalCharCount,
                        targetCharCount,
                        (double) compressionRatioPercent,
                        (double) compressionRatioPercent);

        List<Msg> newMessages = new ArrayList<>();
        // First message: main compression prompt (without character count requirement)
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(
                                                PromptProvider.getCurrentRoundCompressPrompt(
                                                        customPrompt))
                                        .build())
                        .build());
        newMessages.addAll(messages);
        // Message list end marker
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.COMPRESSION_MESSAGE_LIST_END)
                                        .build())
                        .build());
        // Character count requirement (placed after message list end)
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(TextBlock.builder().text(charRequirement).build())
                        .build());
        // Insert plan-aware hint message at the end to leverage recency effect
        addPlanAwareHintIfNeeded(newMessages);

        Msg block =
                model.stream(newMessages, null, options)
                        .concatMap(chunk -> processChunk(chunk, context))
                        .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                        .onErrorResume(InterruptedException.class, Mono::error)
                        .block();

        // Extract token usage information
        int inputTokens = 0;
        int outputTokens = 0;
        if (block != null && block.getChatUsage() != null) {
            inputTokens = block.getChatUsage().getInputTokens();
            outputTokens = block.getChatUsage().getOutputTokens();
        }

        // Calculate actual output character count (including all content blocks)
        int actualCharCount = block != null ? MsgUtils.calculateMessageCharCount(block) : 0;

        log.info(
                "Current round summary completed - original: {} chars, target: {} chars ({}%),"
                        + " actual: {} chars, input tokens: {}, output tokens: {}",
                originalCharCount,
                targetCharCount,
                compressionRatioPercent,
                actualCharCount,
                inputTokens,
                outputTokens);

        // Build metadata with compression information
        Map<String, Object> compressMeta = new HashMap<>();
        if (offloadUuid != null) {
            compressMeta.put("offloaduuid", offloadUuid);
        }
        // Mark this as a compressed current round message to avoid being treated as a real
        // assistant response
        compressMeta.put("compressed_current_round", true);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_compress_meta", compressMeta);
        if (block != null && block.getChatUsage() != null) {
            metadata.put(MessageMetadataKeys.CHAT_USAGE, block.getChatUsage());
        }

        // Create a compressed message
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        TextBlock.builder()
                                .text((block != null ? block.getTextContent() : "") + offloadHint)
                                .build())
                .metadata(metadata)
                .build();
    }

    /**
     * Summarize current round of conversation messages.
     *
     * @param rawMessages the list of messages to process
     * @return true if summary was actually performed, false otherwise
     */
    private void summaryToolsMessages(
            List<Msg> rawMessages, Pair<Integer, Integer> toolMsgIndices) {
        int startIndex = toolMsgIndices.first();
        int endIndex = toolMsgIndices.second();
        int toolMsgCount = endIndex - startIndex + 1;
        log.info(
                "Compressing tool invocations: indices [{}, {}], count: {}",
                startIndex,
                endIndex,
                toolMsgCount);

        List<Msg> toolsMsg = new ArrayList<>();
        for (int i = startIndex; i <= endIndex; i++) {
            toolsMsg.add(rawMessages.get(i));
        }

        // Normal compression flow for non-plan tools
        String uuid = UUID.randomUUID().toString();
        offload(uuid, toolsMsg);

        Msg toolsSummary = compressToolsInvocation(toolsMsg, uuid);

        // Build metadata for compression event
        Map<String, Object> metadata = new HashMap<>();
        if (toolsSummary.getChatUsage() != null) {
            metadata.put("inputToken", toolsSummary.getChatUsage().getInputTokens());
            metadata.put("outputToken", toolsSummary.getChatUsage().getOutputTokens());
            metadata.put("time", toolsSummary.getChatUsage().getTime());
        }

        // Record compression event
        recordCompressionEvent(
                CompressionEvent.TOOL_INVOCATION_COMPRESS,
                startIndex,
                endIndex,
                rawMessages,
                toolsSummary,
                metadata);

        MsgUtils.replaceMsg(rawMessages, startIndex, endIndex, toolsSummary);
    }

    /**
     * Summarize all previous rounds of conversation messages before the latest assistant.
     *
     * <p>This method finds the latest assistant message and summarizes all conversation rounds
     * before it. Each round consists of messages between a user message and its corresponding
     * assistant message (typically including tool calls/results and the assistant message itself).
     *
     * <p>Example transformation:
     * Before: "user1-tools-assistant1, user2-tools-assistant2, user3-tools-assistant3, user4"
     * After:  "user1-summary, user2-summary, user3-summary, user4"
     * Where each summary contains the compressed information from tools and assistant of that round.
     *
     * <p>Strategy:
     * 1. Find the latest assistant message (this is the current round, not to be summarized)
     * 2. From the beginning, find all user-assistant pairs before the latest assistant
     * 3. For each pair, summarize messages between user and assistant (including assistant message)
     * 4. Replace those messages (including assistant) with summary (process from back to front to avoid index shifting)
     *
     * @param rawMessages the list of messages to process
     * @return true if summary was actually performed, false otherwise
     */
    private boolean summaryPreviousRoundMessages(List<Msg> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return false;
        }

        // Step 1: Find the latest assistant message that is a final response (not a tool call)
        int latestAssistantIndex = -1;
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (MsgUtils.isFinalAssistantResponse(msg)) {
                latestAssistantIndex = i;
                break;
            }
        }

        // If no assistant message found, nothing to summarize
        if (latestAssistantIndex < 0) {
            return false;
        }

        // Step 2: Find all user-assistant pairs before the latest assistant
        // We'll collect them as pairs: (userIndex, assistantIndex)
        List<Pair<Integer, Integer>> userAssistantPairs = new ArrayList<>();
        int currentUserIndex = -1;

        for (int i = 0; i < latestAssistantIndex; i++) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                currentUserIndex = i;
            } else if (MsgUtils.isFinalAssistantResponse(msg) && currentUserIndex >= 0) {
                // Found a user-assistant pair (assistant message is a final response, not a tool
                // call)
                if (i - currentUserIndex != 1) {
                    userAssistantPairs.add(new Pair<>(currentUserIndex, i));
                }

                currentUserIndex = -1; // Reset to find next pair
            }
        }

        // If no pairs found, nothing to summarize
        if (userAssistantPairs.isEmpty()) {
            return false;
        }

        log.info(
                "Found {} user-assistant pairs to summarize before latest assistant at index {}",
                userAssistantPairs.size(),
                latestAssistantIndex);

        // Step 3: Process pairs from back to front to avoid index shifting issues
        boolean hasSummarized = false;
        for (int pairIdx = userAssistantPairs.size() - 1; pairIdx >= 0; pairIdx--) {
            Pair<Integer, Integer> pair = userAssistantPairs.get(pairIdx);
            int userIndex = pair.first();
            int assistantIndex = pair.second();

            // Messages to summarize: between user and assistant (inclusive of assistant)
            // This includes all messages after user (tools, etc.) and the assistant message itself
            int startIndex = userIndex + 1;
            int endIndex = assistantIndex; // Include assistant message in summary

            // If no messages between user and assistant (including assistant), skip
            if (startIndex > endIndex) {
                log.info(
                        "No messages to summarize between user at index {} and assistant at index"
                                + " {}",
                        userIndex,
                        assistantIndex);
                continue;
            }

            List<Msg> messagesToSummarize = new ArrayList<>();
            for (int i = startIndex; i <= endIndex; i++) {
                messagesToSummarize.add(rawMessages.get(i));
            }

            log.info(
                    "Summarizing round {}: indices [{}, {}], messageCount={}",
                    pairIdx + 1,
                    startIndex,
                    endIndex,
                    messagesToSummarize.size());

            // Step 4: Offload original messages if contextOffLoader is available
            String uuid = UUID.randomUUID().toString();
            offload(uuid, messagesToSummarize);
            log.info("Offloaded messages to be summarized: uuid={}", uuid);

            // Step 5: Generate summary
            Msg summaryMsg = summaryPreviousRoundConversation(messagesToSummarize, uuid);

            // Build metadata for compression event
            Map<String, Object> metadata = new HashMap<>();
            if (summaryMsg.getChatUsage() != null) {
                metadata.put("inputToken", summaryMsg.getChatUsage().getInputTokens());
                metadata.put("outputToken", summaryMsg.getChatUsage().getOutputTokens());
                metadata.put("time", summaryMsg.getChatUsage().getTime());
            }

            // Record compression event (before removing messages to preserve indices)
            recordCompressionEvent(
                    CompressionEvent.PREVIOUS_ROUND_CONVERSATION_SUMMARY,
                    startIndex,
                    endIndex,
                    rawMessages,
                    summaryMsg,
                    metadata);

            // Step 6: Remove the messages between user and assistant (including assistant), then
            // replace with summary
            // Since we're processing from back to front, the indices are still accurate
            // for the current pair (indices of pairs after this one have already been adjusted)

            // Remove messages from startIndex to endIndex (including assistant, from back to front
            // to avoid index shifting)
            int removedCount = endIndex - startIndex + 1;
            rawMessages.subList(startIndex, endIndex + 1).clear();

            // After removal, the position where assistant was is now: assistantIndex - removedCount
            // + 1
            // But since we removed everything including assistant, we insert summary at the
            // position after user
            int insertIndex = userIndex + 1;

            // Insert summary after user (replacing the removed messages including assistant)
            rawMessages.add(insertIndex, summaryMsg);

            log.info(
                    "Replaced {} messages [indices {}-{}] with summary at index {}",
                    removedCount,
                    startIndex,
                    endIndex,
                    insertIndex);

            hasSummarized = true;
        }

        return hasSummarized;
    }

    /**
     * Generate a summary of previous round conversation messages using the model.
     *
     * @param messages the messages to summarize
     * @param offloadUuid the UUID of offloaded messages (if any), null otherwise
     * @return a summary message
     */
    private Msg summaryPreviousRoundConversation(List<Msg> messages, String offloadUuid) {
        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("conversation_summary");

        String summaryContentFormat =
                Prompts.PREVIOUS_ROUND_CONVERSATION_SUMMARY_FORMAT
                        + (offloadUuid != null
                                ? String.format(Prompts.OFFLOAD_HINT, offloadUuid)
                                : "");

        List<Msg> newMessages = new ArrayList<>();
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(
                                                PromptProvider.getPreviousRoundSummaryPrompt(
                                                        customPrompt))
                                        .build())
                        .build());
        newMessages.addAll(messages);
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.COMPRESSION_MESSAGE_LIST_END)
                                        .build())
                        .build());
        // Insert plan-aware hint message at the end to leverage recency effect
        addPlanAwareHintIfNeeded(newMessages);

        Msg block =
                model.stream(newMessages, null, options)
                        .concatMap(chunk -> processChunk(chunk, context))
                        .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                        .onErrorResume(InterruptedException.class, Mono::error)
                        .block();

        // Extract token usage information
        int inputTokens = 0;
        int outputTokens = 0;
        if (block != null && block.getChatUsage() != null) {
            inputTokens = block.getChatUsage().getInputTokens();
            outputTokens = block.getChatUsage().getOutputTokens();
            log.info(
                    "Conversation summary completed, input tokens: {}, output tokens: {}",
                    inputTokens,
                    outputTokens);
        }

        // Build metadata with compression information
        Map<String, Object> compressMeta = new HashMap<>();
        if (offloadUuid != null) {
            compressMeta.put("offloaduuid", offloadUuid);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_compress_meta", compressMeta);

        // Preserve _chat_usage from the block if available
        if (block != null && block.getChatUsage() != null) {
            metadata.put(MessageMetadataKeys.CHAT_USAGE, block.getChatUsage());
        }

        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        TextBlock.builder()
                                .text(
                                        String.format(
                                                summaryContentFormat,
                                                block != null ? block.getTextContent() : ""))
                                .build())
                .metadata(metadata)
                .build();
    }

    /**
     * Offload large payload messages that exceed the threshold.
     *
     * <p>This method finds messages before the latest assistant response that exceed
     * the largePayloadThreshold, offloads them to storage, and replaces them with
     * a summary containing the first 100 characters and a hint to reload if needed.
     *
     * @param rawMessages the list of messages to process
     * @param lastKeep whether to keep the last N messages (unused in current implementation)
     * @return true if any messages were offloaded, false otherwise
     */
    private boolean offloadingLargePayload(List<Msg> rawMessages, boolean lastKeep) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return false;
        }

        // Strategy 1: If rawMessages has less than lastKeep messages, skip
        if (rawMessages.size() < autoContextConfig.getLastKeep()) {
            return false;
        }

        // Strategy 2: Find the latest assistant message that is a final response and protect it and
        // all messages after it
        int latestAssistantIndex = -1;
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (MsgUtils.isFinalAssistantResponse(msg)) {
                latestAssistantIndex = i;
                break;
            }
        }

        // Determine the search end index based on lastKeep parameter
        int searchEndIndex;
        if (lastKeep) {
            // If lastKeep is true, protect the last N messages
            int lastKeepCount = autoContextConfig.getLastKeep();
            int protectedStartIndex = Math.max(0, rawMessages.size() - lastKeepCount);

            if (latestAssistantIndex >= 0) {
                // Protect both the latest assistant and the last N messages
                // Use the earlier index to ensure both are protected
                searchEndIndex = Math.min(latestAssistantIndex, protectedStartIndex);
            } else {
                // No assistant found, protect the last N messages
                searchEndIndex = protectedStartIndex;
            }
        } else {
            // If lastKeep is false, only protect up to the latest assistant (if found)
            searchEndIndex = (latestAssistantIndex >= 0) ? latestAssistantIndex : 0;
        }

        boolean hasOffloaded = false;
        long threshold = autoContextConfig.largePayloadThreshold;

        // Process messages from the beginning up to the search end index
        // Process in reverse order to avoid index shifting issues when replacing
        for (int i = searchEndIndex - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            String textContent = msg.getTextContent();

            String uuid = null;
            // Check if message content exceeds threshold
            if (textContent != null && textContent.length() > threshold) {
                // Offload the original message
                uuid = UUID.randomUUID().toString();
                List<Msg> offloadMsg = new ArrayList<>();
                offloadMsg.add(msg);
                offload(uuid, offloadMsg);
                log.info(
                        "Offloaded large message: index={}, size={} chars, uuid={}",
                        i,
                        textContent.length(),
                        uuid);
            }
            if (uuid == null) {
                continue;
            }

            // Create replacement message with first autoContextConfig.offloadSinglePreview
            // characters and offload hint
            String preview =
                    textContent.length() > autoContextConfig.offloadSinglePreview
                            ? textContent.substring(0, autoContextConfig.offloadSinglePreview)
                                    + "..."
                            : textContent;

            String offloadHint = preview + "\n" + String.format(Prompts.OFFLOAD_HINT, uuid);

            // Build metadata with compression information
            // Note: This method only offloads without LLM compression, so tokens are 0
            Map<String, Object> compressMeta = new HashMap<>();
            compressMeta.put("offloaduuid", uuid);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("_compress_meta", compressMeta);

            // Create replacement message preserving original role and name
            Msg replacementMsg =
                    Msg.builder()
                            .role(msg.getRole())
                            .name(msg.getName())
                            .content(TextBlock.builder().text(offloadHint).build())
                            .metadata(metadata)
                            .build();

            // Calculate token counts before and after offload
            int tokenBefore = TokenCounterUtil.calculateToken(List.of(msg));
            int tokenAfter = TokenCounterUtil.calculateToken(List.of(replacementMsg));

            // Build metadata for compression event (offload doesn't use LLM, so no compression
            // tokens)
            Map<String, Object> eventMetadata = new HashMap<>();
            eventMetadata.put("inputToken", tokenBefore);
            eventMetadata.put("outputToken", tokenAfter);
            eventMetadata.put("time", 0.0);

            // Record compression event (offload doesn't use LLM, so compressedMessage is null)
            String eventType =
                    lastKeep
                            ? CompressionEvent.LARGE_MESSAGE_OFFLOAD_WITH_PROTECTION
                            : CompressionEvent.LARGE_MESSAGE_OFFLOAD;
            recordCompressionEvent(eventType, i, i, rawMessages, null, eventMetadata);

            // Replace the original message
            rawMessages.set(i, replacementMsg);
            hasOffloaded = true;
        }

        return hasOffloaded;
    }

    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < workingMemoryStorage.size()) {
            workingMemoryStorage.remove(index);
        }
    }

    /**
     * Extract tool messages from raw messages for compression.
     *
     * <p>This method finds consecutive tool invocation messages in historical conversations
     * that can be compressed. It searches for sequences of more than  consecutive tool messages
     * before the latest assistant message.
     *
     * <p>Strategy:
     * 1. If rawMessages has less than lastKeep messages, return null
     * 2. Find the latest assistant message and protect it and all messages after it
     * 3. Search from the beginning for the oldest consecutive tool messages (more than minConsecutiveToolMessages consecutive)
     *    that can be compressed
     * 4. If no assistant message is found, protect the last N messages (lastKeep)
     *
     * @param rawMessages all raw messages
     * @param lastKeep number of recent messages to keep uncompressed
     * @return Pair containing startIndex and endIndex (inclusive) of compressible tool messages, or null if none found
     */
    private Pair<Integer, Integer> extractPrevToolMsgsForCompress(
            List<Msg> rawMessages, int lastKeep) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return null;
        }

        int totalSize = rawMessages.size();

        // Step 1: If rawMessages has less than lastKeep messages, return null
        if (totalSize < lastKeep) {
            return null;
        }

        // Step 2: Find the latest assistant message that is a final response and protect it and all
        // messages after it
        int latestAssistantIndex = -1;
        for (int i = totalSize - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (MsgUtils.isFinalAssistantResponse(msg)) {
                latestAssistantIndex = i;
                break;
            }
        }
        if (latestAssistantIndex == -1) {
            return null;
        }
        // Determine the search boundary: we can only search messages before the latest assistant
        int searchEndIndex = Math.min(latestAssistantIndex, (totalSize - lastKeep));

        // Step 3: Find the oldest consecutive tool messages (more than minConsecutiveToolMessages
        // consecutive)
        // Search from the beginning (oldest messages first) until we find a sequence
        int consecutiveCount = 0;
        int startIndex = -1;
        int endIndex = -1;

        for (int i = 0; i < searchEndIndex; i++) {
            Msg msg = rawMessages.get(i);
            if (MsgUtils.isToolMessage(msg)) {
                if (consecutiveCount == 0) {
                    startIndex = i;
                }
                consecutiveCount++;
            } else {
                // If we found enough consecutive tool messages, return their indices
                if (consecutiveCount > autoContextConfig.minConsecutiveToolMessages) {
                    endIndex = i - 1; // endIndex is inclusive
                    // Adjust indices: ensure startIndex is ToolUse and endIndex is ToolResult
                    int adjustedStart = startIndex;
                    int adjustedEnd = endIndex;

                    // Adjust startIndex forward to find ToolUse
                    while (adjustedStart <= adjustedEnd
                            && !MsgUtils.isToolUseMessage(rawMessages.get(adjustedStart))) {
                        if (MsgUtils.isToolResultMessage(rawMessages.get(adjustedStart))) {
                            adjustedStart++;
                        } else {
                            break; // Invalid sequence, continue searching
                        }
                    }

                    // Adjust endIndex backward to find ToolResult
                    while (adjustedEnd >= adjustedStart
                            && !MsgUtils.isToolResultMessage(rawMessages.get(adjustedEnd))) {
                        if (MsgUtils.isToolUseMessage(rawMessages.get(adjustedEnd))) {
                            adjustedEnd--;
                        } else {
                            break; // Invalid sequence, continue searching
                        }
                    }

                    // Check if we still have enough consecutive tool messages after adjustment
                    if (adjustedStart <= adjustedEnd
                            && adjustedEnd - adjustedStart + 1
                                    > autoContextConfig.minConsecutiveToolMessages) {
                        return new Pair<>(adjustedStart, adjustedEnd);
                    }
                }
                // Reset counter if sequence is broken
                consecutiveCount = 0;
                startIndex = -1;
            }
        }

        // Check if there's a sequence at the end of the search range
        if (consecutiveCount > autoContextConfig.minConsecutiveToolMessages) {
            endIndex = searchEndIndex - 1; // endIndex is inclusive
            // Adjust indices: ensure startIndex is ToolUse and endIndex is ToolResult
            int adjustedStart = startIndex;
            int adjustedEnd = endIndex;

            // Adjust startIndex forward to find ToolUse
            while (adjustedStart <= adjustedEnd
                    && !MsgUtils.isToolUseMessage(rawMessages.get(adjustedStart))) {
                if (MsgUtils.isToolResultMessage(rawMessages.get(adjustedStart))) {
                    adjustedStart++;
                } else {
                    return null; // Invalid sequence
                }
            }

            // Adjust endIndex backward to find ToolResult
            while (adjustedEnd >= adjustedStart
                    && !MsgUtils.isToolResultMessage(rawMessages.get(adjustedEnd))) {
                if (MsgUtils.isToolUseMessage(rawMessages.get(adjustedEnd))) {
                    adjustedEnd--;
                } else {
                    return null; // Invalid sequence
                }
            }

            // Check if we still have enough consecutive tool messages after adjustment
            if (adjustedStart <= adjustedEnd
                    && adjustedEnd - adjustedStart + 1
                            > autoContextConfig.minConsecutiveToolMessages) {
                return new Pair<>(adjustedStart, adjustedEnd);
            }
        }

        return null;
    }

    /**
     * Compresses a list of tool invocation messages using LLM summarization.
     *
     * <p>This method uses an LLM model to intelligently compress tool invocation messages,
     * preserving key information such as tool names, parameters, and important results while
     * reducing the overall token count. The compression is performed as part of Strategy 1
     * (compress historical tool invocations) to manage context window limits.
     *
     * <p><b>Process:</b>
     * <ol>
     *   <li>Constructs a prompt with the tool invocation messages sandwiched between
     *       compression instructions</li>
     *   <li>Sends the prompt to the LLM model for summarization</li>
     *   <li>Formats the compressed result with optional offload hint (if UUID is provided)</li>
     *   <li>Returns a new ASSISTANT message containing the compressed summary</li>
     * </ol>
     *
     * <p><b>Special Handling:</b>
     * The method handles plan note related tools specially (see {@link #summaryToolsMessages}),
     * which are simplified without LLM interaction. This method is only called for non-plan
     * tool invocations.
     *
     * <p><b>Offload Integration:</b>
     * If an {@code offloadUUid} is provided, the compressed message will include a hint
     * indicating that the original content can be reloaded using the UUID via
     * {@link ContextOffloadTool}.
     *
     * @param messages the list of tool invocation messages to compress (must not be null or empty)
     * @param offloadUUid the UUID of the offloaded original messages, or null if not offloaded
     * @return a new ASSISTANT message containing the compressed tool invocation summary
     * @throws RuntimeException if LLM processing fails or is interrupted
     */
    private Msg compressToolsInvocation(List<Msg> messages, String offloadUUid) {

        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("tool_compress");
        String compressContentFormat =
                Prompts.PREVIOUS_ROUND_COMPRESSED_TOOL_INVOCATION_FORMAT
                        + ((offloadUUid != null)
                                ? String.format(Prompts.OFFLOAD_HINT, offloadUUid)
                                : "");
        List<Msg> newMessages = new ArrayList<>();
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(
                                                PromptProvider.getPreviousRoundToolCompressPrompt(
                                                        customPrompt))
                                        .build())
                        .build());
        newMessages.addAll(messages);
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.COMPRESSION_MESSAGE_LIST_END)
                                        .build())
                        .build());
        // Insert plan-aware hint message at the end to leverage recency effect
        addPlanAwareHintIfNeeded(newMessages);
        Msg block =
                model.stream(newMessages, null, options)
                        .concatMap(chunk -> processChunk(chunk, context))
                        .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                        .onErrorResume(InterruptedException.class, Mono::error)
                        .block();

        // Extract token usage information
        int inputTokens = 0;
        int outputTokens = 0;
        if (block != null && block.getChatUsage() != null) {
            inputTokens = block.getChatUsage().getInputTokens();
            outputTokens = block.getChatUsage().getOutputTokens();
            log.info(
                    "Tool compression completed, input tokens: {}, output tokens: {}",
                    inputTokens,
                    outputTokens);
        }

        // Build metadata with compression information
        Map<String, Object> compressMeta = new HashMap<>();
        if (offloadUUid != null) {
            compressMeta.put("offloaduuid", offloadUUid);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_compress_meta", compressMeta);

        // Preserve _chat_usage from the block if available
        if (block != null && block.getChatUsage() != null) {
            metadata.put(MessageMetadataKeys.CHAT_USAGE, block.getChatUsage());
        }

        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .content(
                        TextBlock.builder()
                                .text(
                                        String.format(
                                                compressContentFormat,
                                                block != null ? block.getTextContent() : ""))
                                .build())
                .metadata(metadata)
                .build();
    }

    private Mono<Msg> processChunk(ChatResponse chunk, ReasoningContext context) {
        return Mono.just(chunk).doOnNext(context::processChunk).then(Mono.empty());
    }

    @Override
    public void clear() {
        workingMemoryStorage.clear();
        originalMemoryStorage.clear();
    }

    /**
     * Attaches a PlanNotebook instance to enable plan-aware compression.
     *
     * <p>This method should be called after the ReActAgent is created and has a PlanNotebook.
     * When a PlanNotebook is attached, compression operations will automatically include
     * plan context information to preserve plan-related information during compression.
     *
     * <p>This method can be called multiple times to update or replace the PlanNotebook.
     * Passing null will detach the current PlanNotebook and disable plan-aware compression.
     *
     * @param planNotebook the PlanNotebook instance to attach, or null to detach
     */
    public void attachPlanNote(PlanNotebook planNotebook) {
        this.planNotebook = planNotebook;
        if (planNotebook != null) {
            log.debug("PlanNotebook attached to AutoContextMemory for plan-aware compression");
        } else {
            log.debug("PlanNotebook detached from AutoContextMemory");
        }
    }

    /**
     * Gets the current plan state information for compression context.
     *
     * <p>This method generates a generic plan-aware hint message that is fixed to be placed
     * <b>after</b> the messages that need to be compressed. The content uses "above messages"
     * terminology to refer to the messages that appear before this hint in the message list.
     *
     * @return Plan state information as a formatted string, or null if no plan is active
     */
    private String getPlanStateContext() {
        if (planNotebook == null) {
            return null;
        }

        Plan currentPlan = planNotebook.getCurrentPlan();
        if (currentPlan == null) {
            return null;
        }

        // Build plan state information (as hint message content)
        StringBuilder planContext = new StringBuilder();
        planContext.append("=== Current Plan Context ===\n");
        planContext.append("Plan Name: ").append(currentPlan.getName()).append("\n");
        planContext.append("Plan State: ").append(currentPlan.getState().getValue()).append("\n");
        planContext.append("Description: ").append(currentPlan.getDescription()).append("\n");
        planContext
                .append("Expected Outcome: ")
                .append(currentPlan.getExpectedOutcome())
                .append("\n");

        List<SubTask> subtasks = currentPlan.getSubtasks();
        if (subtasks != null && !subtasks.isEmpty()) {
            planContext.append("\nSubtasks:\n");
            for (int i = 0; i < subtasks.size(); i++) {
                SubTask subtask = subtasks.get(i);
                planContext.append(
                        String.format(
                                "  [%d] %s - State: %s\n",
                                i + 1, subtask.getName(), subtask.getState().getValue()));
                if (subtask.getState() == SubTaskState.IN_PROGRESS) {
                    planContext.append(
                            "    ⚠️ Currently in progress - preserve related information\n");
                } else if (subtask.getState() == SubTaskState.DONE
                        && subtask.getOutcome() != null) {
                    planContext.append("    ✅ Outcome: ").append(subtask.getOutcome()).append("\n");
                }
            }
        }

        planContext.append("\n=== Compression Guidelines ===\n");
        planContext.append("When compressing the above messages, prioritize information that:\n");
        planContext.append("1. Is directly related to the current plan and its subtasks\n");
        planContext.append("2. Supports the execution of in-progress subtasks\n");
        planContext.append("3. Contains outcomes or results from completed subtasks\n");
        planContext.append("4. Provides context for upcoming TODO subtasks\n");
        planContext.append("5. Includes plan-related tool calls and their results\n");

        // Provide more specific guidance based on plan state
        if (currentPlan.getState() == PlanState.IN_PROGRESS) {
            planContext.append("\nSpecifically:\n");
            planContext.append("- Preserve all information related to active subtasks\n");
            planContext.append("- Keep detailed results from tools used in plan execution\n");
            planContext.append("- Maintain context that helps track plan progress\n");
        }

        // Count tasks by state
        long inProgressCount =
                subtasks != null
                        ? subtasks.stream()
                                .filter(st -> st.getState() == SubTaskState.IN_PROGRESS)
                                .count()
                        : 0;
        long doneCount =
                subtasks != null
                        ? subtasks.stream().filter(st -> st.getState() == SubTaskState.DONE).count()
                        : 0;

        if (inProgressCount > 0) {
            planContext.append(
                    String.format(
                            "- Currently %d subtask(s) in progress - preserve all related"
                                    + " context\n",
                            inProgressCount));
        }

        if (doneCount > 0) {
            planContext.append(
                    String.format(
                            "- %d subtask(s) completed - preserve their outcomes and results\n",
                            doneCount));
        }

        return planContext.toString();
    }

    /**
     * Creates a hint message containing plan context information for compression.
     *
     * <p>This hint message is placed <b>after</b> the compression scope marker
     * (COMPRESSION_MESSAGE_LIST_END) at the end of the message list. This placement leverages the
     * model's attention mechanism (recency effect), ensuring compression guidelines are fresh in the
     * model's context during generation.
     *
     * @return A USER message containing plan context, or null if no plan is active
     */
    private Msg createPlanAwareHintMessage() {
        String planContext = getPlanStateContext();
        if (planContext == null) {
            return null;
        }

        return Msg.builder()
                .role(MsgRole.USER)
                .name("user")
                .content(
                        TextBlock.builder()
                                .text("<plan_aware_hint>\n" + planContext + "\n</plan_aware_hint>")
                                .build())
                .build();
    }

    /**
     * Adds plan-aware hint message to the message list if a plan is active.
     *
     * <p>This method creates and adds a plan-aware hint message to the provided message list if
     * there is an active plan. The hint message is added at the end of the list to leverage the
     * recency effect of the model's attention mechanism.
     *
     * @param newMessages the message list to which the hint message should be added
     */
    private void addPlanAwareHintIfNeeded(List<Msg> newMessages) {
        Msg hintMsg = createPlanAwareHintMessage();
        if (hintMsg != null) {
            newMessages.add(hintMsg);
        }
    }

    /**
     * Gets the original memory storage containing complete, uncompressed message history.
     *
     * <p>This storage maintains the full conversation history in its original form (append-only).
     * Unlike {@link #getMessages()} which returns compressed messages from working memory,
     * this method returns all messages as they were originally added, without any compression
     * or summarization applied.
     *
     * <p>Use cases:
     * <ul>
     *   <li>Accessing complete conversation history for analysis or export</li>
     *   <li>Recovering original messages that have been compressed in working memory</li>
     *   <li>Auditing or debugging conversation flow</li>
     * </ul>
     *
     * @return a list of all original messages in the order they were added
     */
    public List<Msg> getOriginalMemoryMsgs() {
        return originalMemoryStorage;
    }

    /**
     * Gets the user-assistant interaction messages from original memory storage.
     *
     * <p>This method filters the original memory storage to return only messages that represent
     * the actual interaction dialogue between the user and assistant. It includes:
     * <ul>
     *   <li>All {@link MsgRole#USER} messages</li>
     *   <li>Only final {@link MsgRole#ASSISTANT} responses that are sent to the user
     *       (excludes intermediate tool invocation messages)</li>
     * </ul>
     *
     * <p>This filtered list excludes:
     * <ul>
     *   <li>Tool-related messages ({@link MsgRole#TOOL})</li>
     *   <li>System messages ({@link MsgRole#SYSTEM})</li>
     *   <li>Intermediate ASSISTANT messages that contain tool calls (not final responses)</li>
     *   <li>Any other message types</li>
     * </ul>
     *
     * <p>A final assistant response is determined by {@link MsgUtils#isFinalAssistantResponse(Msg)},
     * which checks that the message does not contain {@link ToolUseBlock} or
     * {@link ToolResultBlock}, indicating it is the actual reply sent to the user rather
     * than an intermediate tool invocation step.
     *
     * <p>Use cases:
     * <ul>
     *   <li>Extracting clean conversation transcripts for analysis</li>
     *   <li>Generating conversation summaries without tool call details</li>
     *   <li>Exporting user-assistant interaction dialogue for documentation</li>
     *   <li>Training or fine-tuning data preparation</li>
     * </ul>
     *
     * <p>The returned list maintains the original order of messages, preserving the
     * interaction flow between user and assistant.
     *
     * @return a list containing only USER messages and final ASSISTANT responses in chronological order
     */
    public List<Msg> getInteractionMsgs() {
        List<Msg> conversations = new ArrayList<>();
        for (Msg msg : originalMemoryStorage) {
            if (msg.getRole() == MsgRole.USER || MsgUtils.isFinalAssistantResponse(msg)) {
                conversations.add(msg);
            }
        }
        return conversations;
    }

    /**
     * Gets the offload context map containing offloaded message content.
     *
     * <p>This map stores messages that have been offloaded during compression operations.
     * Each entry uses a UUID as the key and contains a list of messages that were offloaded
     * together. These messages can be reloaded using {@link #reload(String)} with the
     * corresponding UUID.
     *
     * <p>Offloading occurs when:
     * <ul>
     *   <li>Large messages exceed the {@code largePayloadThreshold}</li>
     *   <li>Tool invocations are compressed (Strategy 1)</li>
     *   <li>Previous round conversations are summarized (Strategy 4)</li>
     *   <li>Current round messages are compressed (Strategy 5 &amp; 6)</li>
     * </ul>
     *
     * <p>The offloaded content can be accessed via {@link ContextOffloadTool} or by
     * calling {@link #reload(String)} with the UUID found in compressed message hints.
     *
     * @return a map where keys are UUID strings and values are lists of offloaded messages
     */
    public Map<String, List<Msg>> getOffloadContext() {
        return offloadContext;
    }

    /**
     * Gets the list of compression events that occurred during context management.
     *
     * <p>This list records all compression operations that have been performed, including:
     * <ul>
     *   <li>Event type (which compression strategy was used)</li>
     *   <li>Timestamp when the compression occurred</li>
     *   <li>Number of messages compressed</li>
     *   <li>Token counts before and after compression</li>
     *   <li>Message positioning information (previous and next message IDs)</li>
     *   <li>Compressed message ID (for compression types)</li>
     * </ul>
     *
     * <p>The events are stored in chronological order and can be used for analysis,
     * debugging, or monitoring compression effectiveness.
     *
     * @return a list of compression events, ordered by timestamp
     */
    public List<CompressionEvent> getCompressionEvents() {
        return compressionEvents;
    }

    // ==================== StateModule API ====================

    /**
     * Save memory state to the session.
     *
     * <p>Saves working memory and original memory messages to the session storage.
     *
     * @param session the session to save state to
     * @param sessionKey the session identifier
     */
    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        session.save(
                sessionKey,
                "autoContextMemory_workingMessages",
                new ArrayList<>(workingMemoryStorage));
        session.save(
                sessionKey,
                "autoContextMemory_originalMessages",
                new ArrayList<>(originalMemoryStorage));

        // Save offload context (critical for reload functionality)
        if (!offloadContext.isEmpty()) {
            session.save(
                    sessionKey,
                    "autoContextMemory_offloadContext",
                    new OffloadContextState(new HashMap<>(offloadContext)));
        }

        if (!compressionEvents.isEmpty()) {
            session.save(
                    sessionKey,
                    "autoContextMemory_compressionEvents",
                    new ArrayList<>(compressionEvents));
        }
    }

    /**
     * Load memory state from the session.
     *
     * <p>Loads working memory and original memory messages from the session storage.
     *
     * @param session the session to load state from
     * @param sessionKey the session identifier
     */
    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        List<Msg> loadedWorking =
                session.getList(sessionKey, "autoContextMemory_workingMessages", Msg.class);
        workingMemoryStorage.clear();
        workingMemoryStorage.addAll(loadedWorking);

        List<Msg> loadedOriginal =
                session.getList(sessionKey, "autoContextMemory_originalMessages", Msg.class);
        originalMemoryStorage.clear();
        originalMemoryStorage.addAll(loadedOriginal);

        // Load offload context
        session.get(sessionKey, "autoContextMemory_offloadContext", OffloadContextState.class)
                .ifPresent(
                        state -> {
                            offloadContext.clear();
                            offloadContext.putAll(state.offloadContext());
                        });

        // Load compression context events
        List<CompressionEvent> compressEvents =
                session.getList(
                        sessionKey, "autoContextMemory_compressionEvents", CompressionEvent.class);
        compressionEvents.clear();
        compressionEvents.addAll(compressEvents);
    }
}
