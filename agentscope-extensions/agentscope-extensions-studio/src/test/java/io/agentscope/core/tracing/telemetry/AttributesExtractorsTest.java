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
package io.agentscope.core.tracing.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AttributesExtractors Tests")
class AttributesExtractorsTest {

    @Test
    @DisplayName("LLM response attributes include detailed token usage")
    void llmResponseAttributesIncludeDetailedTokenUsage() {
        ChatUsage usage =
                ChatUsage.builder()
                        .inputTokens(100)
                        .outputTokens(50)
                        .cachedTokens(30)
                        .cacheCreationTokens(10)
                        .reasoningTokens(20)
                        .toolUsePromptTokens(15)
                        .build();
        ChatResponse response =
                ChatResponse.builder()
                        .id("response-1")
                        .content(List.of(TextBlock.builder().text("answer").build()))
                        .usage(usage)
                        .finishReason("stop")
                        .build();

        Attributes attributes = AttributesExtractors.getLLMResponseAttributes(response);

        assertEquals(100L, attributes.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(50L, attributes.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(
                30L, attributes.get(AttributeKey.longKey("gen_ai.usage.cache_read.input_tokens")));
        assertEquals(
                10L,
                attributes.get(AttributeKey.longKey("gen_ai.usage.cache_creation.input_tokens")));
        assertEquals(
                20L, attributes.get(AttributeKey.longKey("gen_ai.usage.reasoning.output_tokens")));
        assertEquals(
                15L,
                attributes.get(AttributeKey.longKey("agentscope.usage.tool_use_prompt_tokens")));
    }

    @Test
    @DisplayName("LLM response without usage does not fail")
    void llmResponseWithoutUsageDoesNotFail() {
        ChatResponse response =
                ChatResponse.builder()
                        .id("response-no-usage")
                        .content(List.of(TextBlock.builder().text("answer").build()))
                        .finishReason("stop")
                        .build();

        Attributes attributes = AttributesExtractors.getLLMResponseAttributes(response);

        assertNull(attributes.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertNull(attributes.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    }
}
