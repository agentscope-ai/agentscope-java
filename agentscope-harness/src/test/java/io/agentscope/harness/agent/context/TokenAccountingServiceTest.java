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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenAccountingServiceTest {

    private final TokenAccountingService service = TokenAccountingService.defaults();

    @Test
    void asciiTextCountsAtTwoAndAHalfCharsPerToken() {
        assertEquals(400, service.countText("x".repeat(1000)));
        assertEquals(2, service.countText("abcd"));
    }

    @Test
    void bmpNonAsciiCountsOneTokenPerCharacter() {
        assertEquals(100, service.countText("中".repeat(100)));
    }

    @Test
    void supplementaryCodePointsCountTwoTokensEach() {
        String emoji = "\uD83D\uDE00"; // U+1F600, one supplementary code point
        assertEquals(10, service.countText(emoji.repeat(5)));
    }

    @Test
    void neverLowerThanUniformBaseline() {
        String[] samples = {"plain ascii", "混合内容 with 中文", "\uD83D\uDE00 emoji 混合", "x".repeat(37)};
        for (String sample : samples) {
            long baseline = (long) Math.ceil(sample.length() / 2.5);
            assertTrue(
                    service.countText(sample) >= baseline,
                    "unified count must not undercount: " + sample);
        }
    }

    @Test
    void thinkingContentMatchesLegacyMessageAssertions() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("x".repeat(1_000)).build())
                        .build();

        assertEquals(409, service.countMessages(List.of(message)));
    }

    @Test
    void thinkingContentInsideToolResultMatchesLegacyAssertions() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call-1")
                                        .name("search")
                                        .output(
                                                ThinkingBlock.builder()
                                                        .thinking("x".repeat(1_000))
                                                        .build())
                                        .build())
                        .build();

        assertEquals(421, service.countMessages(List.of(message)));
    }

    @Test
    void mediaBlockChargesTheConfiguredConstant() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder().text("see").build(),
                                ImageBlock.builder()
                                        .source(
                                                new URLSource(
                                                        "https://example.com/a.png", "image/png"))
                                        .build())
                        .build();

        var estimate =
                service.estimate(new TokenAccountingService.TokenSurface(List.of(message), null));
        assertEquals(MediaTokenEstimator.DEFAULT_MEDIA_TOKENS, estimate.mediaTokens());
    }

    @Test
    void toolSchemaIsCountedWithItsOwnOverhead() {
        ToolSchema tool =
                ToolSchema.builder()
                        .name("search")
                        .description("search")
                        .parameters(Map.of("type", "object"))
                        .build();

        var estimate =
                service.estimate(new TokenAccountingService.TokenSurface(List.of(), List.of(tool)));
        assertTrue(estimate.toolSchemaTokens() >= 16);
        assertEquals(estimate.toolSchemaTokens(), estimate.totalTokens());
    }

    @Test
    void breakdownSumsToTotal() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("answer").build(),
                                ToolUseBlock.builder()
                                        .id("t1")
                                        .name("search")
                                        .input(Map.of("q", "x"))
                                        .build())
                        .build();

        var estimate =
                service.estimate(new TokenAccountingService.TokenSurface(List.of(message), null));
        assertEquals(
                estimate.textTokens()
                        + estimate.toolSchemaTokens()
                        + estimate.mediaTokens()
                        + estimate.overheadTokens(),
                estimate.totalTokens());
    }
}
