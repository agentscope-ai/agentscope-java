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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.test.StepVerifier;

/**
 * End-to-end smoke test for MiniMax through the AgentScope model registry.
 *
 * <p>This test makes a real MiniMax API call and may incur costs. It is skipped unless
 * {@code MINIMAX_API_KEY} is set.
 */
@Tag("e2e")
@Tag("minimax")
@DisplayName("MiniMax ModelProvider E2E Tests (Real MiniMax API)")
@EnabledIfEnvironmentVariable(
        named = "MINIMAX_API_KEY",
        matches = ".+",
        disabledReason = "Requires MINIMAX_API_KEY environment variable")
class MiniMaxModelProviderE2ETest {

    private static final String DEFAULT_MODEL = "MiniMax-M2";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(45);

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    @DisplayName("Should resolve MiniMax model through SPI and complete a chat request")
    void shouldResolveMiniMaxModelThroughSpiAndCompleteChatRequest() {
        String modelName = firstNonBlank(System.getenv("MINIMAX_MODEL"), DEFAULT_MODEL);
        Model model =
                ModelRegistry.resolve(
                        "minimax:" + modelName,
                        ModelCreationContext.builder().stream(false).build());

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "Reply with one short sentence"
                                                                        + " about Java.")
                                                        .build()))
                                .build());
        GenerateOptions options =
                GenerateOptions.builder().temperature(0.1).maxCompletionTokens(512).build();

        StepVerifier.create(model.stream(messages, null, options))
                .assertNext(this::assertHasNonBlankText)
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private void assertHasNonBlankText(ChatResponse response) {
        assertNotNull(response);
        assertNotNull(response.getContent());
        if (response.getContent().stream().noneMatch(this::isNonBlankTextBlock)) {
            fail(
                    "Expected MiniMax response to contain a non-blank TextBlock, but got: "
                            + summarize(response));
        }
    }

    private boolean isNonBlankTextBlock(ContentBlock block) {
        return block instanceof TextBlock textBlock
                && textBlock.getText() != null
                && !textBlock.getText().isBlank();
    }

    private String summarize(ChatResponse response) {
        return "finishReason="
                + response.getFinishReason()
                + ", content="
                + response.getContent().stream().map(this::summarizeBlock).toList();
    }

    private String summarizeBlock(ContentBlock block) {
        if (block instanceof TextBlock textBlock) {
            return "TextBlock(length=" + textBlock.getText().length() + ")";
        }
        if (block instanceof ThinkingBlock thinkingBlock) {
            return "ThinkingBlock(length=" + thinkingBlock.getThinking().length() + ")";
        }
        return block.getClass().getSimpleName();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalArgumentException("At least one non-blank value is required");
    }
}
