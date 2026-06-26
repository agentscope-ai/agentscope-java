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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * End-to-end: real SubAgentTool with real ReActAgent sub-agent.
 *
 * <p>The slow sub-agent uses MockModel that returns a tool call to "slow_tool",
 * which sleeps 4 seconds then writes one line to a temp file.
 *
 * <p>SubAgentTool applies {@code .timeout(3s).retryWhen(1)}. After timeout,
 * {@code doFinally(CANCEL)} calls {@code interrupt(ctx)} → sets internal flag.
 * When slow_tool finishes at ~4s, the agent loop hits {@code checkInterrupted()}
 * and throws {@code InterruptedException}, stopping the agent before it can
 * make another LLM call or tool call.
 *
 * <p>The test verifies:
 * <ol>
 *   <li>interrupt() was called on the slow agent (not the fast one)</li>
 *   <li>slow_tool wrote exactly 1 line to file</li>
 *   <li>After waiting, no more lines appear (loop is dead)</li>
 *   <li>MockModel.getCallCount() == 1: interrupt prevented the second LLM call</li>
 * </ol>
 */
@DisplayName("SubAgentTool timeout+retry: interrupt stops agent loop")
class SubAgentToolTimeoutRetryIntegrationTest {

    private Path tmpFile;

    @AfterEach
    void cleanup() throws Exception {
        if (tmpFile != null) Files.deleteIfExists(tmpFile);
    }

    @Test
    @DisplayName("Agent loop stops after interrupt — no further LLM or tool calls")
    @Timeout(30)
    void oldAgentIsStopped() throws Exception {
        tmpFile = Files.createTempFile("agent_bg_", ".log");

        // ---- slow_tool: sleeps 4s, then writes one line to file ----
        AgentTool slowTool =
                new AgentTool() {
                    @Override
                    public String getName() {
                        return "slow_tool";
                    }

                    @Override
                    public String getDescription() {
                        return "Sleeps 4s then writes a line to file.";
                    }

                    @Override
                    public Map<String, Object> getParameters() {
                        return Map.of("type", "object", "properties", Map.of());
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                        return Mono.fromRunnable(
                                        () -> {
                                            try {
                                                Thread.sleep(4_000);
                                                Files.writeString(
                                                        tmpFile, "slow_tool round done\n");
                                            } catch (Exception ignored) {
                                            }
                                        })
                                .subscribeOn(Schedulers.boundedElastic())
                                .then(
                                        Mono.just(
                                                ToolResultBlock.of(
                                                        TextBlock.builder().text("ok").build())));
                    }
                };

        // ---- slow agent: MockModel returns a tool call, then (if loop continues)
        //      more tool calls. With interrupt, only the first call happens. ----
        Toolkit slowTk = new Toolkit();
        slowTk.registerTool(slowTool);

        // The model returns a tool call every time it is invoked.
        // Without interrupt, the agent would call the model again after each tool
        // returns → infinite tool calls → file grows unboundedly.
        // With interrupt: checkInterrupted() fires → loop stops → model called only once.
        MockModel slowModel = MockModel.withToolCall("slow_tool", "call_1", Map.of());

        ReActAgent slowSpy =
                Mockito.spy(
                        ReActAgent.builder()
                                .name("slow_sub")
                                .sysPrompt("slow sub")
                                .model(slowModel)
                                .toolkit(slowTk)
                                .build());

        // ---- fast agent: returns text immediately ----
        ReActAgent fastSpy =
                Mockito.spy(
                        ReActAgent.builder()
                                .name("fast_sub")
                                .sysPrompt("fast sub")
                                .model(new MockModel("OK"))
                                .toolkit(new Toolkit())
                                .build());

        // ---- provider: 0=constructor, 1=slow (timeout), 2=fast (retry) ----
        int[] idx = {0};
        SubAgentProvider<ReActAgent> provider =
                () -> {
                    int i = idx[0]++;
                    if (i == 0) return fastSpy;
                    if (i == 1) return slowSpy;
                    return fastSpy;
                };

        SubAgentTool tool =
                new SubAgentTool(provider, SubAgentConfig.builder().forwardEvents(false).build());

        RuntimeContext rtCtx = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        ToolCallParam param =
                ToolCallParam.builder()
                        .toolUseBlock(
                                io.agentscope.core.message.ToolUseBlock.builder()
                                        .id("call_1")
                                        .name(tool.getName())
                                        .input(Map.of("message", "go"))
                                        .build())
                        .input(Map.of("message", "go"))
                        .runtimeContext(rtCtx)
                        .emitter(Mockito.mock(ToolEmitter.class))
                        .build();

        @SuppressWarnings({"unchecked", "rawtypes"})
        Mono<ToolResultBlock> mono =
                tool.callAsync(param)
                        .timeout(Duration.ofSeconds(3))
                        .retryWhen(
                                reactor.util.retry.Retry.backoff(1, Duration.ofMillis(100))
                                        .maxBackoff(Duration.ofSeconds(1)));

        ToolResultBlock result = mono.block(Duration.ofSeconds(15));
        assertNotNull(result, "Retry should have succeeded via the fast agent");

        // ---- proof #1: interrupt() was called on slow agent only ----
        verify(slowSpy, atLeastOnce()).interrupt(any(RuntimeContext.class));
        verify(fastSpy, never()).interrupt(any(RuntimeContext.class));

        // ---- proof #2: cancellation propagates to the inner tool execution ----
        // Since sink.onCancel(lifecycleDisposable) properly disposes the inner
        // subscription, slow_tool's Thread.sleep is interrupted before it can
        // write to the file. Wait briefly to confirm nothing was written.
        Thread.sleep(2_000);

        // ---- proof #3: slow_tool wrote 0 lines (properly cancelled) ----
        long fileLines = Files.readAllLines(tmpFile).size();
        assertEquals(
                0,
                fileLines,
                "Expected 0 tool invocations (cancelled before write), got %d"
                        .formatted(fileLines));

        // ---- proof #4: wait more → still no lines (loop is dead) ----
        Thread.sleep(2_000);
        long fileLinesAfter = Files.readAllLines(tmpFile).size();
        assertEquals(0, fileLinesAfter, "File should remain empty. Agent loop did not restart.");

        // ---- proof #5: MockModel was called exactly once ----
        // If interrupt didn't work, the agent loop would call reasoning() again
        // after slow_tool returned → model.stream() would be called a second time
        // → getCallCount() would be >= 2.
        assertEquals(
                1,
                slowModel.getCallCount(),
                "MockModel called %d times. Interrupt prevented the second LLM call."
                        .formatted(slowModel.getCallCount()));
    }
}
