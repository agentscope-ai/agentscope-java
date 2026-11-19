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
package io.agentscope.core.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link FinalAnswerInput} record and {@link FinishReason} enum.
 *
 * @author shanhongyu
 */
@DisplayName("FinalAnswerInput / FinishReason")
class FinalAnswerInputTest {

    @Test
    @DisplayName("components are exposed as given")
    void exposesComponents() {
        Msg candidate =
                UserMessage.builder()
                        .name("user")
                        .content(List.of(TextBlock.builder().text("draft").build()))
                        .build();
        ChatUsage usage = new ChatUsage(11, 22, 3.0);
        FinalAnswerInput input =
                new FinalAnswerInput(candidate, 2, usage, FinishReason.FINAL_ANSWER);

        assertEquals(candidate, input.candidate());
        assertEquals(2, input.retryCount());
        assertEquals(usage, input.cumulativeUsage());
        assertEquals(FinishReason.FINAL_ANSWER, input.finishReason());
    }

    @Test
    @DisplayName("cumulativeUsage may be null when the provider reports no usage")
    void nullUsageIsLegal() {
        FinalAnswerInput input = new FinalAnswerInput(null, 0, null, FinishReason.RETURN_DIRECT);
        assertNull(input.cumulativeUsage());
        assertNull(input.candidate());
    }

    @Test
    @DisplayName("FinishReason covers exactly the three final-answer exits")
    void finishReasonCoversThreeExits() {
        assertEquals(
                List.of(
                        FinishReason.FINAL_ANSWER,
                        FinishReason.MAX_ITERATIONS,
                        FinishReason.RETURN_DIRECT),
                List.of(FinishReason.values()));
    }
}
