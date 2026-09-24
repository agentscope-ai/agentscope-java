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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Tests for {@link HarnessAgent} interrupt targeting: the {@code interrupt(RuntimeContext[, Msg])}
 * overloads must cancel the in-flight stream running under the custom {@code sessionId} carried by
 * that context, and the context-free {@code interrupt()} / {@code interrupt(Msg)} overloads must
 * prefer the active call's runtime context when one exists. See issue #2610.
 */
class HarnessAgentInterruptTest {

    @TempDir Path workspace;

    /**
     * Model whose first call blocks until released, so the test thread can fire an interrupt
     * while the agent's stream is in flight.
     */
    private static final class BlockingModel extends ChatModelBase {
        private final CountDownLatch called = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String getModelName() {
            return "blocking-model";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            called.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test interrupt never released the model");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for release", e);
            }
            return Flux.just(textResponse("done"));
        }

        boolean awaitCalled(long timeout, TimeUnit unit) throws InterruptedException {
            return called.await(timeout, unit);
        }

        void release() {
            release.countDown();
        }
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(
                "stub-id", List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static HarnessAgent buildAgent(ChatModelBase model, Path workspace) {
        return HarnessAgent.builder()
                .name("coding-assistant")
                .model(model)
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .stateStore(new InMemoryAgentStateStore())
                .build();
    }

    private static Msg runStreamAndInterrupt(
            HarnessAgent agent, RuntimeContext ctx, BlockingModel model, Runnable interruptAction)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Msg> future =
                    executor.submit(
                            () ->
                                    agent.streamEvents(List.of(userMsg("hello")), ctx)
                                            .filter(e -> e instanceof AgentResultEvent)
                                            .cast(AgentResultEvent.class)
                                            .next()
                                            .map(AgentResultEvent::getResult)
                                            .block());
            assertTrue(
                    model.awaitCalled(5, TimeUnit.SECONDS),
                    "agent stream should have started a model call");
            interruptAction.run();
            model.release();
            return future.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void interruptWithRuntimeContext_targetsCustomSessionStream() throws Exception {
        Files.createDirectories(workspace);
        BlockingModel model = new BlockingModel();
        try (HarnessAgent agent = buildAgent(model, workspace)) {
            RuntimeContext ctx =
                    RuntimeContext.builder().userId("user-1").sessionId("conversation-123").build();
            Msg result =
                    runStreamAndInterrupt(
                            agent, ctx, model, () -> agent.interrupt(ctx, new UserMessage("用户取消")));
            assertNotNull(result);
            assertEquals(GenerateReason.INTERRUPTED, result.getGenerateReason());
        }
    }

    @Test
    void interruptWithRuntimeContextWithoutMsg_targetsCustomSessionStream() throws Exception {
        Files.createDirectories(workspace);
        BlockingModel model = new BlockingModel();
        try (HarnessAgent agent = buildAgent(model, workspace)) {
            RuntimeContext ctx =
                    RuntimeContext.builder().userId("user-1").sessionId("conversation-123").build();
            Msg result = runStreamAndInterrupt(agent, ctx, model, () -> agent.interrupt(ctx));
            assertNotNull(result);
            assertEquals(GenerateReason.INTERRUPTED, result.getGenerateReason());
        }
    }

    @Test
    void interruptWithoutContext_prefersActiveCallSession() throws Exception {
        Files.createDirectories(workspace);
        BlockingModel model = new BlockingModel();
        try (HarnessAgent agent = buildAgent(model, workspace)) {
            RuntimeContext ctx =
                    RuntimeContext.builder().userId("user-1").sessionId("conversation-123").build();
            Msg result =
                    runStreamAndInterrupt(
                            agent, ctx, model, () -> agent.interrupt(new UserMessage("用户取消")));
            assertNotNull(result);
            assertEquals(GenerateReason.INTERRUPTED, result.getGenerateReason());
        }
    }

    @Test
    void interruptWithoutContext_defaultSessionStreamStillInterruptible() throws Exception {
        Files.createDirectories(workspace);
        BlockingModel model = new BlockingModel();
        try (HarnessAgent agent = buildAgent(model, workspace)) {
            // No sessionId on the context: the stream runs under the default session
            // (the agent name), which the context-free interrupt must keep targeting.
            Msg result =
                    runStreamAndInterrupt(
                            agent,
                            RuntimeContext.empty(),
                            model,
                            () -> agent.interrupt(new UserMessage("用户取消")));
            assertNotNull(result);
            assertEquals(GenerateReason.INTERRUPTED, result.getGenerateReason());
        }
    }

    @Test
    void interruptWithoutActiveCall_fallsBackToDefaultSessionWithoutThrowing() throws Exception {
        Files.createDirectories(workspace);
        try (HarnessAgent agent = buildAgent(new BlockingModel(), workspace)) {
            // No in-flight call: the context-free overloads must keep their legacy fallback
            // (interrupting the default session slot) instead of failing.
            agent.interrupt();
            agent.interrupt(new UserMessage("用户取消"));
        }
    }
}
