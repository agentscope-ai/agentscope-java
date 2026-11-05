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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Verifies final feedback at the next model request, rather than only event setter fields. */
@SuppressWarnings("deprecation")
class ReActAgentPostActingFeedbackTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacedBlockReachesNextModelRequestAndHistory(boolean streaming) {
        MockModel model = toolThenDoneModel();
        ReActAgent agent =
                builder(model)
                        .hook(
                                hook(
                                        event ->
                                                event.setToolResult(
                                                        ToolResultBlock.error("corrective feedback")
                                                                .withIdAndName(
                                                                        "other-id", "other-name"))))
                        .build();

        Msg result = invoke(agent, streaming);

        assertNotNull(result);
        assertEquals(2, model.getRecordedMessages().size());
        assertFeedback(
                toolResult(model.getRecordedMessages().get(1)),
                "Error: corrective feedback",
                ToolResultState.ERROR);
        assertFeedback(
                toolResult(agent.getAgentState().getContext()),
                "Error: corrective feedback",
                ToolResultState.ERROR);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitlyReplacedMessageTakesPrecedence(boolean streaming) {
        MockModel model = toolThenDoneModel();
        ReActAgent agent =
                builder(model)
                        .hook(
                                hook(
                                        event -> {
                                            event.setToolResultMsg(
                                                    event.getToolResultMsg()
                                                            .withContent(
                                                                    List.of(
                                                                            ToolResultBlock.success(
                                                                                            "explicit"
                                                                                                + " message")
                                                                                    .withIdAndName(
                                                                                            "tc1",
                                                                                            "lookup")))
                                                            .withMetadata(
                                                                    Map.of(
                                                                            "feedback-source",
                                                                            "explicit")));
                                            event.setToolResult(
                                                    ToolResultBlock.success("block replacement"));
                                        }))
                        .build();

        invoke(agent, streaming);

        assertEquals(2, model.getRecordedMessages().size());
        assertFeedback(
                toolResult(model.getRecordedMessages().get(1)),
                "explicit message",
                ToolResultState.SUCCESS);
        Msg resultMessage =
                model.getRecordedMessages().get(1).stream()
                        .filter(
                                message ->
                                        !message.getContentBlocks(ToolResultBlock.class).isEmpty())
                        .findFirst()
                        .orElseThrow();
        assertEquals("explicit", resultMessage.getMetadata().get("feedback-source"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopReturnsAndPersistsReplacementWithoutAnotherModelCall(boolean streaming) {
        MockModel model = toolThenDoneModel();
        ReActAgent agent =
                builder(model)
                        .hook(
                                hook(
                                        event -> {
                                            event.setToolResult(
                                                    ToolResultBlock.success("review me"));
                                            event.stopAgent();
                                        }))
                        .build();

        Msg result = invoke(agent, streaming);

        assertNotNull(result);
        assertEquals(GenerateReason.ACTING_STOP_REQUESTED, result.getGenerateReason());
        assertEquals(1, model.getRecordedMessages().size());
        assertFeedback(toolResult(List.of(result)), "review me", ToolResultState.SUCCESS);
        assertFeedback(
                toolResult(agent.getAgentState().getContext()),
                "review me",
                ToolResultState.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelCallFeedbackCanBeProjectedWithoutChangingHistory(boolean streaming) {
        MockModel model = toolThenDoneModel();
        MiddlewareBase feedback =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onModelCall(
                            Agent agent,
                            RuntimeContext context,
                            ModelCallInput input,
                            Function<ModelCallInput, Flux<AgentEvent>> next) {
                        List<Msg> projected =
                                input.messages().stream()
                                        .map(
                                                message -> {
                                                    if (message.getContentBlocks(
                                                                    ToolResultBlock.class)
                                                            .isEmpty()) {
                                                        return message;
                                                    }
                                                    ToolResultBlock original =
                                                            message.getContentBlocks(
                                                                            ToolResultBlock.class)
                                                                    .get(0);
                                                    return message.withContent(
                                                            List.of(
                                                                    ToolResultBlock.success(
                                                                                    "request"
                                                                                        + " feedback")
                                                                            .withIdAndName(
                                                                                    original
                                                                                            .getId(),
                                                                                    original
                                                                                            .getName())));
                                                })
                                        .toList();
                        return next.apply(
                                new ModelCallInput(
                                        projected, input.tools(), input.options(), input.model()));
                    }
                };
        ReActAgent agent = builder(model).middleware(feedback).build();

        invoke(agent, streaming);

        assertEquals(2, model.getRecordedMessages().size());
        assertFeedback(
                toolResult(model.getRecordedMessages().get(1)),
                "request feedback",
                ToolResultState.SUCCESS);
        assertFeedback(
                toolResult(agent.getAgentState().getContext()),
                "raw output",
                ToolResultState.SUCCESS);
        assertEquals(
                model.getRecordedMessages().get(0).get(0).getTextContent(),
                model.getRecordedMessages().get(1).get(0).getTextContent());
        assertFalse(
                model.getRecordedMessages().get(0).stream()
                        .anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)));
    }

    private static Hook hook(Consumer<PostActingEvent> handler) {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PostActingEvent postActing) {
                    handler.accept(postActing);
                }
                return Mono.just(event);
            }
        };
    }

    private static ReActAgent.Builder builder(MockModel model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("lookup")
                                .description("Look up a value")
                                .inputSchema(Map.of("type", "object"))
                                .readOnly(true)) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(PermissionDecision.allow("test"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        return Mono.just(ToolResultBlock.success("raw output"));
                    }
                });
        return ReActAgent.builder()
                .name("assistant")
                .sysPrompt("base system prompt")
                .model(model)
                .toolkit(toolkit);
    }

    private static Msg invoke(ReActAgent agent, boolean streaming) {
        if (!streaming) {
            return agent.call(List.of()).block(Duration.ofSeconds(10));
        }
        return agent.streamEvents(List.of())
                .ofType(AgentResultEvent.class)
                .map(AgentResultEvent::getResult)
                .single()
                .block(Duration.ofSeconds(10));
    }

    private static ToolResultBlock toolResult(List<Msg> messages) {
        return messages.stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .findFirst()
                .orElseThrow();
    }

    private static void assertFeedback(ToolResultBlock result, String text, ToolResultState state) {
        assertEquals("tc1", result.getId());
        assertEquals("lookup", result.getName());
        assertEquals(state, result.getState());
        assertEquals(1, result.getOutput().size());
        assertEquals(text, ((TextBlock) result.getOutput().get(0)).getText());
    }

    private static MockModel toolThenDoneModel() {
        return new MockModel(
                messages -> {
                    boolean hasToolResult =
                            messages.stream()
                                    .anyMatch(
                                            message ->
                                                    !message.getContentBlocks(ToolResultBlock.class)
                                                            .isEmpty());
                    if (hasToolResult) {
                        return List.of(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build());
                    }
                    return List.of(
                            ChatResponse.builder()
                                    .content(
                                            List.of(
                                                    ToolUseBlock.builder()
                                                            .id("tc1")
                                                            .name("lookup")
                                                            .input(Map.of())
                                                            .content("{}")
                                                            .build()))
                                    .build());
                });
    }
}
