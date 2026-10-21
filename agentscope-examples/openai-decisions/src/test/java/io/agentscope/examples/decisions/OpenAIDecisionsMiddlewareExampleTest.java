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
package io.agentscope.examples.decisions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.judge.openaidecisions.DecisionAnswer;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Runs the example wiring through the real ReAct loop without credentials or network access. */
class OpenAIDecisionsMiddlewareExampleTest {
    @Test
    void routesToFastModel() {
        var original = new FixtureModel(null);
        var fast = new FixtureModel(null);
        var answer =
                new DecisionAnswer.Choice(
                        "fast",
                        0.95,
                        List.of(
                                new DecisionAnswer.ChoiceProbability("fast", 0.95),
                                new DecisionAnswer.ChoiceProbability("capable", 0.05)));
        var agent =
                OpenAIDecisionsMiddlewareExample.createAgent(
                        "route",
                        original,
                        fast,
                        r -> Mono.just(result(Map.of("models_0", answer))));
        agent.call("Explain ReAct", RuntimeContext.empty()).block(Duration.ofSeconds(10));
        assertEquals(0, original.calls);
        assertEquals(1, fast.calls);
    }

    @Test
    void filtersActualModelSchemasToWeather() {
        var model = new FixtureModel("weather");
        var agent =
                OpenAIDecisionsMiddlewareExample.createAgent(
                        "select",
                        model,
                        model,
                        request -> {
                            Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
                            request.questions()
                                    .forEach(
                                            (name, question) ->
                                                    answers.put(
                                                            name,
                                                            new DecisionAnswer.Predicate(
                                                                    question.toString()
                                                                                    .contains(
                                                                                            "weather")
                                                                            ? 0.95
                                                                            : 0.05)));
                            return Mono.just(result(answers));
                        });
        var events =
                agent.streamEvents(List.of(new UserMessage("Weather in Beijing")))
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertEquals(List.of("weather"), model.firstTools);
        assertEquals(2, model.calls);
        assertTrue(
                events.stream()
                        .filter(ToolResultTextDeltaEvent.class::isInstance)
                        .map(ToolResultTextDeltaEvent.class::cast)
                        .anyMatch(e -> e.getDelta().contains("[simulated] Sunny in Beijing")));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.05, 0.95})
    void guardProducesObservableDeniedOrSimulatedResult(double probability) {
        var model = new FixtureModel("delete_file");
        var agent =
                OpenAIDecisionsMiddlewareExample.createAgent(
                        "guard",
                        model,
                        model,
                        r ->
                                Mono.just(
                                        result(
                                                Map.of(
                                                        "tool_0",
                                                        new DecisionAnswer.Predicate(
                                                                probability)))));
        var events =
                agent.streamEvents(List.of(new UserMessage("Delete a backup")))
                        .collectList()
                        .block(Duration.ofSeconds(10));
        var ends = events.stream().filter(ToolResultEndEvent.class::isInstance).toList();
        assertEquals(1, ends.size());
        boolean denied = probability < 0.8;
        var text =
                events.stream()
                        .filter(ToolResultTextDeltaEvent.class::isInstance)
                        .map(ToolResultTextDeltaEvent.class::cast)
                        .map(ToolResultTextDeltaEvent::getDelta)
                        .toList();
        assertEquals(
                denied ? ToolResultState.DENIED : ToolResultState.SUCCESS,
                ((ToolResultEndEvent) ends.get(0)).getState(),
                text.toString());
        assertTrue(
                events.stream()
                        .filter(ToolResultTextDeltaEvent.class::isInstance)
                        .map(ToolResultTextDeltaEvent.class::cast)
                        .anyMatch(e -> e.getDelta().contains(denied ? "Blocked:" : "[simulated]")),
                text.toString());
        assertEquals(2, model.calls);
    }

    @Test
    void invalidScenarioFailsBeforeAnyProviderAccess() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OpenAIDecisionsMiddlewareExample.main(new String[] {"unknown"}));
    }

    private static DecisionResult result(Map<String, DecisionAnswer> answers) {
        return new DecisionResult("fixture", answers, null);
    }

    private static final class FixtureModel extends ChatModelBase {
        private final String tool;
        private int calls;
        private List<String> firstTools;

        private FixtureModel(String tool) {
            this.tool = tool;
        }

        @Override
        public String getModelName() {
            return "fixture";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (calls++ == 0) {
                firstTools = tools.stream().map(ToolSchema::getName).toList();
                if (tool != null) {
                    Map<String, Object> input =
                            "weather".equals(tool)
                                    ? Map.of("city", "Beijing")
                                    : Map.of("path", "/backups/production");
                    return Flux.just(
                            ChatResponse.builder()
                                    .content(
                                            List.of(
                                                    new ToolUseBlock(
                                                            "call-1",
                                                            tool,
                                                            input,
                                                            JsonUtils.getJsonCodec().toJson(input),
                                                            null)))
                                    .build());
                }
            }
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("Done").build()))
                            .build());
        }
    }
}
