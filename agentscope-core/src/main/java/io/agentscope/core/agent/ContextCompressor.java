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
package io.agentscope.core.agent;

import io.agentscope.core.agent.config.ContextConfig;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Three-strategy conversation context compressor.
 *
 * <ol>
 *   <li><b>Tool-result truncation</b> &mdash; per-message cap on a tool-result
 *       {@link Msg}'s aggregated text length, applied unconditionally before the
 *       tool message lands in {@link AgentState#contextMutable()}.</li>
 *   <li><b>Old-message snip</b> &mdash; when an estimated token count exceeds
 *       {@link ContextConfig#triggerRatio()} of {@link #CONTEXT_WINDOW_TOKENS},
 *       drop the oldest non-system messages until the count falls under
 *       {@link ContextConfig#reserveRatio()} of the same window.</li>
 *   <li><b>Micro-compact</b> &mdash; if snipping cannot get below the reserve
 *       ratio (e.g. one very large message remains), invoke the chat model with
 *       {@link ContextConfig#compressionPrompt()} to produce a summary,
 *       persist it via {@link AgentState#setSummary(String)}, and replace
 *       {@link AgentState#contextMutable()} with a single rendered hint
 *       message derived from {@link ContextConfig#summaryTemplate()}.</li>
 * </ol>
 *
 * <p>Token counts are estimated as {@code totalTextChars / 4}; a constant
 * {@link #CONTEXT_WINDOW_TOKENS} of 128k stands in for a model-specific
 * context window until per-model lookups land in a later stage.
 */
public final class ContextCompressor {

    /** Approximate context window used for trigger / reserve ratio math. */
    public static final int CONTEXT_WINDOW_TOKENS = 128_000;

    /** Marker appended to truncated tool-result text. */
    public static final String TRUNCATION_SUFFIX = "...[truncated]";

    private final ContextConfig config;
    private final Model model;

    public ContextCompressor(ContextConfig config, Model model) {
        this.config = Objects.requireNonNull(config, "config");
        this.model = Objects.requireNonNull(model, "model");
    }

    /**
     * Apply strategy 1 to a tool-result {@link Msg}. The output content is
     * rebuilt so that the cumulative length of all {@link TextBlock}s embedded
     * in any contained {@link ToolResultBlock}s does not exceed
     * {@link ContextConfig#toolResultLimit()} characters. Non-text outputs
     * (e.g. images, data blocks) pass through unchanged.
     *
     * @param toolMsg the tool-role message to inspect
     * @return the original message when no truncation is required, otherwise a
     *     new {@link Msg} with shortened tool-result text
     */
    public Msg truncateToolResultMsg(Msg toolMsg) {
        if (toolMsg == null || toolMsg.getRole() != MsgRole.TOOL) {
            return toolMsg;
        }
        int limit = config.toolResultLimit();
        if (limit <= 0) {
            return toolMsg;
        }
        List<ContentBlock> rebuilt = new ArrayList<>();
        boolean anyChanged = false;
        for (ContentBlock block : toolMsg.getContent()) {
            if (block instanceof ToolResultBlock trb) {
                ToolResultBlock capped = capToolResult(trb, limit);
                if (capped != trb) {
                    anyChanged = true;
                }
                rebuilt.add(capped);
            } else {
                rebuilt.add(block);
            }
        }
        if (!anyChanged) {
            return toolMsg;
        }
        return Msg.builder()
                .role(toolMsg.getRole())
                .name(toolMsg.getName())
                .content(rebuilt)
                .build();
    }

    /**
     * Apply strategies 2 and 3 in sequence if the current context tokens
     * exceed the trigger threshold. Mutates {@link AgentState#contextMutable()}
     * in place and may update {@link AgentState#getSummary()}.
     */
    public Mono<Void> maybeCompress(AgentState state) {
        return Mono.defer(
                () -> {
                    int currentTokens = estimateTokens(state.getContext());
                    int triggerTokens = (int) (CONTEXT_WINDOW_TOKENS * config.triggerRatio());
                    int reserveTokens = (int) (CONTEXT_WINDOW_TOKENS * config.reserveRatio());
                    if (currentTokens <= triggerTokens) {
                        return Mono.empty();
                    }
                    snipOldMessages(state.contextMutable(), reserveTokens);
                    if (estimateTokens(state.getContext()) <= reserveTokens) {
                        return Mono.empty();
                    }
                    return microCompact(state);
                });
    }

    // ==================== Internals ====================

    private static ToolResultBlock capToolResult(ToolResultBlock trb, int limit) {
        List<ContentBlock> output = trb.getOutput();
        if (output == null || output.isEmpty()) {
            return trb;
        }
        int totalTextLen = 0;
        for (ContentBlock b : output) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                totalTextLen += tb.getText().length();
            }
        }
        if (totalTextLen <= limit) {
            return trb;
        }
        int budget = Math.max(0, limit - TRUNCATION_SUFFIX.length());
        List<ContentBlock> rebuilt = new ArrayList<>();
        int consumed = 0;
        boolean suffixAppended = false;
        for (ContentBlock b : output) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                String t = tb.getText();
                if (consumed >= budget) {
                    if (!suffixAppended) {
                        rebuilt.add(TextBlock.builder().text(TRUNCATION_SUFFIX).build());
                        suffixAppended = true;
                    }
                    continue;
                }
                int remaining = budget - consumed;
                if (t.length() <= remaining) {
                    rebuilt.add(b);
                    consumed += t.length();
                } else {
                    rebuilt.add(
                            TextBlock.builder()
                                    .text(t.substring(0, remaining) + TRUNCATION_SUFFIX)
                                    .build());
                    consumed = budget;
                    suffixAppended = true;
                }
            } else {
                rebuilt.add(b);
            }
        }
        return new ToolResultBlock(trb.getId(), trb.getName(), rebuilt, trb.getMetadata());
    }

    /**
     * Estimate the token cost of a list of messages using a character-count
     * heuristic ({@code chars / 4}).
     */
    public static int estimateTokens(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return 0;
        }
        long chars = 0;
        for (Msg msg : msgs) {
            chars += charCount(msg);
        }
        return (int) Math.min(Integer.MAX_VALUE, chars / 4);
    }

    private static int charCount(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return 0;
        }
        int total = 0;
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                total += tb.getText().length();
            } else if (block instanceof ToolResultBlock trb && trb.getOutput() != null) {
                for (ContentBlock inner : trb.getOutput()) {
                    if (inner instanceof TextBlock tb2 && tb2.getText() != null) {
                        total += tb2.getText().length();
                    }
                }
            }
        }
        return total;
    }

    /**
     * Remove oldest non-system messages in place until the estimated token
     * count is at or below {@code reserveTokens}. System messages are always
     * preserved.
     */
    static int snipOldMessages(List<Msg> context, int reserveTokens) {
        int removed = 0;
        int i = 0;
        while (i < context.size() && estimateTokens(context) > reserveTokens) {
            Msg candidate = context.get(i);
            if (candidate.getRole() == MsgRole.SYSTEM) {
                i++;
                continue;
            }
            context.remove(i);
            removed++;
        }
        return removed;
    }

    private Mono<Void> microCompact(AgentState state) {
        List<Msg> baseMessages = new ArrayList<>(state.getContext());
        baseMessages.add(
                Msg.builder().role(MsgRole.USER).textContent(config.compressionPrompt()).build());
        return model.stream(baseMessages, List.of(), GenerateOptions.builder().build())
                .collectList()
                .doOnNext(
                        chunks -> {
                            String summary = aggregateText(chunks);
                            state.setSummary(summary);
                            String rendered = renderSummary(summary);
                            List<Msg> ctx = state.contextMutable();
                            Msg preservedSystem = firstSystemOrNull(ctx);
                            ctx.clear();
                            if (preservedSystem != null) {
                                ctx.add(preservedSystem);
                            }
                            ctx.add(Msg.builder().role(MsgRole.USER).textContent(rendered).build());
                        })
                .then();
    }

    private static String aggregateText(List<ChatResponse> chunks) {
        StringBuilder sb = new StringBuilder();
        for (ChatResponse chunk : chunks) {
            if (chunk.getContent() == null) {
                continue;
            }
            for (ContentBlock block : chunk.getContent()) {
                if (block instanceof TextBlock tb && tb.getText() != null) {
                    sb.append(tb.getText());
                }
            }
        }
        return sb.toString();
    }

    private String renderSummary(String summary) {
        String template = config.summaryTemplate();
        if (template == null || template.isEmpty()) {
            return summary;
        }
        return template.replace("{task_overview}", summary)
                .replace("{current_state}", "")
                .replace("{important_discoveries}", "")
                .replace("{next_steps}", "")
                .replace("{context_to_preserve}", "");
    }

    private static Msg firstSystemOrNull(List<Msg> ctx) {
        for (Msg m : ctx) {
            if (m.getRole() == MsgRole.SYSTEM) {
                return m;
            }
        }
        return null;
    }
}
