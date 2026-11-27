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
package io.agentscope.harness.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Normal agent-id routing must survive asynchronous subagent completion. */
class HarnessGatewayWakeupRoutingTest {

    private static final String RESULT = "BUILD_RESULT_42: all checks passed";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @TempDir Path temp;

    @Test
    void explicitSecondaryAgentReceivesItsOwnBackgroundCompletion() throws Exception {
        runBackgroundWorkflow("support");
    }

    @Test
    void defaultAgentStillReceivesItsOwnBackgroundCompletion() throws Exception {
        runBackgroundWorkflow("sales");
    }

    @Test
    void secondaryAgentRegisteredUnderAnAliasReceivesItsOwnBackgroundCompletion() throws Exception {
        runBackgroundWorkflow("support", "support-route");
    }

    @Test
    void secondaryAgentWithoutAnExplicitIdReceivesItsOwnBackgroundCompletion() throws Exception {
        runBackgroundWorkflow("support", "support-route", false);
    }

    @Test
    void agentsWithTheSameDisplayNameReceiveTheirOwnBackgroundCompletion() throws Exception {
        runBackgroundWorkflow("support", "support-route", false, "assistant");
    }

    private void runBackgroundWorkflow(String ownerId) throws Exception {
        runBackgroundWorkflow(ownerId, "support");
    }

    private void runBackgroundWorkflow(String ownerId, String supportRouteId) throws Exception {
        runBackgroundWorkflow(ownerId, supportRouteId, true);
    }

    private void runBackgroundWorkflow(
            String ownerId, String supportRouteId, boolean explicitAgentId) throws Exception {
        runBackgroundWorkflow(ownerId, supportRouteId, explicitAgentId, null);
    }

    private void runBackgroundWorkflow(
            String ownerId, String supportRouteId, boolean explicitAgentId, String displayName)
            throws Exception {
        var completionObserved = new CountDownLatch(1);
        var salesModel = new RecordingParentModel("sales", ownerId, completionObserved);
        var supportModel = new RecordingParentModel("support", ownerId, completionObserved);
        var workerResult = Sinks.<ChatResponse>one();
        Model workerModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return workerResult.asMono().flux();
                    }

                    @Override
                    public String getModelName() {
                        return "local-worker";
                    }
                };
        var bus =
                new WorkspaceMessageBus(
                        new LocalFilesystem(temp.resolve("bus"), true, 10),
                        "/bus",
                        Duration.ofMillis(50));
        try (var worker =
                        ReActAgent.builder()
                                .name("build-worker")
                                .model(workerModel)
                                .stateStore(new InMemoryAgentStateStore())
                                .build();
                var sales = parent("sales", salesModel, bus, worker, ownerId, true, displayName);
                var support =
                        parent(
                                "support",
                                supportModel,
                                bus,
                                worker,
                                ownerId,
                                explicitAgentId,
                                displayName)) {
            var gateway = HarnessGateway.create(new ChannelManager(), bus);
            gateway.bindMainAgent(sales);
            gateway.registerAgent(supportRouteId, support);
            var context =
                    new MsgContext(
                            "chatui",
                            null,
                            "build-conversation",
                            null,
                            null,
                            Map.of("agentId", ownerId.equals("support") ? supportRouteId : ownerId),
                            "user-42");
            String sessionId = "gw-" + SessionIdUtils.deterministicHash(context.canonicalKey());
            Msg initial =
                    gateway.run(
                                    context,
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent(
                                                            "Run the build in a background worker"
                                                                    + " and report its result.")
                                                    .build()),
                                    OutboundAddress.direct("chatui", "user-42"))
                            .block(TIMEOUT);
            assertNotNull(initial);
            assertEquals("Build started; waiting for completion.", initial.getTextContent());
            awaitSessionIdle(gateway, sessionId);
            assertFalse(gateway.isSessionRunning(sessionId));
            assertEquals(2, modelFor(ownerId, salesModel, supportModel).inputs.size());
            assertEquals(0, modelFor(other(ownerId), salesModel, supportModel).inputs.size());

            // The worker finishes only after the user-facing turn has ended. The SDK's own
            // TaskRepository completion callback writes the inbox result and wakeup entry.
            assertEquals(
                    Sinks.EmitResult.OK, workerResult.tryEmitValue(textResponse("worker", RESULT)));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!Boolean.TRUE.equals(bus.queuePeek("agentscope:wakeups").block(TIMEOUT))) {
                assertTrue(System.nanoTime() < deadline, "Task completion did not enqueue wakeup");
                Thread.sleep(10);
            }
            assertTrue(Boolean.TRUE.equals(bus.inboxHasMessages(sessionId).block(TIMEOUT)));

            // Initial drain is the documented recovery path for queued background completions.
            // Starting after the queued entry exists makes this independent of polling timing.
            var wakeupTerminated = new CountDownLatch(1);
            var wakeupError = new AtomicReference<Throwable>();
            WakeupDispatcher.WakeupTarget observedGateway =
                    new WakeupDispatcher.WakeupTarget() {
                        @Override
                        public boolean isSessionRunning(String id) {
                            return gateway.isSessionRunning(id);
                        }

                        @Override
                        public Mono<Msg> runWakeup(String id) {
                            return runWakeup(null, id);
                        }

                        @Override
                        public Mono<Msg> runWakeup(String userId, String id) {
                            return runWakeup(userId, id, null);
                        }

                        @Override
                        public Mono<Msg> runWakeup(String userId, String id, String agentId) {
                            return gateway.runWakeup(userId, id, agentId)
                                    .doOnError(wakeupError::set)
                                    .doFinally(signal -> wakeupTerminated.countDown());
                        }
                    };
            try (var dispatcher = new WakeupDispatcher(bus, observedGateway)) {
                dispatcher.start();
                assertTrue(
                        wakeupTerminated.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                        "Wakeup did not finish");
                awaitSessionIdle(gateway, sessionId);
                assertNull(wakeupError.get(), () -> "Wakeup failed: " + wakeupError.get());
                assertEquals(
                        0,
                        completionObserved.getCount(),
                        "Neither registered agent received the completed build result");
                assertEquals(
                        1,
                        modelFor(ownerId, salesModel, supportModel).completionRequests(),
                        () ->
                                "Only the owning agent should process the result. sales="
                                        + salesModel.describe()
                                        + ", support="
                                        + supportModel.describe());
                assertEquals(
                        0,
                        modelFor(other(ownerId), salesModel, supportModel).completionRequests(),
                        "The other agent must not consume this conversation's completion");
            }
        }
    }

    private static void awaitSessionIdle(HarnessGateway gateway, String sessionId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (gateway.isSessionRunning(sessionId)) {
            assertTrue(System.nanoTime() < deadline, "Session gate was not released");
            Thread.sleep(10);
        }
    }

    private HarnessAgent parent(
            String id,
            RecordingParentModel model,
            WorkspaceMessageBus bus,
            ReActAgent worker,
            String ownerId,
            boolean explicitAgentId,
            String displayName) {
        Path workspace = temp.resolve(id);
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name(displayName != null ? displayName : id)
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace, true, 10))
                        .stateStore(new InMemoryAgentStateStore())
                        .threadJournalStore(new InMemoryThreadJournalStore())
                        .messageBus(bus);
        if (explicitAgentId) {
            builder.agentId(id);
        }
        if (id.equals(ownerId)) {
            builder.subagentFactory("build-worker", "Run the build", ignored -> worker);
        }
        return builder.build();
    }

    private static String other(String ownerId) {
        return ownerId.equals("sales") ? "support" : "sales";
    }

    private static RecordingParentModel modelFor(
            String id, RecordingParentModel sales, RecordingParentModel support) {
        return id.equals("sales") ? sales : support;
    }

    private static ChatResponse textResponse(String id, String text) {
        return new ChatResponse(
                id, List.of(TextBlock.builder().text(text).build()), null, Map.of(), "stop");
    }

    private static final class RecordingParentModel implements Model {
        private final String id;
        private final String ownerId;
        private final CountDownLatch completionObserved;
        private final List<List<Msg>> inputs = new CopyOnWriteArrayList<>();

        private RecordingParentModel(String id, String ownerId, CountDownLatch completionObserved) {
            this.id = id;
            this.ownerId = ownerId;
            this.completionObserved = completionObserved;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            if (messages.stream().anyMatch(m -> m.getTextContent().contains(RESULT))) {
                completionObserved.countDown();
                return Flux.just(textResponse(id + "-complete", id + " reports: " + RESULT));
            }
            if (id.equals(ownerId) && inputs.size() == 1) {
                Map<String, Object> arguments =
                        Map.of(
                                "agent_id",
                                "build-worker",
                                "task",
                                "Run the build and return its summary",
                                "reusable",
                                false,
                                "timeout_seconds",
                                0);
                return Flux.just(
                        new ChatResponse(
                                id + "-spawn",
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("spawn-build")
                                                .name("agent_spawn")
                                                .input(arguments)
                                                .content(JsonUtils.getJsonCodec().toJson(arguments))
                                                .build()),
                                null,
                                Map.of(),
                                "tool_use"));
            }
            return Flux.just(
                    textResponse(id + "-waiting", "Build started; waiting for completion."));
        }

        private long completionRequests() {
            return inputs.stream()
                    .filter(ms -> ms.stream().anyMatch(m -> m.getTextContent().contains(RESULT)))
                    .count();
        }

        private String describe() {
            return "requests=" + inputs.size() + ", completions=" + completionRequests();
        }

        @Override
        public String getModelName() {
            return "local-" + id;
        }
    }
}
