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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * End-to-end coverage for the agent-scoped cancellation of background memory work, driven through
 * the real {@link HarnessAgent} lifecycle rather than by calling the middlewares directly.
 *
 * <p>That distinction is the point of this test. The memory middlewares are constructed inside
 * {@code Builder.build()}, where {@code this} is the builder, while {@link HarnessAgent#close()}
 * runs on the agent instance. If the object handed to the middlewares is not the same object
 * {@code close()} cancels, the agent-scoped cancellation silently matches nothing and the hung
 * model call leaks — exactly the failure mode the unit tests below the agent level cannot see.
 *
 * <p>The model used for memory work never emits, so its subscription stands in for the stuck
 * SSE/HTTP connection reported in production.
 */
@HarnessQuiescence
class HarnessAgentMemoryCancelTest {

    @TempDir Path workspace;

    @Test
    @Timeout(60)
    void closeCancelsThisAgentsHungMemoryFlush() throws Exception {
        Files.createDirectories(workspace);

        CountDownLatch flushStarted = new CountDownLatch(1);
        HarnessAgent agent = buildAgent(hangingModel(flushStarted));
        boolean closed = false;
        try {
            agent.call(
                            userMsg("remember: deploys happen on Fridays"),
                            RuntimeContext.builder().sessionId("s-memory-cancel").build())
                    .block();

            assertTrue(
                    flushStarted.await(10, TimeUnit.SECONDS),
                    "the background memory model call must have been started");
            assertFalse(
                    MemoryBackgroundTasks.awaitQuiescence(1, TimeUnit.SECONDS),
                    "before close the hung memory task must still be in flight");

            agent.close();
            closed = true;

            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS),
                    "close() must cancel this agent's in-flight memory task; if the owner handed to"
                            + " the memory middlewares is not the object close() cancels, nothing"
                            + " is cancelled and the connection leaks");
        } finally {
            if (!closed) {
                agent.close();
            }
            MemoryBackgroundTasks.cancelAll();
            MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS);
            // The real agent lifecycle claims the shared maintenance gate. Leaving it claimed would
            // throttle another test's maintenance past its min-gap and make that test's hung task
            // complete instantly.
            LocalPeriodicGate.clearForTests();
        }
    }

    @Test
    @Timeout(60)
    void closeLeavesOtherOwnersTasksUntouched() throws Exception {
        Files.createDirectories(workspace);

        // Stands in for another agent's background work: same global registry, different owner.
        Object otherOwner = new Object();
        Disposable otherOwnerTask =
                Flux.never().subscribeOn(Schedulers.boundedElastic()).subscribe();
        MemoryBackgroundTasks.register(otherOwner, otherOwnerTask);

        CountDownLatch flushStarted = new CountDownLatch(1);
        HarnessAgent agent = buildAgent(hangingModel(flushStarted));
        boolean closed = false;
        try {
            agent.call(
                            userMsg("remember: deploys happen on Fridays"),
                            RuntimeContext.builder().sessionId("s-memory-scope").build())
                    .block();

            assertTrue(
                    flushStarted.await(10, TimeUnit.SECONDS),
                    "the background memory model call must have been started");

            agent.close();
            closed = true;

            assertFalse(
                    otherOwnerTask.isDisposed(),
                    "close() must stay scoped to its own agent and leave other owners' tasks"
                            + " running");

            assertTrue(
                    MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS),
                    "close() must cancel this agent's own in-flight memory task even while an"
                            + " unrelated owner still has work registered");
        } finally {
            if (!closed) {
                agent.close();
            }
            if (!otherOwnerTask.isDisposed()) {
                otherOwnerTask.dispose();
            }
            MemoryBackgroundTasks.cancelAll();
            MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS);
            LocalPeriodicGate.clearForTests();
        }
    }

    private HarnessAgent buildAgent(Model memoryModel) {
        return HarnessAgent.builder()
                .name("memory-agent")
                .model(stubModel("ok"))
                .memory(MemoryConfig.builder().model(memoryModel).build())
                .workspace(workspace)
                .abstractFilesystem(new LocalFilesystem(workspace))
                .build();
    }

    private static Model hangingModel(CountDownLatch flushStarted) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                flushStarted.countDown();
                // Never emits [DONE]: simulates a stuck SSE/HTTP connection.
                return Flux.never();
            }

            @Override
            public String getModelName() {
                return "hanging-memory-model";
            }
        };
    }

    private static Model stubModel(String assistantText) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                ChatResponse chunk =
                        new ChatResponse(
                                "stub-id",
                                List.of(TextBlock.builder().text(assistantText).build()),
                                null,
                                Map.of(),
                                "stop");
                return Flux.just(chunk);
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }
        };
    }

    private static Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }
}
