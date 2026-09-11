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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AgentResolver;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Exercises two real agent runs, with deterministic model and tool implementations. */
class AguiPermissionResumeTest {

    @TempDir Path workspace;

    @ParameterizedTest(name = "harness={0}, officialResume={1}")
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void approvedToolResultSurvivesNewStreamContext(boolean harness, boolean officialResume) {
        ScriptedModel model = new ScriptedModel();
        AskingTool tool = new AskingTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        Agent agent =
                harness
                        ? HarnessAgent.builder()
                                .name("resume-test")
                                .model(model)
                                .toolkit(toolkit)
                                .workspace(workspace)
                                .stateStore(store)
                                .permissionContext(PermissionContextState.builder().build())
                                .build()
                        : ReActAgent.builder()
                                .name("resume-test")
                                .model(model)
                                .toolkit(toolkit)
                                .stateStore(store)
                                .build();
        try {
            AguiRequestProcessor processor =
                    AguiRequestProcessor.builder()
                            .agentResolver(
                                    new AgentResolver() {
                                        @Override
                                        public Agent resolveAgent(String agentId, String threadId) {
                                            return agent;
                                        }

                                        @Override
                                        public boolean hasMemory(RuntimeContext context) {
                                            return true;
                                        }
                                    })
                            .build();
            List<AguiEvent> first =
                    run(
                            processor,
                            input("first")
                                    .messages(
                                            List.of(
                                                    AguiMessage.userMessage(
                                                            "user-1", "Run the tool")))
                                    .build());
            assertNoError(first);
            AguiEvent.RunFinished paused =
                    assertInstanceOf(AguiEvent.RunFinished.class, first.get(first.size() - 1));
            AguiEvent.RunFinishedInterruptOutcome outcome =
                    assertInstanceOf(AguiEvent.RunFinishedInterruptOutcome.class, paused.outcome());
            assertEquals(1, outcome.interrupts().size());
            AguiEvent.Interrupt interrupt = outcome.interrupts().get(0);
            assertEquals("tc1", interrupt.toolCallId());
            assertEquals(0, tool.calls.get(), "tool must wait for approval");
            assertEquals(
                    1, first.stream().filter(AguiEvent.ToolCallStart.class::isInstance).count());
            assertEquals(1, first.stream().filter(AguiEvent.ToolCallEnd.class::isInstance).count());
            assertTrue(first.stream().noneMatch(AguiEvent.ToolCallResult.class::isInstance));

            List<AguiEvent> resumed;
            if (officialResume) {
                resumed =
                        run(
                                processor,
                                input("second")
                                        .resume(
                                                List.of(
                                                        new AguiResume(
                                                                interrupt.id(),
                                                                AguiResume.STATUS_RESOLVED,
                                                                Map.of("approved", true))))
                                        .build());
            } else {
                // Applications can also submit ConfirmResult directly to the event-stream API.
                Msg confirmation =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("approved")
                                .metadata(
                                        Map.of(
                                                Msg.METADATA_CONFIRM_RESULTS,
                                                List.of(new ConfirmResult(true, toolCall()))))
                                .build();
                RuntimeContext runtime =
                        RuntimeContext.builder().sessionId("resume-thread").build();
                Flux<AgentEvent> source =
                        harness
                                ? ((HarnessAgent) agent)
                                        .streamEvents(List.of(confirmation), runtime)
                                : ((ReActAgent) agent).streamEvents(List.of(confirmation), runtime);
                AgentEventConverterRegistry registry = new AgentEventConverterRegistry();
                AguiStreamContext context =
                        new AguiStreamContext(
                                "resume-thread", "second", AguiAdapterConfig.defaultConfig());
                resumed =
                        source.concatMapIterable(event -> registry.convert(event, context))
                                .concatWith(
                                        Flux.defer(
                                                () ->
                                                        Flux.fromIterable(
                                                                context.finishPendingEvents())))
                                .collectList()
                                .block(Duration.ofSeconds(20));
            }
            assertNoError(resumed);
            List<AguiEvent.ToolCallResult> results =
                    resumed.stream()
                            .filter(AguiEvent.ToolCallResult.class::isInstance)
                            .map(AguiEvent.ToolCallResult.class::cast)
                            .toList();
            assertEquals(1, results.size(), "resumed run must deliver the original tool result");
            assertEquals("tc1", results.get(0).toolCallId());
            assertEquals("executed:ping", results.get(0).content());
            assertEquals("second", results.get(0).runId());
            assertTrue(
                    resumed.stream()
                            .noneMatch(
                                    event ->
                                            event instanceof AguiEvent.ToolCallStart
                                                    || event instanceof AguiEvent.ToolCallArgs
                                                    || event instanceof AguiEvent.ToolCallEnd));
            assertInstanceOf(AguiEvent.RunFinished.class, resumed.get(resumed.size() - 1));
            assertEquals(1, tool.calls.get());
        } finally {
            if (agent instanceof HarnessAgent harnessAgent) {
                harnessAgent.close();
            } else {
                ((ReActAgent) agent).close();
            }
        }
    }

    private static RunAgentInput.Builder input(String runId) {
        return RunAgentInput.builder().threadId("resume-thread").runId(runId).messages(List.of());
    }

    private static List<AguiEvent> run(AguiRequestProcessor processor, RunAgentInput input) {
        return processor
                .process(AguiRuntimeContextRequest.builder().input(input).build())
                .events()
                .collectList()
                .block(Duration.ofSeconds(20));
    }

    private static void assertNoError(List<AguiEvent> events) {
        assertNotNull(events);
        assertTrue(
                events.stream().noneMatch(AguiEvent.RunError.class::isInstance), events.toString());
    }

    private static ToolUseBlock toolCall() {
        return ToolUseBlock.builder()
                .id("tc1")
                .name("approval_test_tool")
                .input(Map.of("query", "ping"))
                .content("{\"query\":\"ping\"}")
                .build();
    }

    private static final class ScriptedModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(
                                    calls.getAndIncrement() == 0
                                            ? List.of(toolCall())
                                            : List.of(TextBlock.builder().text("done").build()))
                            .build());
        }
    }

    private static final class AskingTool extends ToolBase {
        private final AtomicInteger calls = new AtomicInteger();

        AskingTool() {
            super(
                    "approval_test_tool",
                    "requires approval",
                    Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of("query", Map.of("type", "string"))),
                    false,
                    true,
                    false,
                    null,
                    false,
                    false);
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> input, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("approve this tool"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            calls.incrementAndGet();
            return Mono.just(ToolResultBlock.text("executed:" + param.getInput().get("query")));
        }
    }
}
