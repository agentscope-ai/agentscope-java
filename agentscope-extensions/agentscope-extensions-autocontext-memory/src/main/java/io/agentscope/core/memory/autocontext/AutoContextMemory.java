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
import io.agentscope.core.message.ContentBlock;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

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

    private Map<String, List<Msg>> offloadContext = new ConcurrentHashMap<>();

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
        offloadContext = new ConcurrentHashMap<>();
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
    /**
     * Asynchronously compresses the context if needed.
     *
     * <p>This is the primary compression entry point. It runs strategies 1–6 in sequence,
     * returning as soon as one succeeds ({@code true}). Per-strategy enable/disable flags from
     * {@link AutoContextConfig} are respected.
     *
     * <p>Strategy 4 (summarize previous round conversations) runs eligible rounds
     * <b>concurrently</b> (up to 5 in-flight) to reduce total latency.
     *
     * <p>No {@code .block()} is used anywhere in the reactive chain.
     *
     * @return Mono emitting {@code true} if compression was applied, {@code false} if not needed
     *         or no strategy could compress
     */
    public Mono<Boolean> compressIfNeededAsync() {
        // Subscribe on boundedElastic to avoid blocking non-blocking schedulers
        // (e.g., Netty event loop, Reactor parallel) with synchronous work
        // such as TokenCounterUtil.calculateToken and ArrayList copies.
        return compressIfNeededAsyncInternal().subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> compressIfNeededAsyncInternal() {
        List<Msg> currentContextMessages = new ArrayList<>(workingMemoryStorage);

        boolean msgCountReached = currentContextMessages.size() >= autoContextConfig.msgThreshold;
        int calculateToken = TokenCounterUtil.calculateToken(currentContextMessages);
        int thresholdToken = (int) (autoContextConfig.maxToken * autoContextConfig.tokenRatio);
        boolean tokenCounterReached = calculateToken >= thresholdToken;

        if (!msgCountReached && !tokenCounterReached) {
            return Mono.just(false);
        }

        log.info(
                "Compression triggered - msgCount: {}/{}, tokenCount: {}/{}",
                currentContextMessages.size(),
                autoContextConfig.msgThreshold,
                calculateToken,
                thresholdToken);

        // Build a sequential chain of strategies; return true at the first that succeeds.
        // Each strategy is wrapped in Mono.defer so it is only subscribed when reached.
        Mono<Boolean> chain = Mono.just(false);

        // Strategy 1: Compress previous round tool invocations
        if (autoContextConfig.isStrategy1Enabled()) {
            chain =
                    chain.flatMap(
                            prev -> {
                                if (prev) return Mono.just(true);
                                log.info(
                                        "Strategy 1: Checking for previous round tool invocations"
                                                + " to compress");
                                return Mono.defer(
                                        () -> {
                                            return processStrategy1Async(5, false)
                                                    .doOnNext(
                                                            applied -> {
                                                                if (applied)
                                                                    log.info(
                                                                            "Strategy 1: APPLIED -"
                                                                                + " Compressed tool"
                                                                                + " invocations");
                                                                else
                                                                    log.info(
                                                                            "Strategy 1: SKIPPED -"
                                                                                + " No compressible"
                                                                                + " tool"
                                                                                + " invocations (or"
                                                                                + " skipped due to"
                                                                                + " low tokens)");
                                                            });
                                        });
                            });
        } else {
            log.debug("Strategy 1: DISABLED by config");
        }

        // Strategy 2: Offload previous round large messages (with lastKeep protection)
        if (autoContextConfig.isStrategy2Enabled()) {
            chain =
                    chain.flatMap(
                            prev -> {
                                if (prev) return Mono.just(true);
                                log.info(
                                        "Strategy 2: Checking for previous round large messages"
                                                + " (with lastKeep protection)");
                                return Mono.fromCallable(
                                                () -> {
                                                    List<Msg> msgs =
                                                            new ArrayList<>(workingMemoryStorage);
                                                    boolean applied =
                                                            offloadingLargePayload(msgs, true);
                                                    if (applied) replaceWorkingMessage(msgs);
                                                    return applied;
                                                })
                                        .doOnNext(
                                                applied -> {
                                                    if (applied)
                                                        log.info(
                                                                "Strategy 2: APPLIED - Offloaded"
                                                                    + " large messages (lastKeep"
                                                                    + " protected)");
                                                    else
                                                        log.info(
                                                                "Strategy 2: SKIPPED - No large"
                                                                        + " messages or all"
                                                                        + " protected");
                                                });
                            });
        } else {
            log.debug("Strategy 2: DISABLED by config");
        }

        // Strategy 3: Offload previous round large messages (without lastKeep protection)
        if (autoContextConfig.isStrategy3Enabled()) {
            chain =
                    chain.flatMap(
                            prev -> {
                                if (prev) return Mono.just(true);
                                log.info(
                                        "Strategy 3: Checking for previous round large messages"
                                                + " (without lastKeep protection)");
                                return Mono.fromCallable(
                                                () -> {
                                                    List<Msg> msgs =
                                                            new ArrayList<>(workingMemoryStorage);
                                                    boolean applied =
                                                            offloadingLargePayload(msgs, false);
                                                    if (applied) replaceWorkingMessage(msgs);
                                                    return applied;
                                                })
                                        .doOnNext(
                                                applied -> {
                                                    if (applied)
                                                        log.info(
                                                                "Strategy 3: APPLIED - Offloaded"
                                                                        + " large messages");
                                                    else
                                                        log.info(
                                                                "Strategy 3: SKIPPED - No large"
                                                                        + " messages found");
                                                });
                            });
        } else {
            log.debug("Strategy 3: DISABLED by config");
        }

        // Strategy 4: Summarize previous round conversations (concurrent rounds)
        if (autoContextConfig.isStrategy4Enabled()) {
            chain =
                    chain.flatMap(
                            prev -> {
                                if (prev) return Mono.just(true);
                                log.info(
                                        "Strategy 4: Checking for previous round conversations"
                                                + " to summarize");
                                List<Msg> msgs = new ArrayList<>(workingMemoryStorage);
                                return summaryPreviousRoundMessages(msgs)
                                        .doOnNext(
                                                applied -> {
                                                    if (applied) {
                                                        replaceWorkingMessage(msgs);
                                                        log.info(
                                                                "Strategy 4: APPLIED - Summarized"
                                                                        + " previous round"
                                                                        + " conversations");
                                                    } else {
                                                        log.info(
                                                                "Strategy 4: SKIPPED - No previous"
                                                                        + " round conversations to"
                                                                        + " summarize");
                                                    }
                                                });
                            });
        } else {
            log.debug("Strategy 4: DISABLED by config");
        }

        // Strategy 5: Summarize and offload current round large messages
        if (autoContextConfig.isStrategy5Enabled()) {
            chain =
                    chain.flatMap(
                            prev -> {
                                if (prev) return Mono.just(true);
                                log.info(
                                        "Strategy 5: Checking for current round large messages to"
                                                + " summarize");
                                List<Msg> msgs = new ArrayList<>(workingMemoryStorage);
                                return summaryCurrentRoundLargeMessages(msgs)
                                        .doOnNext(
                                                applied -> {
                                                    if (applied) {
                                                        replaceWorkingMessage(msgs);
                                                        log.info(
                                                                "Strategy 5: APPLIED - Summarized"
                                                                        + " current round large"
                                                                        + " messages");
                                                    } else {
                                                        log.info(
                                                                "Strategy 5: SKIPPED - No current"
                                                                        + " round large messages");
                                                    }
                                                });
                            });
        } else {
            log.debug("Strategy 5: DISABLED by config");
        }

        // Strategy 6: Summarize current round messages
        if (autoContextConfig.isStrategy6Enabled()) {
            chain =
                    chain.flatMap(
                            prev -> {
                                if (prev) return Mono.just(true);
                                log.info(
                                        "Strategy 6: Checking for current round messages to"
                                                + " summarize");
                                List<Msg> msgs = new ArrayList<>(workingMemoryStorage);
                                return summaryCurrentRoundMessages(msgs)
                                        .doOnNext(
                                                applied -> {
                                                    if (applied) {
                                                        replaceWorkingMessage(msgs);
                                                        log.info(
                                                                "Strategy 6: APPLIED - Summarized"
                                                                        + " current round"
                                                                        + " messages");
                                                    } else {
                                                        log.info(
                                                                "Strategy 6: SKIPPED - No current"
                                                                        + " round messages to"
                                                                        + " summarize");
                                                    }
                                                });
                            });
        } else {
            log.debug("Strategy 6: DISABLED by config");
        }

        boolean anyStrategyEnabled =
                autoContextConfig.isStrategy1Enabled()
                        || autoContextConfig.isStrategy2Enabled()
                        || autoContextConfig.isStrategy3Enabled()
                        || autoContextConfig.isStrategy4Enabled()
                        || autoContextConfig.isStrategy5Enabled()
                        || autoContextConfig.isStrategy6Enabled();

        return chain.doOnNext(
                applied -> {
                    if (!applied) {
                        if (!anyStrategyEnabled) {
                            log.info(
                                    "All compression strategies are disabled by config;"
                                            + " no compression attempted");
                        } else {
                            log.warn(
                                    "All compression strategies exhausted but context"
                                            + " still exceeds threshold");
                        }
                    }
                });
    }

    /**
     * Synchronously compresses the context if needed.
     *
     * @deprecated Use {@link #compressIfNeededAsync()} to stay fully reactive and avoid
     *             blocking the scheduler thread. This method is kept for backward compatibility
     *             only and will be removed in a future release.
     * @return {@code true} if compression was applied, {@code false} otherwise
     */
    @Deprecated
    public boolean compressIfNeeded() {
        Boolean result = compressIfNeededAsync().block();
        return Boolean.TRUE.equals(result);
    }

    private Mono<Boolean> processStrategy1Async(int maxIters, boolean anyApplied) {
        return processStrategy1Async(maxIters, anyApplied, 0);
    }

    private Mono<Boolean> processStrategy1Async(int maxIters, boolean anyApplied, int cursor) {
        if (maxIters <= 0) return Mono.just(anyApplied);
        return Mono.defer(
                () -> {
                    List<Msg> msgs = new ArrayList<>(workingMemoryStorage);
                    Pair<Integer, Integer> range =
                            extractPrevToolMsgsForCompress(
                                    msgs, autoContextConfig.getLastKeep(), cursor);
                    if (range == null) {
                        return Mono.just(anyApplied);
                    }
                    return summaryToolsMessages(msgs, range)
                            .flatMap(
                                    done -> {
                                        if (done) {
                                            replaceWorkingMessage(msgs);
                                            // Recursively process the next range now that list is
                                            // updated
                                            return processStrategy1Async(maxIters - 1, true, 0);
                                        } else {
                                            // Skip this range and find next
                                            return processStrategy1Async(
                                                    maxIters - 1, anyApplied, range.second() + 1);
                                        }
                                    });
                });
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
    private Mono<Boolean> summaryCurrentRoundMessages(List<Msg> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return Mono.just(false);
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

        if (latestUserIndex < 0) {
            return Mono.just(false);
        }
        if (latestUserIndex >= rawMessages.size() - 1) {
            return Mono.just(false);
        }

        // Step 2: Extract messages after the latest user message
        int startIndex = latestUserIndex + 1;
        int endIndex = rawMessages.size() - 1;

        // Ensure tool use and tool result are paired
        if (endIndex >= startIndex) {
            Msg lastMsg = rawMessages.get(endIndex);
            if (MsgUtils.isToolUseMessage(lastMsg)) {
                endIndex--;
                if (endIndex < startIndex) {
                    return Mono.just(false);
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

        final int finalStartIndex = startIndex;
        final int finalEndIndex = endIndex;

        return mergeAndCompressCurrentRoundMessagesAsync(messagesToCompress)
                .map(
                        compressedMsg -> {
                            Map<String, Object> metadata = new HashMap<>();
                            if (compressedMsg.getChatUsage() != null) {
                                metadata.put(
                                        "inputToken",
                                        compressedMsg.getChatUsage().getInputTokens());
                                metadata.put(
                                        "outputToken",
                                        compressedMsg.getChatUsage().getOutputTokens());
                                metadata.put("time", compressedMsg.getChatUsage().getTime());
                            }
                            recordCompressionEvent(
                                    CompressionEvent.CURRENT_ROUND_MESSAGE_COMPRESS,
                                    finalStartIndex,
                                    finalEndIndex,
                                    rawMessages,
                                    compressedMsg,
                                    metadata);
                            rawMessages.subList(finalStartIndex, finalEndIndex + 1).clear();
                            rawMessages.add(finalStartIndex, compressedMsg);
                            log.info(
                                    "Replaced {} messages with 1 compressed message at index {}",
                                    messagesToCompress.size(),
                                    finalStartIndex);
                            return true;
                        })
                .defaultIfEmpty(false);
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
    private Mono<Boolean> summaryCurrentRoundLargeMessages(List<Msg> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return Mono.just(false);
        }

        int latestUserIndex = -1;
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                latestUserIndex = i;
                break;
            }
        }

        if (latestUserIndex < 0) {
            return Mono.just(false);
        }
        if (latestUserIndex >= rawMessages.size() - 1) {
            return Mono.just(false);
        }

        // Collect large messages in reverse order (to preserve insertion indices later)
        List<Integer> indicesToSummarize = new ArrayList<>();
        long threshold = autoContextConfig.largePayloadThreshold;
        for (int i = rawMessages.size() - 1; i > latestUserIndex; i--) {
            Msg msg = rawMessages.get(i);
            if (MsgUtils.isCompressedMessage(msg)) {
                log.debug(
                        "Skipping already compressed message at index {} to avoid double"
                                + " compression",
                        i);
                continue;
            }
            String textContent = msg.getTextContent();
            if ((textContent == null || textContent.isEmpty())
                    && MsgUtils.calculateMessageCharCount(msg) <= threshold) {
                continue;
            }
            if (textContent != null
                    && !textContent.isEmpty()
                    && textContent.length() <= threshold) {
                continue;
            }
            indicesToSummarize.add(i);
        }

        if (indicesToSummarize.isEmpty()) {
            return Mono.just(false);
        }

        // Build one Mono per large message, run concurrently (max 5)
        int concurrency = Math.min(indicesToSummarize.size(), 5);
        final List<Msg> rawMessagesCopy = rawMessages;

        return Flux.fromIterable(indicesToSummarize)
                .flatMap(
                        idx -> {
                            Msg msg = rawMessagesCopy.get(idx);
                            String uuid = UUID.randomUUID().toString();
                            List<Msg> offloadMsg = new ArrayList<>();
                            offloadMsg.add(msg);
                            return Mono.fromRunnable(
                                            () -> {
                                                offload(uuid, offloadMsg);
                                                log.info(
                                                        "Offloaded current round large message:"
                                                                + " index={}, size={} chars,"
                                                                + " uuid={}",
                                                        idx,
                                                        MsgUtils.calculateMessageCharCount(msg),
                                                        uuid);
                                            })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .then(generateLargeMessageSummary(msg, uuid))
                                    .map(summaryMsg -> Tuples.of(idx, summaryMsg));
                        },
                        concurrency)
                .collectList()
                .map(
                        results -> {
                            if (results == null || results.isEmpty()) {
                                return false;
                            }
                            // Sort descending by index to avoid shifting
                            results.sort((a, b) -> Integer.compare(b.getT1(), a.getT1()));
                            for (Tuple2<Integer, Msg> item : results) {
                                int idx = item.getT1();
                                Msg summaryMsg = item.getT2();
                                Map<String, Object> metadata = new HashMap<>();
                                if (summaryMsg.getChatUsage() != null) {
                                    metadata.put(
                                            "inputToken",
                                            summaryMsg.getChatUsage().getInputTokens());
                                    metadata.put(
                                            "outputToken",
                                            summaryMsg.getChatUsage().getOutputTokens());
                                    metadata.put("time", summaryMsg.getChatUsage().getTime());
                                }
                                recordCompressionEvent(
                                        CompressionEvent.CURRENT_ROUND_LARGE_MESSAGE_SUMMARY,
                                        idx,
                                        idx,
                                        rawMessagesCopy,
                                        summaryMsg,
                                        metadata);
                                rawMessagesCopy.set(idx, summaryMsg);
                                log.info(
                                        "Replaced large message at index {} with summarized"
                                                + " version",
                                        idx);
                            }
                            return true;
                        });
    }

    /**
     * Generate a summary of a large message using the model.
     *
     * <p>Returns a {@link Mono} that emits a fully-built summary {@link Msg}.
     * No {@code .block()} is used; all LLM calls remain inside the reactive pipeline.
     *
     * <p>For messages containing ToolUseBlock (e.g., ReAct ASSISTANT messages with reasoning +
     * tool call), only the TextBlock content is sent to the model for summarization (to avoid
     * triggering model's tool detection), and the compressed result preserves the ToolUseBlock
     * structure while replacing TextBlock with the LLM summary.
     *
     * <p>For messages containing ToolResultBlock (e.g., TOOL messages), the text content inside
     * ToolResultBlock's output is extracted for summarization, and the compressed result preserves
     * the ToolResultBlock structure (id, name, metadata) while replacing output with the summary.
     *
     * @param message the message to summarize
     * @param offloadUuid the UUID of offloaded message
     * @return a Mono emitting a summary message preserving the original role and name
     */
    private Mono<Msg> generateLargeMessageSummary(Msg message, String offloadUuid) {
        boolean hasToolUse = message.hasContentBlocks(ToolUseBlock.class);
        boolean hasToolResult = message.hasContentBlocks(ToolResultBlock.class);

        // Call model to generate summary, then build the final Msg reactively
        return callModelForSummary(message, hasToolUse, hasToolResult)
                .map(
                        block -> {
                            // Build summary text with optional offload hint
                            String summaryContent = block != null ? block.getTextContent() : "";
                            String offloadHint =
                                    offloadUuid != null
                                            ? String.format(
                                                    Prompts.CONTEXT_OFFLOAD_TAG_FORMAT, offloadUuid)
                                            : "";
                            String finalContent =
                                    offloadHint.isEmpty()
                                            ? summaryContent
                                            : summaryContent + offloadHint;

                            // Build metadata
                            Map<String, Object> metadata =
                                    buildCompressionMetadata(message, offloadUuid, block);

                            // Build result message content blocks
                            List<ContentBlock> contentBlocks;
                            if (hasToolUse) {
                                contentBlocks = buildToolUsePreservingBlocks(message, finalContent);
                            } else if (hasToolResult) {
                                contentBlocks =
                                        buildToolResultPreservingBlocks(message, finalContent);
                            } else {
                                contentBlocks =
                                        List.of(TextBlock.builder().text(finalContent).build());
                            }

                            return Msg.builder()
                                    .role(message.getRole())
                                    .name(message.getName())
                                    .content(contentBlocks)
                                    .metadata(metadata)
                                    .build();
                        })
                .defaultIfEmpty(
                        Msg.builder()
                                .role(message.getRole())
                                .name(message.getName())
                                .content(
                                        TextBlock.builder()
                                                .text(
                                                        offloadUuid != null
                                                                ? String.format(
                                                                        Prompts
                                                                                .CONTEXT_OFFLOAD_TAG_FORMAT,
                                                                        offloadUuid)
                                                                : "")
                                                .build())
                                .build());
    }

    /**
     * Call model to generate a summary of the message content.
     *
     * <p>Returns a {@link Mono} that, when subscribed, streams the model response and emits a
     * single summary {@link Msg}. No {@code .block()} is used; callers must stay reactive.
     *
     * <p>For messages with ToolUseBlock, only TextBlock content is extracted and sent to the model
     * to avoid triggering tool detection mechanism. For messages with ToolResultBlock, the text
     * content inside ToolResultBlock's output is extracted for summarization.
     */
    private Mono<Msg> callModelForSummary(Msg message, boolean hasToolUse, boolean hasToolResult) {
        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("large_message_summary");

        // Build the message to send for compression
        Msg messageForCompression = message;
        String textForCompression = extractTextForCompression(message, hasToolUse, hasToolResult);
        if (textForCompression != null && !textForCompression.isEmpty()) {
            messageForCompression =
                    Msg.builder()
                            .role(message.getRole())
                            .name(message.getName())
                            .content(TextBlock.builder().text(textForCompression).build())
                            .build();
        }

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
        newMessages.add(messageForCompression);
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.COMPRESSION_MESSAGE_LIST_END)
                                        .build())
                        .build());
        addPlanAwareHintIfNeeded(newMessages);

        return model.stream(newMessages, null, options)
                .concatMap(chunk -> processChunk(chunk, context))
                .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                .doOnNext(
                        block -> {
                            if (block != null && block.getChatUsage() != null) {
                                log.info(
                                        "Large message summary completed, input tokens: {},"
                                                + " output tokens: {}",
                                        block.getChatUsage().getInputTokens(),
                                        block.getChatUsage().getOutputTokens());
                            }
                        })
                .onErrorResume(InterruptedException.class, Mono::error);
    }

    /** Build compression metadata including offload UUID and chat usage. */
    private Map<String, Object> buildCompressionMetadata(
            Msg message, String offloadUuid, Msg block) {
        Map<String, Object> compressMeta = new HashMap<>();
        if (offloadUuid != null) {
            compressMeta.put("offloaduuid", offloadUuid);
        }

        // Preserve original message metadata
        Map<String, Object> metadata =
                message.getMetadata() != null
                        ? new HashMap<>(message.getMetadata())
                        : new HashMap<>();
        metadata.put("_compress_meta", compressMeta);

        if (block != null && block.getChatUsage() != null) {
            metadata.put(MessageMetadataKeys.CHAT_USAGE, block.getChatUsage());
        }
        return metadata;
    }

    /**
     * Extract text content for compression based on the message's content block types.
     *
     * @return extracted text content, or null if no special extraction is needed
     */
    private String extractTextForCompression(
            Msg message, boolean hasToolUse, boolean hasToolResult) {
        if (hasToolUse) {
            // Extract only top-level TextBlock content to avoid triggering model's tool detection
            return message.getTextContent();
        }
        if (hasToolResult) {
            // Extract text from ToolResultBlock's output since Msg.getTextContent() only
            // extracts top-level TextBlocks and returns empty for ToolResultBlock content
            return message.getContent().stream()
                    .filter(ToolResultBlock.class::isInstance)
                    .map(ToolResultBlock.class::cast)
                    .flatMap(trb -> trb.getOutput().stream())
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .collect(Collectors.joining("\n"));
        }
        return null;
    }

    /**
     * Build content blocks that preserve ToolUseBlock structure while replacing TextBlock with
     * compressed summary.
     */
    private List<ContentBlock> buildToolUsePreservingBlocks(Msg message, String summaryText) {
        List<ContentBlock> blocks = new ArrayList<>();
        boolean hasTextBlock = false;

        for (ContentBlock originalBlock : message.getContent()) {
            if (originalBlock instanceof ToolUseBlock) {
                blocks.add(originalBlock);
            } else if (originalBlock instanceof TextBlock) {
                if (!hasTextBlock) {
                    blocks.add(TextBlock.builder().text(summaryText).build());
                    hasTextBlock = true;
                }
            } else {
                blocks.add(originalBlock);
            }
        }

        // Defensive: if no TextBlock existed, still add the summary
        if (!hasTextBlock && !summaryText.isEmpty()) {
            blocks.add(0, TextBlock.builder().text(summaryText).build());
        }
        return blocks;
    }

    /**
     * Build content blocks that preserve ToolResultBlock structure (id, name, metadata) while
     * replacing its output with the compressed summary.
     */
    private List<ContentBlock> buildToolResultPreservingBlocks(Msg message, String summaryText) {
        List<ContentBlock> blocks = new ArrayList<>();
        boolean hasReplacedToolResult = false;

        for (ContentBlock originalBlock : message.getContent()) {
            if (originalBlock instanceof ToolResultBlock toolResult) {
                if (!hasReplacedToolResult) {
                    // Replace output with compressed summary, preserve id/name/metadata
                    blocks.add(
                            ToolResultBlock.of(
                                    toolResult.getId(),
                                    toolResult.getName(),
                                    TextBlock.builder().text(summaryText).build(),
                                    toolResult.getMetadata()));
                    hasReplacedToolResult = true;
                }
            } else {
                blocks.add(originalBlock);
            }
        }
        return blocks;
    }

    /**
     * Merge and compress current round messages (typically tool calls and tool results).
     *
     * <p>Returns a {@link Mono} emitting the compressed message. No {@code .block()} used.
     *
     * @param messages the messages to merge and compress
     * @return Mono emitting the compressed message
     */
    private Mono<Msg> mergeAndCompressCurrentRoundMessagesAsync(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.empty();
        }
        String uuid = UUID.randomUUID().toString();
        List<Msg> originalMessages = new ArrayList<>(messages);
        offload(uuid, originalMessages);
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
     * <p>Returns a {@link Mono} that emits the compressed {@link Msg}.
     * No {@code .block()} is used inside this method.
     *
     * @param messages the messages to summarize
     * @param offloadUuid the UUID of offloaded content (if any)
     * @return Mono emitting the compressed message
     */
    private Mono<Msg> generateCurrentRoundSummaryFromMessages(
            List<Msg> messages, String offloadUuid) {
        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("current_round_compress");

        // Filter out plan-related tool calls before compression
        List<Msg> filteredMessages = MsgUtils.filterPlanRelatedToolCalls(messages);
        if (filteredMessages.size() < messages.size()) {
            log.info(
                    "Filtered out {} plan-related tool call messages from current round"
                            + " compression",
                    messages.size() - filteredMessages.size());
        }

        // Calculate original character count
        int originalCharCount = MsgUtils.calculateMessagesCharCount(filteredMessages);

        // Get compression ratio and calculate target character count
        double compressionRatio = autoContextConfig.getCurrentRoundCompressionRatio();
        int compressionRatioPercent = (int) Math.round(compressionRatio * 100);
        int targetCharCount = (int) Math.round(originalCharCount * compressionRatio);

        String offloadHint =
                offloadUuid != null
                        ? String.format(Prompts.CONTEXT_OFFLOAD_TAG_FORMAT, offloadUuid)
                        : "";

        // Build character count requirement message
        String charRequirement =
                String.format(
                        Prompts.CURRENT_ROUND_MESSAGE_COMPRESS_CHAR_REQUIREMENT,
                        originalCharCount,
                        targetCharCount,
                        (double) compressionRatioPercent,
                        (double) compressionRatioPercent);

        List<Msg> newMessages = new ArrayList<>();
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
        newMessages.addAll(filteredMessages);
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.COMPRESSION_MESSAGE_LIST_END)
                                        .build())
                        .build());
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(TextBlock.builder().text(charRequirement).build())
                        .build());
        addPlanAwareHintIfNeeded(newMessages);

        return model.stream(newMessages, null, options)
                .concatMap(chunk -> processChunk(chunk, context))
                .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                .onErrorResume(InterruptedException.class, Mono::error)
                .map(
                        block -> {
                            int inputTokens = 0;
                            int outputTokens = 0;
                            if (block != null && block.getChatUsage() != null) {
                                inputTokens = block.getChatUsage().getInputTokens();
                                outputTokens = block.getChatUsage().getOutputTokens();
                            }
                            int actualCharCount =
                                    block != null ? MsgUtils.calculateMessageCharCount(block) : 0;
                            log.info(
                                    "Current round summary completed - original: {} chars, target:"
                                            + " {} chars ({}%), actual: {} chars, input tokens: {},"
                                            + " output tokens: {}",
                                    originalCharCount,
                                    targetCharCount,
                                    compressionRatioPercent,
                                    actualCharCount,
                                    inputTokens,
                                    outputTokens);

                            Map<String, Object> compressMeta = new HashMap<>();
                            if (offloadUuid != null) {
                                compressMeta.put("offloaduuid", offloadUuid);
                            }
                            compressMeta.put("compressed_current_round", true);
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put("_compress_meta", compressMeta);
                            if (block != null && block.getChatUsage() != null) {
                                metadata.put(MessageMetadataKeys.CHAT_USAGE, block.getChatUsage());
                            }

                            return Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .name("assistant")
                                    .content(
                                            TextBlock.builder()
                                                    .text(
                                                            (block != null
                                                                            ? block.getTextContent()
                                                                            : "")
                                                                    + offloadHint)
                                                    .build())
                                    .metadata(metadata)
                                    .build();
                        });
    }

    /**
     * Summarizes a group of tool-invocation messages and replaces them in {@code rawMessages}.
     *
     * <p>Returns a {@link Mono}{@code <Boolean>} that emits {@code true} if compression was
     * performed, {@code false} if skipped (e.g. token count below threshold).
     *
     * @param rawMessages the working message list (mutated in place on success)
     * @param toolMsgIndices start/end indices (inclusive) of the tool messages to compress
     * @return Mono emitting whether compression happened
     */
    private Mono<Boolean> summaryToolsMessages(
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

        // Skip if token count is below threshold
        int originalTokens = TokenCounterUtil.calculateToken(toolsMsg);
        int threshold = autoContextConfig.getMinCompressionTokenThreshold();
        if (originalTokens < threshold) {
            log.info(
                    "Skipping tool invocation compression: original tokens ({}) is below threshold"
                            + " ({})",
                    originalTokens,
                    threshold);
            return Mono.just(false);
        }

        log.info(
                "Proceeding with tool invocation compression: original tokens: {}, threshold: {}",
                originalTokens,
                threshold);

        // Offload originals before async summary
        String uuid = UUID.randomUUID().toString();
        offload(uuid, toolsMsg);

        return compressToolsInvocationAsync(toolsMsg, uuid)
                .map(
                        toolsSummary -> {
                            // Build metadata for compression event
                            Map<String, Object> metadata = new HashMap<>();
                            if (toolsSummary.getChatUsage() != null) {
                                metadata.put(
                                        "inputToken", toolsSummary.getChatUsage().getInputTokens());
                                metadata.put(
                                        "outputToken",
                                        toolsSummary.getChatUsage().getOutputTokens());
                                metadata.put("time", toolsSummary.getChatUsage().getTime());
                            }
                            recordCompressionEvent(
                                    CompressionEvent.TOOL_INVOCATION_COMPRESS,
                                    startIndex,
                                    endIndex,
                                    rawMessages,
                                    toolsSummary,
                                    metadata);
                            MsgUtils.replaceMsg(rawMessages, startIndex, endIndex, toolsSummary);
                            return true;
                        })
                .defaultIfEmpty(false);
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
    /**
     * Summarize all previous rounds of conversation messages before the latest assistant.
     *
     * <p>This method runs all eligible round summaries <b>concurrently</b> (up to 5 at a time)
     * using {@link Flux#flatMap} with a concurrency limit, then applies the results to
     * {@code rawMessages} in reverse index order to avoid index-shifting side effects.
     *
     * <p>Returns a {@link Mono}{@code <Boolean>} emitting {@code true} if at least one round
     * was summarized, {@code false} otherwise. No {@code .block()} is used.
     *
     * @param rawMessages the working message list (mutated in place after all summaries complete)
     * @return Mono emitting whether any summarization happened
     */
    private Mono<Boolean> summaryPreviousRoundMessages(List<Msg> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return Mono.just(false);
        }

        // Step 1: Find the latest final assistant message
        int latestAssistantIndex = -1;
        for (int i = rawMessages.size() - 1; i >= 0; i--) {
            if (MsgUtils.isFinalAssistantResponse(rawMessages.get(i))) {
                latestAssistantIndex = i;
                break;
            }
        }
        if (latestAssistantIndex < 0) {
            return Mono.just(false);
        }

        // Step 2: Collect user-assistant pairs before the latest assistant
        List<Pair<Integer, Integer>> userAssistantPairs = new ArrayList<>();
        int currentUserIndex = -1;
        for (int i = 0; i < latestAssistantIndex; i++) {
            Msg msg = rawMessages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                currentUserIndex = i;
            } else if (MsgUtils.isFinalAssistantResponse(msg) && currentUserIndex >= 0) {
                if (i - currentUserIndex != 1) {
                    userAssistantPairs.add(new Pair<>(currentUserIndex, i));
                }
                currentUserIndex = -1;
            }
        }
        if (userAssistantPairs.isEmpty()) {
            return Mono.just(false);
        }

        log.info(
                "Found {} user-assistant pairs to summarize before latest assistant at index {}",
                userAssistantPairs.size(),
                latestAssistantIndex);

        // Step 3: Build a Mono for each eligible pair
        // SummaryTask record-like holder
        record SummaryCandidate(
                int pairIdx,
                int userIndex,
                int startIndex,
                int endIndex,
                List<Msg> messagesToSummarize,
                String uuid) {}

        List<SummaryCandidate> candidates = new ArrayList<>();
        for (int pairIdx = userAssistantPairs.size() - 1; pairIdx >= 0; pairIdx--) {
            Pair<Integer, Integer> pair = userAssistantPairs.get(pairIdx);
            int userIndex = pair.first();
            int assistantIndex = pair.second();
            int startIndex = userIndex + 1;
            int endIndex = assistantIndex;

            if (startIndex > endIndex) {
                continue;
            }

            List<Msg> messagesToSummarize = new ArrayList<>();
            messagesToSummarize.add(rawMessages.get(userIndex));
            for (int i = startIndex; i <= endIndex; i++) {
                messagesToSummarize.add(rawMessages.get(i));
            }

            int originalTokens = TokenCounterUtil.calculateToken(messagesToSummarize);
            int threshold = autoContextConfig.getMinCompressionTokenThreshold();
            if (originalTokens < threshold) {
                log.info(
                        "Skipping conversation summary for round {}: original tokens ({}) below"
                                + " threshold ({})",
                        pairIdx + 1,
                        originalTokens,
                        threshold);
                continue;
            }

            log.info(
                    "Queuing conversation summary for round {}: user at index {}, messages"
                            + " [{}, {}], totalCount={} (includes user message for context)",
                    pairIdx + 1,
                    userIndex,
                    startIndex,
                    endIndex,
                    messagesToSummarize.size());

            // Offload originals eagerly (synchronous, cheap) before firing the async summary
            String uuid = UUID.randomUUID().toString();
            offload(uuid, messagesToSummarize);
            log.info("Offloaded messages for round {}: uuid={}", pairIdx + 1, uuid);

            candidates.add(
                    new SummaryCandidate(
                            pairIdx, userIndex, startIndex, endIndex, messagesToSummarize, uuid));
        }

        if (candidates.isEmpty()) {
            return Mono.just(false);
        }

        // Step 4: Run summaries concurrently (max 5 in-flight), collect all results
        int concurrency = Math.min(candidates.size(), 5);

        return Flux.fromIterable(candidates)
                .flatMap(
                        candidate ->
                                summaryPreviousRoundConversationAsync(
                                                candidate.messagesToSummarize(), candidate.uuid())
                                        .filter(
                                                summaryMsg -> {
                                                    String content = summaryMsg.getTextContent();
                                                    if (content == null
                                                            || content.trim().isEmpty()) {
                                                        // Clean up orphaned offload entry
                                                        clear(candidate.uuid());
                                                        log.warn(
                                                                "Strategy 4: LLM returned"
                                                                        + " empty/blank summary for"
                                                                        + " round {}; cleaned"
                                                                        + " orphaned offload"
                                                                        + " uuid={}",
                                                                candidate.pairIdx() + 1,
                                                                candidate.uuid());
                                                        return false;
                                                    }
                                                    return true;
                                                })
                                        .map(
                                                summaryMsg -> {
                                                    Map<String, Object> metadata = new HashMap<>();
                                                    if (summaryMsg.getChatUsage() != null) {
                                                        metadata.put(
                                                                "inputToken",
                                                                summaryMsg
                                                                        .getChatUsage()
                                                                        .getInputTokens());
                                                        metadata.put(
                                                                "outputToken",
                                                                summaryMsg
                                                                        .getChatUsage()
                                                                        .getOutputTokens());
                                                        metadata.put(
                                                                "time",
                                                                summaryMsg
                                                                        .getChatUsage()
                                                                        .getTime());
                                                    }
                                                    return Tuples.of(
                                                            candidate, summaryMsg, metadata);
                                                })
                                        .onErrorResume(
                                                err -> {
                                                    // Clean up orphaned offload entry on failure
                                                    clear(candidate.uuid());
                                                    log.error(
                                                            "Strategy 4: LLM summary failed for"
                                                                    + " round {}; cleaned orphaned"
                                                                    + " offload uuid={}",
                                                            candidate.pairIdx() + 1,
                                                            candidate.uuid(),
                                                            err);
                                                    return Mono.empty();
                                                }),
                        concurrency)
                .collectList()
                .map(
                        results -> {
                            if (results == null || results.isEmpty()) {
                                return false;
                            }

                            // Sort results in descending startIndex order to avoid shifting
                            results.sort(
                                    (a, b) ->
                                            Integer.compare(
                                                    b.getT1().startIndex(),
                                                    a.getT1().startIndex()));

                            for (Tuple3<SummaryCandidate, Msg, Map<String, Object>> item :
                                    results) {
                                SummaryCandidate cand = item.getT1();
                                Msg summaryMsg = item.getT2();
                                Map<String, Object> metadata = item.getT3();

                                recordCompressionEvent(
                                        CompressionEvent.PREVIOUS_ROUND_CONVERSATION_SUMMARY,
                                        cand.startIndex(),
                                        cand.endIndex(),
                                        rawMessages,
                                        summaryMsg,
                                        metadata);

                                int removedCount = cand.endIndex() - cand.startIndex() + 1;
                                rawMessages.subList(cand.startIndex(), cand.endIndex() + 1).clear();
                                int insertIndex = cand.userIndex() + 1;
                                rawMessages.add(insertIndex, summaryMsg);

                                log.info(
                                        "Replaced {} messages [indices {}-{}] with summary at"
                                                + " index {}",
                                        removedCount,
                                        cand.startIndex(),
                                        cand.endIndex(),
                                        insertIndex);
                            }
                            return true;
                        });
    }

    /**
     * Generate a summary of previous round conversation messages using the model.
     *
     * <p>Returns a {@link Mono} that emits the summary {@link Msg}.
     * No {@code .block()} is used; all LLM calls stay inside the reactive pipeline.
     *
     * @param messages the messages to summarize
     * @param offloadUuid the UUID of offloaded messages (if any), null otherwise
     * @return Mono emitting the summary message
     */
    private Mono<Msg> summaryPreviousRoundConversationAsync(
            List<Msg> messages, String offloadUuid) {
        // Filter out plan-related tool calls (user messages are preserved)
        List<Msg> filteredMessages = MsgUtils.filterPlanRelatedToolCalls(messages);
        if (filteredMessages.size() < messages.size()) {
            log.info(
                    "Filtered out {} plan-related tool call messages from previous round"
                            + " conversation summary",
                    messages.size() - filteredMessages.size());
        }

        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("conversation_summary");

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
        newMessages.addAll(filteredMessages);
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.COMPRESSION_MESSAGE_LIST_END)
                                        .build())
                        .build());
        addPlanAwareHintIfNeeded(newMessages);

        return model.stream(newMessages, null, options)
                .concatMap(chunk -> processChunk(chunk, context))
                .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                .onErrorResume(InterruptedException.class, Mono::error)
                .map(
                        block -> {
                            if (block != null && block.getChatUsage() != null) {
                                log.info(
                                        "Conversation summary completed, input tokens: {},"
                                                + " output tokens: {}",
                                        block.getChatUsage().getInputTokens(),
                                        block.getChatUsage().getOutputTokens());
                            }

                            Map<String, Object> compressMeta = new HashMap<>();
                            if (offloadUuid != null) {
                                compressMeta.put("offloaduuid", offloadUuid);
                            }
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put("_compress_meta", compressMeta);
                            if (block != null && block.getChatUsage() != null) {
                                metadata.put(MessageMetadataKeys.CHAT_USAGE, block.getChatUsage());
                            }

                            String summaryContent = block != null ? block.getTextContent() : "";
                            String offloadTag =
                                    offloadUuid != null
                                            ? String.format(
                                                    Prompts.CONTEXT_OFFLOAD_TAG_FORMAT, offloadUuid)
                                            : "";
                            String finalContent =
                                    offloadTag.isEmpty()
                                            ? summaryContent
                                            : summaryContent + "\n" + offloadTag;

                            return Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .name("assistant")
                                    .content(TextBlock.builder().text(finalContent).build())
                                    .metadata(metadata)
                                    .build();
                        });
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

            // ASSISTANT messages with ToolUseBlock (tool_calls) must NOT be offloaded as a plain
            // text stub. Doing so strips the ToolUseBlock, leaving the subsequent TOOL result
            // messages without a preceding tool_calls assistant message, which violates the API
            // constraint: "messages with role 'tool' must be a response to a preceding message
            // with 'tool_calls'". These pairs are handled exclusively by Strategy 1.
            if (MsgUtils.isToolUseMessage(msg)) {
                continue;
            }

            // TOOL result messages can have their output content offloaded, but the
            // ToolResultBlock structure (id, name) MUST be preserved so that the API formatter
            // can still emit the correct tool_call_id / name fields. We handle them separately.
            if (MsgUtils.isToolResultMessage(msg)) {
                ToolResultBlock originalResult = msg.getFirstContentBlock(ToolResultBlock.class);
                if (originalResult != null) {
                    // Use the ToolResultBlock output text for size checking, because
                    // Msg.getTextContent() only extracts top-level TextBlocks and returns
                    // empty string for TOOL messages whose content is a ToolResultBlock.
                    String outputText =
                            originalResult.getOutput().stream()
                                    .filter(TextBlock.class::isInstance)
                                    .map(TextBlock.class::cast)
                                    .map(TextBlock::getText)
                                    .collect(Collectors.joining("\n"));
                    if (outputText.length() > threshold) {
                        String toolResultUuid = UUID.randomUUID().toString();
                        List<Msg> offloadMsg = new ArrayList<>();
                        offloadMsg.add(msg);
                        offload(toolResultUuid, offloadMsg);
                        log.info(
                                "Offloaded large tool result message: index={}, size={} chars,"
                                        + " uuid={}",
                                i,
                                outputText.length(),
                                toolResultUuid);

                        String preview =
                                outputText.length() > autoContextConfig.offloadSinglePreview
                                        ? outputText.substring(
                                                        0, autoContextConfig.offloadSinglePreview)
                                                + "..."
                                        : outputText;
                        String offloadHint =
                                preview
                                        + "\n"
                                        + String.format(
                                                Prompts.CONTEXT_OFFLOAD_TAG_FORMAT, toolResultUuid);

                        // Preserve ToolResultBlock structure (id, name, metadata) so the API
                        // formatter can emit the correct tool_call_id / name, and downstream
                        // consumers retain semantic flags (e.g. agentscope_suspended) after
                        // offloading.  Only the output text is replaced with the offload hint.
                        ToolResultBlock compressedResult =
                                ToolResultBlock.of(
                                        originalResult.getId(),
                                        originalResult.getName(),
                                        TextBlock.builder().text(offloadHint).build(),
                                        originalResult.getMetadata());

                        Map<String, Object> trCompressMeta = new HashMap<>();
                        trCompressMeta.put("offloaduuid", toolResultUuid);
                        Map<String, Object> trMetadata = new HashMap<>();
                        trMetadata.put("_compress_meta", trCompressMeta);

                        Msg replacementToolMsg =
                                Msg.builder()
                                        .role(msg.getRole())
                                        .name(msg.getName())
                                        .content(compressedResult)
                                        .metadata(trMetadata)
                                        .build();

                        int tokenBefore = TokenCounterUtil.calculateToken(List.of(msg));
                        int tokenAfter =
                                TokenCounterUtil.calculateToken(List.of(replacementToolMsg));
                        Map<String, Object> trEventMetadata = new HashMap<>();
                        trEventMetadata.put("inputToken", tokenBefore);
                        trEventMetadata.put("outputToken", tokenAfter);
                        trEventMetadata.put("time", 0.0);

                        String eventType =
                                lastKeep
                                        ? CompressionEvent.LARGE_MESSAGE_OFFLOAD_WITH_PROTECTION
                                        : CompressionEvent.LARGE_MESSAGE_OFFLOAD;
                        recordCompressionEvent(eventType, i, i, rawMessages, null, trEventMetadata);

                        rawMessages.set(i, replacementToolMsg);
                        hasOffloaded = true;
                    }
                }
                continue;
            }

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

            String offloadHint =
                    preview + "\n" + String.format(Prompts.CONTEXT_OFFLOAD_TAG_FORMAT, uuid);

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
     * that can be compressed. It searches, using a cursor-based {@code searchStartIndex},
     * for sequences of more than a minimum number of consecutive tool messages that appear
     * before the latest assistant message that should be preserved.
     *
     * <p>Strategy:
     * 1. If {@code rawMessages} has less than {@code lastKeep} messages, return {@code null}.
     * 2. Identify the latest assistant message and treat it and all messages after it as
     *    protected content that will not be compressed.
     * 3. Starting from {@code searchStartIndex}, search for the oldest range of consecutive
     *    tool messages (more than {@code minConsecutiveToolMessages} consecutive) that lies
     *    entirely before the protected region and can be compressed.
     * 4. If no eligible assistant message or compressible tool-message sequence is found
     *    in the searchable range, return {@code null}.
     *
     * @param rawMessages all raw messages
     * @param lastKeep number of recent messages to keep uncompressed
     * @param searchStartIndex the index to start searching from (used as a cursor)
     * @return Pair containing startIndex and endIndex (inclusive) of compressible tool messages, or {@code null} if none found
     */
    private Pair<Integer, Integer> extractPrevToolMsgsForCompress(
            List<Msg> rawMessages, int lastKeep, int searchStartIndex) {
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
        int actualStart = Math.max(0, searchStartIndex);
        for (int i = actualStart; i < searchEndIndex; i++) {
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
    /**
     * Compresses a list of tool invocation messages using LLM summarization.
     *
     * <p>Returns a {@link Mono} emitting the compressed {@link Msg}.
     * No {@code .block()} is used; the reactive pipeline is fully non-blocking.
     *
     * <p>If an {@code offloadUUid} is provided, the compressed message will include a hint
     * indicating that the original content can be reloaded via {@link ContextOffloadTool}.
     *
     * @param messages the list of tool invocation messages to compress
     * @param offloadUUid the UUID of the offloaded original messages, or null if not offloaded
     * @return Mono emitting the compressed tool invocation summary message
     */
    private Mono<Msg> compressToolsInvocationAsync(List<Msg> messages, String offloadUUid) {

        // Filter out plan-related tool calls before compression
        List<Msg> filteredMessages = MsgUtils.filterPlanRelatedToolCalls(messages);
        if (filteredMessages.size() < messages.size()) {
            log.info(
                    "Filtered out {} plan-related tool call messages from tool invocation"
                            + " compression",
                    messages.size() - filteredMessages.size());
        }

        GenerateOptions options = GenerateOptions.builder().build();
        ReasoningContext context = new ReasoningContext("tool_compress");
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
        newMessages.addAll(filteredMessages);
        newMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("user")
                        .content(
                                TextBlock.builder()
                                        .text(Prompts.COMPRESSION_MESSAGE_LIST_END)
                                        .build())
                        .build());
        addPlanAwareHintIfNeeded(newMessages);

        return model.stream(newMessages, null, options)
                .concatMap(chunk -> processChunk(chunk, context))
                .then(Mono.defer(() -> Mono.just(context.buildFinalMessage())))
                .onErrorResume(InterruptedException.class, Mono::error)
                .map(
                        block -> {
                            if (block != null && block.getChatUsage() != null) {
                                log.info(
                                        "Tool compression completed, input tokens: {},"
                                                + " output tokens: {}",
                                        block.getChatUsage().getInputTokens(),
                                        block.getChatUsage().getOutputTokens());
                            }

                            Map<String, Object> compressMeta = new HashMap<>();
                            if (offloadUUid != null) {
                                compressMeta.put("offloaduuid", offloadUUid);
                            }
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put("_compress_meta", compressMeta);
                            if (block != null && block.getChatUsage() != null) {
                                metadata.put(MessageMetadataKeys.CHAT_USAGE, block.getChatUsage());
                            }

                            String compressedContent = block != null ? block.getTextContent() : "";
                            String offloadTag =
                                    offloadUUid != null
                                            ? String.format(
                                                    Prompts.CONTEXT_OFFLOAD_TAG_FORMAT, offloadUUid)
                                            : "";
                            String finalContent =
                                    offloadTag.isEmpty()
                                            ? compressedContent
                                            : compressedContent + "\n" + offloadTag;

                            return Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .name("assistant")
                                    .content(TextBlock.builder().text(finalContent).build())
                                    .metadata(metadata)
                                    .build();
                        });
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

        // Build simplified plan state information
        StringBuilder planContext = new StringBuilder();

        // 1. Task overall goal
        if (currentPlan.getDescription() != null && !currentPlan.getDescription().isEmpty()) {
            planContext.append("Goal: ").append(currentPlan.getDescription()).append("\n");
        }

        // 2. Current progress
        List<SubTask> subtasks = currentPlan.getSubtasks();
        if (subtasks != null && !subtasks.isEmpty()) {
            List<SubTask> inProgressTasks =
                    subtasks.stream()
                            .filter(st -> st.getState() == SubTaskState.IN_PROGRESS)
                            .collect(Collectors.toList());

            if (!inProgressTasks.isEmpty()) {
                planContext.append("Current Progress: ");
                for (int i = 0; i < inProgressTasks.size(); i++) {
                    if (i > 0) {
                        planContext.append(", ");
                    }
                    planContext.append(inProgressTasks.get(i).getName());
                }
                planContext.append("\n");
            }

            // Count completed tasks for context
            long doneCount =
                    subtasks.stream().filter(st -> st.getState() == SubTaskState.DONE).count();
            long totalCount = subtasks.size();

            if (totalCount > 0) {
                planContext.append(
                        String.format(
                                "Progress: %d/%d subtasks completed\n", doneCount, totalCount));
            }
        }

        // 3. Appropriate supplement to task plan context
        if (currentPlan.getExpectedOutcome() != null
                && !currentPlan.getExpectedOutcome().isEmpty()) {
            planContext
                    .append("Expected Outcome: ")
                    .append(currentPlan.getExpectedOutcome())
                    .append("\n");
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
                    new OffloadContextState(new ConcurrentHashMap<>(offloadContext)));
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
                            offloadContext.putAll(new ConcurrentHashMap<>(state.offloadContext()));
                        });

        // Load compression context events
        List<CompressionEvent> compressEvents =
                session.getList(
                        sessionKey, "autoContextMemory_compressionEvents", CompressionEvent.class);
        compressionEvents.clear();
        compressionEvents.addAll(compressEvents);
    }
}
