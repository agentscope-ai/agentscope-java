/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Exercises the processor, converter, resume coordinator and real ReAct loop together. */
class AguiToolResultReplayTest {
    private static final RuntimeContext CONTEXT =
            RuntimeContext.builder().userId("alice").sessionId("thread-1").build();

    private static final class Model extends ChatModelBase {
        private final boolean suspendFirst;
        private final List<List<Msg>> inputs = new ArrayList<>();

        Model(boolean suspendFirst) {
            this.suspendFirst = suspendFirst;
        }

        @Override
        public String getModelName() {
            return "agui-replay-test";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    suspendFirst && inputs.size() == 1
                                            ? calls("pending").getContent()
                                            : List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }

    private static ReActAgent agent(Model model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerSchema(
                ToolSchema.builder()
                        .name("external")
                        .description("Execute externally")
                        .parameters(Map.of("type", "object", "properties", Map.of()))
                        .build());
        return ReActAgent.builder()
                .name("asst")
                .model(model)
                .toolkit(toolkit)
                .enablePendingToolRecovery(true)
                .build();
    }

    private static Msg calls(String id) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(ToolUseBlock.builder().id(id).name("external").input(Map.of()).build())
                .build();
    }

    private static Msg result(String id) {
        return new AguiMessageConverter().toMsg(AguiMessage.toolMessage("result-" + id, id, "ok"));
    }

    private static void seed(ReActAgent agent, boolean pending) {
        agent.getAgentState(CONTEXT).contextMutable().addAll(List.of(calls("old"), result("old")));
        if (pending) {
            agent.getAgentState(CONTEXT).contextMutable().add(calls("pending"));
        }
    }

    private static AguiRequestProcessor processor(ReActAgent agent) {
        return AguiRequestProcessor.builder()
                .agentResolver(
                        new AgentResolver() {
                            @Override
                            public Agent resolveAgent(String agentId, String threadId) {
                                return agent;
                            }

                            @Override
                            public boolean hasMemory(RuntimeContext ctx) {
                                return !agent.getAgentState(ctx).getContext().isEmpty();
                            }
                        })
                .runtimeContextResolver(request -> CONTEXT)
                .build();
    }

    private static RunAgentInput input(String run, List<AguiMessage> messages) {
        return RunAgentInput.builder().threadId("thread-1").runId(run).messages(messages).build();
    }

    private static List<AguiEvent> run(AguiRequestProcessor processor, RunAgentInput input) {
        return processor
                .process(AguiRuntimeContextRequest.builder().input(input).build())
                .events()
                .collectList()
                .block();
    }

    private static void assertSuccess(List<AguiEvent> events) {
        assertFalse(
                events.stream().anyMatch(AguiEvent.RunError.class::isInstance), events.toString());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(events.size() - 1));
    }

    private static List<String> resultIds(ReActAgent agent) {
        return agent.getAgentState(CONTEXT).getContext().stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .map(ToolResultBlock::getId)
                .toList();
    }

    @Test
    void recoversMidTranscriptPendingResultAndDropsConsumedTrailingResult() {
        Model model = new Model(false);
        try (ReActAgent agent = agent(model)) {
            seed(agent, true);
            List<AguiMessage> transcript =
                    List.of(
                            AguiMessage.systemMessage("system", "historical client system"),
                            AguiMessage.userMessage("old-user", "old prompt"),
                            AguiMessage.assistantMessage("assistant-one", "first local round"),
                            AguiMessage.toolMessage("pending-result", "pending", "valid result"),
                            AguiMessage.toolMessage("compacted-result", "compacted", "old history"),
                            AguiMessage.assistantMessage("assistant-two", "split local round"),
                            AguiMessage.toolMessage("old-result", "old", "replayed result"),
                            AguiMessage.userMessage("fresh", "continue"));
            assertSuccess(run(processor(agent), input("run-1", transcript)));
            assertEquals(List.of("old", "pending"), resultIds(agent));
            List<Msg> seen = model.inputs.get(0);
            assertTrue(seen.stream().anyMatch(m -> "continue".equals(m.getTextContent())));
            assertFalse(
                    seen.stream()
                            .anyMatch(
                                    m ->
                                            "old prompt".equals(m.getTextContent())
                                                    || "historical client system"
                                                            .equals(m.getTextContent())));
            ToolResultBlock pending =
                    seen.stream()
                            .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                            .filter(r -> "pending".equals(r.getId()))
                            .findFirst()
                            .orElseThrow();
            assertEquals("valid result", ((TextBlock) pending.getOutput().get(0)).getText());
        }
    }

    @Test
    void realSuspensionResumesWithMidTranscriptResultAndOfficialResumeWithoutDuplicates() {
        Model model = new Model(true);
        try (ReActAgent agent = agent(model)) {
            AguiRequestProcessor processor = processor(agent);
            List<AguiEvent> first =
                    run(
                            processor,
                            input("run-1", List.of(AguiMessage.userMessage("user", "start"))));
            assertSuccess(first);
            AguiEvent.RunFinished finished = (AguiEvent.RunFinished) first.get(first.size() - 1);
            AguiEvent.RunFinishedInterruptOutcome outcome =
                    assertInstanceOf(
                            AguiEvent.RunFinishedInterruptOutcome.class, finished.outcome());
            String interruptId = outcome.interrupts().get(0).id();
            RunAgentInput resume =
                    RunAgentInput.builder()
                            .threadId("thread-1")
                            .runId("run-2")
                            .messages(
                                    List.of(
                                            AguiMessage.userMessage("user", "start"),
                                            AguiMessage.assistantMessage("first", "tool call"),
                                            AguiMessage.toolMessage(
                                                    "result", "pending", "browser result"),
                                            AguiMessage.assistantMessage(
                                                    "split", "local continuation")))
                            .resume(
                                    List.of(
                                            new AguiResume(
                                                    interruptId,
                                                    AguiResume.STATUS_RESOLVED,
                                                    "browser result")))
                            .build();
            assertSuccess(run(processor, resume));
            assertEquals(List.of("pending"), resultIds(agent));
            assertEquals(2, model.inputs.size());
            assertEquals(
                    1,
                    agent.getAgentState(CONTEXT).getContext().stream()
                            .filter(m -> m.getRole() == MsgRole.USER)
                            .count());
        }
    }

    @Test
    void repeatedStaleResultWithNewUserMessagesDoesNotWedgeCompletedSession() {
        Model model = new Model(false);
        try (ReActAgent agent = agent(model)) {
            seed(agent, false);
            AguiRequestProcessor processor = processor(agent);
            for (int i = 0; i < 2; i++) {
                assertSuccess(
                        run(
                                processor,
                                input(
                                        "run-" + i,
                                        List.of(
                                                AguiMessage.assistantMessage("asst", "completed"),
                                                AguiMessage.toolMessage("stale", "old", "replayed"),
                                                AguiMessage.userMessage(
                                                        "user-" + i, "next " + i)))));
            }
            assertEquals(List.of("old"), resultIds(agent));
            assertEquals(2, model.inputs.size());
        }
    }

    @Test
    void unknownTrailingResultFailsWithoutPoisoningTheNextCorrectedRequest() {
        Model model = new Model(false);
        try (ReActAgent agent = agent(model)) {
            seed(agent, true);
            AguiRequestProcessor processor = processor(agent);
            List<AguiEvent> rejected =
                    run(
                            processor,
                            input(
                                    "bad",
                                    List.of(
                                            AguiMessage.assistantMessage("asst", "pending"),
                                            AguiMessage.toolMessage("unknown", "unknown", "bad"),
                                            AguiMessage.userMessage("user", "continue"))));
            assertTrue(rejected.stream().anyMatch(AguiEvent.RunError.class::isInstance));
            assertEquals(List.of("old"), resultIds(agent));
            assertSuccess(
                    run(
                            processor,
                            input(
                                    "good",
                                    List.of(
                                            AguiMessage.assistantMessage("asst", "pending"),
                                            AguiMessage.toolMessage("valid", "pending", "good"),
                                            AguiMessage.userMessage("user", "continue")))));
            assertEquals(List.of("old", "pending"), resultIds(agent));
        }
    }
}
