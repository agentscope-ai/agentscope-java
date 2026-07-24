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

package io.agentscope.extensions.model.openai.compat.minimax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MiniMaxFormatter}. */
@Tag("unit")
@DisplayName("MiniMaxFormatter Unit Tests")
class MiniMaxFormatterTest {

    private MiniMaxFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new MiniMaxFormatter();
    }

    @Test
    @DisplayName("Should add reasoning_split by default")
    void shouldAddReasoningSplitByDefault() {
        OpenAIRequest request =
                OpenAIRequest.builder().model("MiniMax-M2").messages(List.of()).build();

        formatter.applyOptions(request, GenerateOptions.builder().build(), null);

        assertNotNull(request.getExtraParams());
        assertEquals(true, request.getExtraParams().get("reasoning_split"));
    }

    @Test
    @DisplayName("Should allow reasoning_split override through additional body params")
    void shouldAllowReasoningSplitOverride() {
        OpenAIRequest request =
                OpenAIRequest.builder().model("MiniMax-M2").messages(List.of()).build();
        GenerateOptions options =
                GenerateOptions.builder().additionalBodyParam("reasoning_split", false).build();

        formatter.applyOptions(request, options, null);

        assertEquals(false, request.getExtraParams().get("reasoning_split"));
    }

    @Test
    @DisplayName("Should map maxTokens to max_completion_tokens")
    void shouldMapMaxTokensToMaxCompletionTokens() {
        OpenAIRequest request =
                OpenAIRequest.builder().model("MiniMax-M2").messages(List.of()).build();
        GenerateOptions options = GenerateOptions.builder().maxTokens(1024).build();

        formatter.applyOptions(request, options, null);

        assertNull(request.getMaxTokens());
        assertEquals(1024, request.getMaxCompletionTokens());
    }

    @Test
    @DisplayName("Should prefer maxCompletionTokens over maxTokens")
    void shouldPreferMaxCompletionTokensOverMaxTokens() {
        OpenAIRequest request =
                OpenAIRequest.builder().model("MiniMax-M2").messages(List.of()).build();
        GenerateOptions options =
                GenerateOptions.builder().maxTokens(1024).maxCompletionTokens(2048).build();

        formatter.applyOptions(request, options, null);

        assertNull(request.getMaxTokens());
        assertEquals(2048, request.getMaxCompletionTokens());
    }

    @Test
    @DisplayName("Should not include strict parameter in tool definitions")
    void shouldNotIncludeStrictParameterInToolDefinitions() {
        OpenAIRequest request =
                OpenAIRequest.builder().model("MiniMax-M2").messages(List.of()).build();
        ToolSchema tool =
                ToolSchema.builder()
                        .name("test_tool")
                        .description("Test tool")
                        .strict(true)
                        .build();

        formatter.applyTools(request, List.of(tool));

        assertNotNull(request.getTools());
        assertEquals(1, request.getTools().size());
        assertNull(request.getTools().get(0).getFunction().getStrict());
    }
}
