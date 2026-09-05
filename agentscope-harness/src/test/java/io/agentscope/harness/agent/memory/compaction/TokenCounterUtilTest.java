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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenCounterUtilTest {
    @Test
    void hintTextContributesItsFullLength() {
        Msg hint =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(new HintBlock("hint", "资料".repeat(40_000)))
                        .build();
        assertTrue(TokenCounterUtil.calculateToken(List.of(hint)) > 70_000);
    }

    @Test
    void reasoningHistoryIsCountedAsTextRatherThanFixedOverhead() {
        Msg thinking =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("分析".repeat(40_000)).build())
                        .build();
        assertTrue(TokenCounterUtil.calculateToken(List.of(thinking)) > 70_000);
    }

    @Test
    void chineseContextIsNotEstimatedAsAscii() {
        assertTrue(count("中".repeat(80_000)) > 70_000);
        assertTrue(count("a".repeat(80_000)) < 70_000);
    }

    @Test
    void asciiEstimateRemainsCompatible() {
        int overhead = count("");
        for (String text : List.of("a", "hello", "some English text", "{}[]:12345")) {
            assertEquals(overhead + (int) Math.ceil(text.length() / 2.5), count(text));
        }
    }

    @Test
    void mixedTextAndSupplementaryCharactersRetainTheirContribution() {
        int overhead = count("");
        assertEquals(overhead + 8, count("hello你好中文世界"));
        assertTrue(count("你好🙂𠀀") - overhead >= 4);
        assertTrue(count("你好🙂𠀀") >= count("你好"));
        assertEquals(0, TokenCounterUtil.calculateToken(null));
        assertEquals(0, TokenCounterUtil.calculateToken(List.of()));
    }

    @Test
    void nestedToolArgumentsAreCountedAsEscapedJson() {
        Map<String, Object> arguments =
                Map.of("nested", Map.of("items", List.of("\"\\\n".repeat(100), "中文")));
        Msg structured =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call")
                                        .name("tool")
                                        .input(arguments)
                                        .build())
                        .build();
        Msg raw =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call")
                                        .name("tool")
                                        .content(JsonUtils.getJsonCodec().toJson(arguments))
                                        .build())
                        .build();
        assertEquals(
                TokenCounterUtil.calculateToken(List.of(raw)),
                TokenCounterUtil.calculateToken(List.of(structured)));
    }

    @Test
    void connectionOptionsDoNotContributeToPromptTokens() {
        List<Msg> messages = List.of(Msg.builder().textContent("hello").build());
        GenerateOptions options =
                GenerateOptions.builder()
                        .apiKey("dummy-key".repeat(1000))
                        .baseUrl("https://example.invalid")
                        .build();
        assertEquals(
                TokenCounterUtil.calculateToken(messages),
                TokenCounterUtil.calculateToken(messages, null, options));
    }

    private static int count(String text) {
        return TokenCounterUtil.calculateToken(List.of(Msg.builder().textContent(text).build()));
    }
}
