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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;

class ReActAgentPerCallStructuredToolChoiceTest {

    public static class Output {
        public String value;
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void structuredRetryUsesSelectedModelsToolChoiceCapability(
            boolean defaultSupportsSpecific, boolean selectedSupportsSpecific) {
        MockModel defaultModel = new MockModel("unused default");
        defaultModel.setSupportsToolChoiceSpecific(defaultSupportsSpecific);
        RetryModel selected = new RetryModel(selectedSupportsSpecific);
        ReActAgent agent =
                ReActAgent.builder().name("structured-choice").model(defaultModel).build();
        RuntimeContext context = RuntimeContext.empty();
        context.put(Model.class, selected);
        Msg result =
                assertDoesNotThrow(
                        () ->
                                agent.call("Return a structured answer", Output.class, context)
                                        .block(Duration.ofSeconds(10)));

        assertNotNull(result);
        assertTrue(result.hasStructuredData(), "the selected model must complete the retry");
        Output output = result.getStructuredData(Output.class);
        assertNotNull(output, "the selected model must complete the structured retry");
        assertEquals("ok", output.value);
        assertEquals(2, selected.requests.size());
        assertEquals(
                selectedSupportsSpecific,
                selected.requests.get(1).getToolChoice() instanceof ToolChoice.Specific);
        assertEquals(0, defaultModel.getCallCount(), "the default must not serve this call");
    }

    /** Ignores the first instruction, then obeys supported forcing or the prompt reminder. */
    private static final class RetryModel extends ChatModelBase {
        private final boolean supportsSpecific;
        private final List<GenerateOptions> requests = new ArrayList<>();

        RetryModel(boolean supportsSpecific) {
            this.supportsSpecific = supportsSpecific;
        }

        @Override
        public String getModelName() {
            return "selected-retry-model";
        }

        @Override
        public boolean supportsToolChoiceSpecific() {
            return supportsSpecific;
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(options);
            boolean forced = options.getToolChoice() instanceof ToolChoice.Specific;
            if (forced && !supportsSpecific) {
                return Flux.error(new IllegalArgumentException("named tool choice is unsupported"));
            }
            if (requests.size() == 1 || (supportsSpecific && !forced)) {
                return Flux.just(
                        ChatResponse.builder()
                                .content(
                                        List.of(TextBlock.builder().text("A plain answer").build()))
                                .build());
            }
            assertTrue(
                    messages.stream()
                            .anyMatch(
                                    message ->
                                            message.getTextContent()
                                                    .contains(
                                                            "You MUST call the"
                                                                    + " `generate_response`")));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    List.of(
                                            ToolUseBlock.builder()
                                                    .id("structured-result")
                                                    .name("generate_response")
                                                    .input(
                                                            Map.of(
                                                                    "response",
                                                                    Map.of("value", "ok")))
                                                    .content("{\"response\":{\"value\":\"ok\"}}")
                                                    .build()))
                            .build());
        }
    }
}
