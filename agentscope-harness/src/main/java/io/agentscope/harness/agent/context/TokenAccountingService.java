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
package io.agentscope.harness.agent.context;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Objects;

/**
 * Single source of truth for context token estimation.
 *
 * <p>Both the compaction trigger ({@code TokenCounterUtil}) and the final request budget
 * ({@code ContextTokenEstimator}) delegate here so a message or media block contributes the same
 * cost everywhere. Estimators are not provider usage; they are deliberately conservative so a
 * request that passes the trigger does not surprise the final budget check.
 *
 * <p>Text is counted per Unicode category: ASCII at {@value #ASCII_CHARS_PER_TOKEN} characters per
 * token, BMP non-ASCII (CJK and other multi-byte scripts) at one token per character, and
 * supplementary code points (emoji and rare scripts) at two tokens each. This is never lower than
 * the previous uniform {@code length / 2.5} baseline. All accumulation saturates at
 * {@link Long#MAX_VALUE}.
 */
public final class TokenAccountingService {

    private static final double ASCII_CHARS_PER_TOKEN = 2.5;
    private static final long MESSAGE_OVERHEAD = 5;
    private static final long TOOL_USE_OVERHEAD = 10;
    private static final long TOOL_RESULT_OVERHEAD = 8;
    private static final long TOOL_SCHEMA_OVERHEAD = 16;
    private static final String METHOD = "unified-ascii2.5-cjk1-supp2-v1";

    private final MediaTokenEstimator mediaEstimator;

    public TokenAccountingService(MediaTokenEstimator mediaEstimator) {
        this.mediaEstimator = Objects.requireNonNull(mediaEstimator);
    }

    public static TokenAccountingService defaults() {
        return new TokenAccountingService(MediaTokenEstimator.defaults());
    }

    /** Full request surface: messages plus tool schemas. */
    public record TokenSurface(List<Msg> messages, List<ToolSchema> tools) {}

    /** Breakdown of an estimate; {@code totalTokens} is the value budget checks consume. */
    public record TokenEstimate(
            long textTokens,
            long toolSchemaTokens,
            long mediaTokens,
            long overheadTokens,
            long totalTokens,
            String method,
            boolean exact) {}

    /** Estimates conversation messages only (no tool schemas); used by the compaction trigger. */
    public long countMessages(List<Msg> messages) {
        return estimate(new TokenSurface(messages, List.of())).totalTokens();
    }

    /** Estimates a full request including tool schemas; used by the final budget check. */
    public TokenEstimate estimate(TokenSurface surface) {
        long text = 0;
        long media = 0;
        long overhead = 0;
        if (surface.messages() != null) {
            for (Msg msg : surface.messages()) {
                if (msg == null) {
                    continue;
                }
                overhead = saturatedAdd(overhead, MESSAGE_OVERHEAD);
                text = saturatedAdd(text, countText(roleName(msg)));
                text = saturatedAdd(text, countText(msg.getName()));
                List<ContentBlock> content = msg.getContent();
                if (content != null) {
                    for (ContentBlock block : content) {
                        BlockCounts counts = countBlock(block);
                        text = saturatedAdd(text, counts.text());
                        media = saturatedAdd(media, counts.media());
                        overhead = saturatedAdd(overhead, counts.overhead());
                    }
                }
            }
        }
        long toolSchema = 0;
        if (surface.tools() != null) {
            for (ToolSchema tool : surface.tools()) {
                toolSchema =
                        saturatedAdd(
                                toolSchema,
                                saturatedAdd(
                                        TOOL_SCHEMA_OVERHEAD,
                                        countText(JsonUtils.getJsonCodec().toJson(tool))));
            }
        }
        long total = saturatedAdd(saturatedAdd(text, toolSchema), saturatedAdd(media, overhead));
        return new TokenEstimate(text, toolSchema, media, overhead, total, METHOD, false);
    }

    private record BlockCounts(long text, long media, long overhead) {}

    private BlockCounts countBlock(ContentBlock block) {
        if (block == null) {
            return new BlockCounts(0, 0, 0);
        }
        if (block instanceof TextBlock text) {
            return new BlockCounts(countText(text.getText()), 0, 0);
        }
        if (block instanceof ThinkingBlock thinking) {
            return new BlockCounts(countText(thinking.getThinking()), 0, 0);
        }
        if (block instanceof ToolUseBlock use) {
            long text =
                    saturatedAdd(
                            countText(use.getId()),
                            saturatedAdd(
                                    countText(use.getName()),
                                    saturatedAdd(
                                            countText(
                                                    JsonUtils.getJsonCodec()
                                                            .toJson(use.getInput())),
                                            countText(use.getContent()))));
            return new BlockCounts(text, 0, TOOL_USE_OVERHEAD);
        }
        if (block instanceof ToolResultBlock result) {
            long text = saturatedAdd(countText(result.getId()), countText(result.getName()));
            long media = 0;
            long overhead = TOOL_RESULT_OVERHEAD;
            List<ContentBlock> output = result.getOutput();
            if (output != null) {
                for (ContentBlock child : output) {
                    BlockCounts counts = countBlock(child);
                    text = saturatedAdd(text, counts.text());
                    media = saturatedAdd(media, counts.media());
                    overhead = saturatedAdd(overhead, counts.overhead());
                }
            }
            return new BlockCounts(text, media, overhead);
        }
        // Media blocks (Image/Audio/Video/Data) and any other unknown block types.
        return new BlockCounts(0, mediaEstimator.estimate(block), 0);
    }

    /**
     * Counts tokens in a string, separating ASCII, BMP non-ASCII and supplementary code points.
     *
     * <p>The result is never lower than {@code ceil(length / 2.5)}, the previous uniform baseline.
     */
    public long countText(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        long ascii = 0;
        long bmpNonAscii = 0;
        long supplementary = 0;
        int i = 0;
        int length = value.length();
        while (i < length) {
            char c = value.charAt(i);
            if (c < 0x80) {
                ascii++;
                i++;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < length
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                supplementary++;
                i += 2;
            } else {
                bmpNonAscii++;
                i++;
            }
        }
        long asciiTokens = (long) Math.ceil(ascii / ASCII_CHARS_PER_TOKEN);
        return saturatedAdd(asciiTokens, saturatedAdd(bmpNonAscii, supplementary * 2));
    }

    private static String roleName(Msg msg) {
        return msg.getRole() == null ? null : msg.getRole().name();
    }

    private static long saturatedAdd(long a, long b) {
        long result = a + b;
        if (((a ^ result) & (b ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }
}
