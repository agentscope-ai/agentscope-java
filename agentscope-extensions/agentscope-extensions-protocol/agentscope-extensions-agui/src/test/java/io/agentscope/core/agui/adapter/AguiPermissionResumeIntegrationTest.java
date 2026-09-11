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
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class AguiPermissionResumeIntegrationTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void confirmationResumeEmitsResultAcrossRuns(boolean approved, boolean legacyState) {
        AtomicInteger executions = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        "echo",
                        "echo",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("message", Map.of("type", "string"))),
                        false,
                        true,
                        false,
                        null,
                        false,
                        false) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        return Mono.just(PermissionDecision.ask("confirm echo"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executions.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("echoed"));
                    }
                });
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModelBase model =
                new ChatModelBase() {
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
                                                modelCalls.getAndIncrement() == 0
                                                        ? List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("tc1")
                                                                        .name("echo")
                                                                        .input(
                                                                                Map.of(
                                                                                        "message",
                                                                                        "hello"))
                                                                        .build())
                                                        : List.of(
                                                                TextBlock.builder()
                                                                        .text("done")
                                                                        .build()))
                                        .build());
                    }
                };
        try (ReActAgent agent =
                ReActAgent.builder().name("assistant").model(model).toolkit(toolkit).build()) {
            AguiAgentAdapter adapter =
                    new AguiAgentAdapter(agent, AguiAdapterConfig.defaultConfig());
            List<AguiEvent> paused =
                    adapter.run(
                                    RunAgentInput.builder()
                                            .threadId("permission-thread")
                                            .runId("first-run")
                                            .messages(
                                                    List.of(
                                                            AguiMessage.userMessage(
                                                                    "msg-1", "echo hello")))
                                            .build())
                            .collectList()
                            .block();
            assertNotNull(paused);
            AguiEvent.RunFinished finished =
                    paused.stream()
                            .filter(AguiEvent.RunFinished.class::isInstance)
                            .map(AguiEvent.RunFinished.class::cast)
                            .findFirst()
                            .orElseThrow();
            AguiEvent.Interrupt interrupt =
                    assertInstanceOf(
                                    AguiEvent.RunFinishedInterruptOutcome.class, finished.outcome())
                            .interrupts()
                            .get(0);
            if (legacyState) {
                List<Msg> context = agent.getAgentState(null, "permission-thread").contextMutable();
                for (int i = 0; i < context.size(); i++) {
                    Msg msg = context.get(i);
                    Map<String, Object> metadata = new HashMap<>(msg.getMetadata());
                    metadata.remove(Msg.METADATA_CONFIRM_REQUEST_REPLY_ID);
                    context.set(i, msg.withMetadata(metadata));
                }
            }
            List<AguiEvent> resumed =
                    adapter.run(
                                    RunAgentInput.builder()
                                            .threadId("permission-thread")
                                            .runId("second-run")
                                            .messages(List.of())
                                            .resume(
                                                    List.of(
                                                            new AguiResume(
                                                                    interrupt.id(),
                                                                    AguiResume.STATUS_RESOLVED,
                                                                    Map.of("approved", approved))))
                                            .build(),
                                    RuntimeContext.builder()
                                            .put(
                                                    AguiAgentAdapter
                                                            .RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY,
                                                    Map.of(interrupt.id(), interrupt))
                                            .build())
                            .collectList()
                            .block();
            assertNotNull(resumed);
            assertFalse(resumed.stream().anyMatch(AguiEvent.RunError.class::isInstance));
            List<AguiEvent.ToolCallResult> results =
                    resumed.stream()
                            .filter(AguiEvent.ToolCallResult.class::isInstance)
                            .map(AguiEvent.ToolCallResult.class::cast)
                            .toList();
            assertEquals(1, results.size(), "the resumed run must deliver the tool outcome");
            assertEquals("tc1", results.get(0).toolCallId());
            assertEquals(
                    approved ? "echoed" : "Permission denied by user", results.get(0).content());
            assertEquals("second-run", results.get(0).runId());
            assertFalse(
                    resumed.stream().anyMatch(AguiEvent.ToolCallStart.class::isInstance),
                    "resuming must not replay the original tool call lifecycle");
            assertFalse(resumed.stream().anyMatch(AguiEvent.ToolCallEnd.class::isInstance));
            assertEquals(approved ? 1 : 0, executions.get());
        }
    }
}
