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
package io.agentscope.harness.agent.memory.compaction;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.hook.CompactionHook;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig.TruncateArgsConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Check trigger</b> — token count or message count exceeds threshold</li>
 *   <li><b>Determine cutoff</b> — find the earliest index that keeps the tail within the
 *       "keep" budget; never split an ASSISTANT tool-call from its TOOL result(s)</li>
 *   <li><b>Memory flush</b> (optional) — extract long-term memories from the prefix via
 *       {@link MemoryFlushManager#flushMemories}</li>
 *   <li><b>Message offload</b> (optional) — persist the full conversation to the session
 *       JSONL via {@link MemoryFlushManager#offloadMessages}</li>
 *   <li><b>Summarize</b> — one LLM call to distill the prefix into a structured summary</li>
 *   <li><b>Rebuild</b> — return {@code [summaryUserMsg] + preservedTail}</li>
 * </ol>
 *
 * <p>The caller is responsible for updating both the agent's working memory and the LLM-facing
 * message list (see {@link CompactionHook}).
 */
public class ConversationCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompactor.class);

    /** Marker stored in message name to identify injected summary messages. */
    public static final String SUMMARY_MSG_NAME = "__compaction_summary__";

    private final Model model;
    private final MemoryFlushManager flushManager;

    public ConversationCompactor(Model model, MemoryFlushManager flushManager) {
        this.model = model;
        this.flushManager = flushManager;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs compaction on the supplied conversation messages if a trigger condition is met.
     *
     * <p>Only <em>conversation</em> messages (non-SYSTEM) should be passed. The caller must
     * separate system messages before invoking this method and re-prepend them after.
     *
     * @param conversationMessages non-SYSTEM messages (USER / ASSISTANT / TOOL)
     * @param config               compaction configuration
     * @param agentId              agent identifier used for the memory offload path
     * @param sessionId            session identifier used for the memory offload path
     * @return {@code Optional.empty()} when no compaction was needed; otherwise the replacement
     *         message list consisting of {@code [summaryUserMsg] + preservedTail}
     */
    public Mono<Optional<List<Msg>>> compactIfNeeded(
            List<Msg> conversationMessages,
            CompactionConfig config,
            String agentId,
            String sessionId) {

        if (conversationMessages == null || conversationMessages.isEmpty()) {
            return Mono.just(Optional.empty());
        }

        // Step 1: Lightweight arg truncation (non-LLM). Runs at a lower threshold than
        List<Msg> messages = truncateArgs(conversationMessages, config.getTruncateArgsConfig());

        int totalTokens = TokenCounterUtil.calculateToken(messages);
        if (!shouldCompact(messages, totalTokens, config)) {
            return Mono.just(Optional.empty());
        }

        int cutoff = determineCutoffIndex(messages, totalTokens, config);
        if (cutoff <= 0) {
            log.debug("Compaction triggered but safe cutoff is 0 — skipping");
            return Mono.just(Optional.empty());
        }

        // Filter previous summary messages from the prefix before offloading to avoid
        // re-storing already-archived summaries.
        List<Msg> prefix = filterSummaryMessages(new ArrayList<>(messages.subList(0, cutoff)));
        List<Msg> tail = new ArrayList<>(messages.subList(cutoff, messages.size()));

        log.info(
                "Compaction triggered: total={} msgs / {} tokens, cutoff={}, keeping={} msgs",
                messages.size(),
                totalTokens,
                cutoff,
                tail.size());

        // Step 2: Flush long-term memories from the prefix (best-effort).
        Mono<Void> flushStep =
                config.isFlushBeforeCompact()
                        ? flushManager
                                .flushMemories(prefix)
                                .doOnSuccess(v -> log.debug("Memory flush before compaction done"))
                                .onErrorResume(
                                        e -> {
                                            log.warn(
                                                    "Memory flush before compaction failed: {}",
                                                    e.getMessage());
                                            return Mono.empty();
                                        })
                        : Mono.empty();

        // Step 3: Offload raw messages to JSONL and capture the file path.
        // If offload fails, we continue with null — the summary message falls back to the
        // simple format without a file reference.
        Mono<String> offloadStep;
        if (config.isOffloadBeforeCompact()) {
            offloadStep =
                    Mono.fromCallable(
                                    () -> {
                                        flushManager.offloadMessages(messages, agentId, sessionId);
                                        return flushManager.resolveOffloadPath(agentId, sessionId);
                                    })
                            .doOnSuccess(
                                    path ->
                                            log.debug(
                                                    "Message offload before compaction done,"
                                                            + " path={}",
                                                    path))
                            .onErrorResume(
                                    e -> {
                                        log.warn(
                                                "Message offload before compaction failed: {}",
                                                e.getMessage());
                                        return Mono.just("");
                                    });
        } else {
            offloadStep = Mono.just("");
        }

        // Step 4: LLM summarization of the prefix, combined with the offload result.
        return flushStep
                .then(offloadStep)
                .flatMap(
                        offloadPath ->
                                summarizePrefix(prefix, config)
                                        .map(
                                                summary -> {
                                                    String filePath =
                                                            offloadPath.isBlank()
                                                                    ? null
                                                                    : offloadPath;
                                                    Msg summaryMsg =
                                                            buildSummaryMessage(summary, filePath);
                                                    List<Msg> compacted = new ArrayList<>();
                                                    compacted.add(summaryMsg);
                                                    compacted.addAll(tail);
                                                    log.info(
                                                            "Compaction complete: {} msgs → 1"
                                                                    + " summary + {} tail = {}"
                                                                    + " total",
                                                            messages.size(),
                                                            tail.size(),
                                                            compacted.size());
                                                    return Optional.of(compacted);
                                                }));
    }

    // -------------------------------------------------------------------------
    // Trigger logic
    // -------------------------------------------------------------------------

    private static boolean shouldCompact(
            List<Msg> messages, int totalTokens, CompactionConfig config) {
        if (config.getTriggerMessages() > 0 && messages.size() >= config.getTriggerMessages()) {
            log.debug(
                    "Compaction trigger: message count {} >= {}",
                    messages.size(),
                    config.getTriggerMessages());
            return true;
        }
        if (config.getTriggerTokens() > 0 && totalTokens >= config.getTriggerTokens()) {
            log.debug(
                    "Compaction trigger: token count {} >= {}",
                    totalTokens,
                    config.getTriggerTokens());
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Cutoff / partition logic
    // -------------------------------------------------------------------------

    /**
     * Determines the cutoff index separating the prefix-to-summarize from the tail-to-keep.
     *
     * <p>The cutoff is adjusted so that ASSISTANT/TOOL pairs are never split.
     */
    private static int determineCutoffIndex(
            List<Msg> messages, int totalTokens, CompactionConfig config) {
        int rawCutoff;
        if (config.getKeepTokens() > 0) {
            rawCutoff = findTokenBasedCutoff(messages, totalTokens, config.getKeepTokens());
        } else {
            rawCutoff = findMessageBasedCutoff(messages, config.getKeepMessages());
        }
        return findSafeCutoffPoint(messages, rawCutoff);
    }

    /** Returns the earliest index such that {@code messages[index:]} fits within the token budget. */
    private static int findTokenBasedCutoff(List<Msg> messages, int totalTokens, int keepTokens) {
        if (totalTokens <= keepTokens) {
            return 0;
        }
        // Binary search for the earliest index where the suffix token count <= keepTokens
        int left = 0;
        int right = messages.size();
        int candidate = messages.size();
        int maxIter = Integer.SIZE - Integer.numberOfLeadingZeros(messages.size()) + 1;
        for (int i = 0; i < maxIter && left < right; i++) {
            int mid = (left + right) / 2;
            if (TokenCounterUtil.calculateToken(messages.subList(mid, messages.size()))
                    <= keepTokens) {
                candidate = mid;
                right = mid;
            } else {
                left = mid + 1;
            }
        }
        // Clamp so at least 1 message is always kept
        return Math.min(candidate, messages.size() - 1);
    }

    /** Returns the cutoff that keeps the last {@code keepMessages} messages verbatim. */
    private static int findMessageBasedCutoff(List<Msg> messages, int keepMessages) {
        if (messages.size() <= keepMessages) {
            return 0;
        }
        return messages.size() - keepMessages;
    }

    /**
     * Adjusts the cutoff to avoid splitting ASSISTANT tool-call/TOOL-result pairs.
     *
     * <p>If the message at {@code cutoffIndex} has role TOOL, we search backward for the
     * ASSISTANT message whose tool-use blocks correspond to those tool results and move the
     * cutoff to include that ASSISTANT message in the prefix (i.e., cut before it).
     *
     */
    private static int findSafeCutoffPoint(List<Msg> messages, int cutoffIndex) {
        if (cutoffIndex <= 0 || cutoffIndex >= messages.size()) {
            return cutoffIndex;
        }

        Msg atCutoff = messages.get(cutoffIndex);
        if (atCutoff.getRole() != MsgRole.TOOL) {
            return cutoffIndex;
        }

        // Collect tool-call IDs from consecutive TOOL messages at/after the cutoff
        List<String> toolCallIds = new ArrayList<>();
        int idx = cutoffIndex;
        while (idx < messages.size() && messages.get(idx).getRole() == MsgRole.TOOL) {
            for (ContentBlock block : messages.get(idx).getContent()) {
                if (block instanceof ToolResultBlock tr && tr.getId() != null) {
                    toolCallIds.add(tr.getId());
                }
            }
            idx++;
        }

        if (toolCallIds.isEmpty()) {
            // No IDs found — advance past all TOOL messages to avoid orphaned results
            return idx;
        }

        // Search backward for the ASSISTANT message that issued those tool calls
        for (int i = cutoffIndex - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolUseBlock tu && toolCallIds.contains(tu.getId())) {
                        // Move the cutoff to just before this ASSISTANT message
                        return i;
                    }
                }
            }
        }

        // Fallback: advance past all TOOL messages
        return idx;
    }

    // -------------------------------------------------------------------------
    // Summarization
    // -------------------------------------------------------------------------

    private Mono<String> summarizePrefix(List<Msg> prefix, CompactionConfig config) {
        if (prefix.isEmpty()) {
            return Mono.just("No previous conversation history.");
        }

        String formatted = formatMessagesForSummary(prefix);
        String prompt = config.getSummaryPrompt().replace("{messages}", formatted);

        List<Msg> summarizationInput =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(TextBlock.builder().text(prompt).build())
                                .build());

        return model.stream(summarizationInput, null, null)
                .reduce(
                        new StringBuilder(),
                        (sb, resp) -> {
                            if (resp.getContent() != null) {
                                for (ContentBlock block : resp.getContent()) {
                                    if (block instanceof TextBlock tb && tb.getText() != null) {
                                        sb.append(tb.getText());
                                    }
                                }
                            }
                            return sb;
                        })
                .map(StringBuilder::toString)
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .defaultIfEmpty("(Summary unavailable)")
                .onErrorResume(
                        e -> {
                            log.warn("Summarization LLM call failed: {}", e.getMessage());
                            return Mono.just("(Summarization failed: " + e.getMessage() + ")");
                        });
    }

    /**
     * Formats a list of messages as a human-readable text block for the summarization LLM.
     *
     * <p>Renders TEXT blocks verbatim; TOOL_USE and TOOL_RESULT blocks as concise inline
     * representations so the summarizer understands what actions were taken.
     */
    static String formatMessagesForSummary(List<Msg> messages) {
        return messages.stream()
                .filter(m -> m.getRole() != null && m.getRole() != MsgRole.SYSTEM)
                .map(ConversationCompactor::renderMessageForSummary)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private static String renderMessageForSummary(Msg msg) {
        String roleLabel =
                switch (msg.getRole()) {
                    case USER -> "Human";
                    case ASSISTANT -> "AI";
                    case TOOL -> "Tool";
                    default -> msg.getRole().name();
                };

        StringBuilder sb = new StringBuilder(roleLabel).append(": ");
        boolean first = true;
        for (ContentBlock block : msg.getContent()) {
            if (!first) sb.append(" ");
            first = false;
            if (block instanceof TextBlock tb && tb.getText() != null && !tb.getText().isBlank()) {
                sb.append(tb.getText().strip());
            } else if (block instanceof ToolUseBlock tu) {
                sb.append("[tool_call: ").append(tu.getName()).append("]");
            } else if (block instanceof ToolResultBlock tr) {
                String text = extractToolResultText(tr);
                sb.append("[tool_result: ")
                        .append(tr.getName() != null ? tr.getName() : "?")
                        .append("] ");
                if (!text.isBlank()) {
                    sb.append(text.length() > 500 ? text.substring(0, 500) + "..." : text);
                }
            }
        }
        return sb.toString().strip();
    }

    private static String extractToolResultText(ToolResultBlock tr) {
        if (tr.getOutput() == null) return "";
        return tr.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining(" "));
    }

    // -------------------------------------------------------------------------
    // Summary message construction
    // -------------------------------------------------------------------------

    /**
     * Builds a USER message carrying the summary.
     *
     * <p>When {@code filePath} is non-null, the message includes a reference to where the full
     * conversation history was offloaded.
     * When null, falls back to the simple "summary to date" format.
     *
     * <p>The message name is set to {@link #SUMMARY_MSG_NAME} so hooks can identify and
     * skip summary messages during future flush/offload cycles.
     */
    private static Msg buildSummaryMessage(String summary, String filePath) {
        String content;
        if (filePath != null) {
            content =
                    "You are in the middle of a conversation that has been summarized.\n\n"
                            + "The full conversation history has been saved to "
                            + filePath
                            + " should you need to refer back to it for details.\n\n"
                            + "A condensed summary follows:\n\n"
                            + "<summary>\n"
                            + summary
                            + "\n</summary>";
        } else {
            content = "Here is a summary of the conversation to date:\n\n" + summary;
        }
        return Msg.builder()
                .role(MsgRole.USER)
                .name(SUMMARY_MSG_NAME)
                .content(TextBlock.builder().text(content).build())
                .build();
    }

    // -------------------------------------------------------------------------
    // Summary message filtering (chained summarization support)
    // -------------------------------------------------------------------------

    /**
     * Removes previously injected summary messages from a list.
     *
     * <p>During chained summarization the working memory may already contain a summary USER
     * message from a prior compaction round. We filter these out before offloading to the
     * backend so the original messages (already stored there) are not duplicated.
     */
    static List<Msg> filterSummaryMessages(List<Msg> messages) {
        return messages.stream()
                .filter(m -> !SUMMARY_MSG_NAME.equals(m.getName()))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Argument truncation (pre-summarization, non-LLM)
    // -------------------------------------------------------------------------

    /**
     * Truncates large {@code ToolUseBlock} argument values in old messages.
     *
     * <p>This is a lightweight, non-LLM pass that fires at a separate (lower) threshold
     * than full summarization. Only messages before the keep window are modified; recent
     * messages are left intact.
     *
     * <p>When {@code truncateConfig} is {@code null}, the original list is returned unchanged.
     */
    List<Msg> truncateArgs(List<Msg> messages, TruncateArgsConfig truncateConfig) {
        if (truncateConfig == null || messages == null || messages.isEmpty()) {
            return messages;
        }

        int totalTokens = TokenCounterUtil.calculateToken(messages);
        if (!shouldTruncateArgs(messages, totalTokens, truncateConfig)) {
            return messages;
        }

        int cutoff = determineTruncateCutoff(messages, truncateConfig);
        if (cutoff >= messages.size()) {
            return messages; // Nothing in the truncation window
        }

        boolean anyModified = false;
        List<Msg> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            if (i < cutoff && msg.getRole() == MsgRole.ASSISTANT) {
                Msg truncated = truncateToolUseArgs(msg, truncateConfig);
                result.add(truncated);
                if (truncated != msg) {
                    anyModified = true;
                }
            } else {
                result.add(msg);
            }
        }

        if (anyModified) {
            log.debug("Arg truncation applied to messages before index {}", cutoff);
        }
        return anyModified ? result : messages;
    }

    private static boolean shouldTruncateArgs(
            List<Msg> messages, int totalTokens, TruncateArgsConfig cfg) {
        if (cfg.getTriggerMessages() > 0 && messages.size() >= cfg.getTriggerMessages()) {
            return true;
        }
        return cfg.getTriggerTokens() > 0 && totalTokens >= cfg.getTriggerTokens();
    }

    private static int determineTruncateCutoff(List<Msg> messages, TruncateArgsConfig cfg) {
        if (cfg.getKeepTokens() > 0) {
            // Token-budget-based keep window: scan from the end
            int tokensKept = 0;
            for (int i = messages.size() - 1; i >= 0; i--) {
                int msgTokens = TokenCounterUtil.calculateToken(List.of(messages.get(i)));
                if (tokensKept + msgTokens > cfg.getKeepTokens()) {
                    return i + 1;
                }
                tokensKept += msgTokens;
            }
            return 0;
        }
        // Message-count keep window
        int keep = cfg.getKeepMessages();
        return Math.max(0, messages.size() - keep);
    }

    /**
     * Returns a copy of the message with large {@code ToolUseBlock} argument values shortened.
     * If no argument exceeds the limit, the original message reference is returned unchanged.
     */
    private static Msg truncateToolUseArgs(Msg msg, TruncateArgsConfig cfg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return msg;
        }

        boolean anyModified = false;
        List<ContentBlock> newBlocks = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block instanceof ToolUseBlock tu) {
                ToolUseBlock truncated = truncateToolUseBlock(tu, cfg);
                newBlocks.add(truncated);
                if (truncated != tu) {
                    anyModified = true;
                }
            } else {
                newBlocks.add(block);
            }
        }

        if (!anyModified) {
            return msg;
        }
        return Msg.builder().role(msg.getRole()).name(msg.getName()).content(newBlocks).build();
    }

    /**
     * Returns a copy of the {@code ToolUseBlock} with large string arg values truncated,
     * or the original if no truncation was needed.
     */
    private static ToolUseBlock truncateToolUseBlock(ToolUseBlock tu, TruncateArgsConfig cfg) {
        Map<String, Object> input = tu.getInput();
        if (input == null || input.isEmpty()) {
            return tu;
        }

        boolean anyModified = false;
        Map<String, Object> newInput = new HashMap<>(input);
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getValue() instanceof String s && s.length() > cfg.getMaxArgLength()) {
                newInput.put(entry.getKey(), s.substring(0, 20) + cfg.getTruncationText());
                anyModified = true;
            }
        }

        if (!anyModified) {
            return tu;
        }
        return ToolUseBlock.builder().id(tu.getId()).name(tu.getName()).input(newInput).build();
    }
}
