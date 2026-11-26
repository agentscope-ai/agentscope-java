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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Dedicated compaction models must remain selected during overflow recovery. */
class HarnessAgentCompactionModelRecoveryTest {
    private static final String PAD = " Previous requirements and design decisions.".repeat(20);
    private static final Duration WAIT = Duration.ofSeconds(30);
    @TempDir Path workspace;

    @Test
    void proactiveCompactionUsesConfiguredLargerContextSummaryModel() {
        verifySelectedSummaryModel(40, false, true, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emergencyCompactionMustUseConfiguredLargerContextSummaryModel(boolean stream) {
        // Forty-nine messages are below the normal default fifty-message threshold. A history
        // collected with a larger model can be loaded when switching to a smaller primary model.
        verifySelectedSummaryModel(50, false, true, stream);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeJournalEmergencyCompactionUsesConfiguredLargerContextSummaryModel(boolean stream) {
        verifySelectedSummaryModel(50, true, true, stream);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotEmergencyCompactionWithoutDedicatedModelKeepsBuildTimeDefault(boolean stream) {
        verifySelectedSummaryModel(50, false, false, stream);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeJournalEmergencyCompactionWithoutDedicatedModelKeepsPerCallModel(boolean stream) {
        verifySelectedSummaryModel(50, true, false, stream);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotEmergencyCompactionResolvesDefaultModelIdentityOffSubscriberThread(
            boolean stream) {
        LimitedPrimaryModel primary = new LimitedPrimaryModel();
        LargeSummaryModel summary = new LargeSummaryModel();
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicReference<String> factoryThread = new AtomicReference<>();
        String modelIdentity = "overflow-recovery-default";
        ModelRegistry.registerFactory(
                modelIdentity,
                id -> {
                    factoryCalls.incrementAndGet();
                    factoryThread.set(Thread.currentThread().getName());
                    return summary;
                });
        CompactionMiddleware compaction =
                new CompactionMiddleware(
                        null,
                        null,
                        CompactionConfig.builder()
                                .triggerMessages(50)
                                .keepTokens(0)
                                .keepMessages(20)
                                .flushBeforeCompact(false)
                                .build(),
                        null,
                        false);
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("identity-summary-recovery")
                        .model(modelIdentity)
                        .journalEnabled(false)
                        .stateStore(new InMemoryAgentStateStore())
                        .middleware(compaction.overflowRecovery())
                        .build()) {
            assertNull(agent.getModel());
            assertEquals(0, factoryCalls.get(), "The configured identity must remain lazy");
            RuntimeContext ctx =
                    RuntimeContext.builder().sessionId("restored-conversation").build();
            ctx.put(Model.class, primary);
            agent.updateAgentState(
                    ctx, "restore_history", state -> state.contextMutable().addAll(priorHistory()));
            Msg input = message(MsgRole.USER, "Continue with the next step.");
            Mono<Msg> response =
                    stream
                            ? agent.streamEvents(List.of(input), ctx)
                                    .ofType(AgentResultEvent.class)
                                    .single()
                                    .map(AgentResultEvent::getResult)
                            : agent.call(List.of(input), ctx);
            Msg reply =
                    assertDoesNotThrow(
                            () -> response.subscribeOn(Schedulers.parallel()).block(WAIT));
            assertNotNull(reply);
            assertEquals("ok", reply.getTextContent());
            assertTrue(summary.calls.get() > 0, "The configured default must handle the summary");
            assertEquals(0, primary.summaryCalls.get());
            assertEquals(1, factoryCalls.get());
            assertTrue(
                    factoryThread.get().startsWith("agentscope-model-request"),
                    () -> "Identity resolution ran on " + factoryThread.get());
            assertSame(primary, ctx.get(Model.class), "Recovery must retain the per-call model");
        } finally {
            ModelRegistry.reset();
        }
    }

    private void verifySelectedSummaryModel(
            int triggerMessages,
            boolean nativeJournal,
            boolean dedicatedSummaryModel,
            boolean stream) {
        LimitedPrimaryModel primary = new LimitedPrimaryModel();
        LargeSummaryModel summary = new LargeSummaryModel();
        CompactionConfig compaction =
                CompactionConfig.builder()
                        .model(dedicatedSummaryModel ? summary : null)
                        .triggerMessages(triggerMessages)
                        .keepTokens(0)
                        .keepMessages(20)
                        .flushBeforeCompact(false)
                        .build();
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("dedicated-summary-recovery")
                        .model(dedicatedSummaryModel ? primary : summary)
                        .workspace(workspace)
                        .journalEnabled(nativeJournal)
                        .stateStore(new InMemoryAgentStateStore())
                        .compaction(compaction)
                        .disableMemoryHooks()
                        .disableWorkspaceContext()
                        .disableDefaultWorkspaceSkills()
                        .disableDynamicSkills()
                        .disableSubagents()
                        .disableWebTools()
                        .disableToolsConfig()
                        .enableAgentTracingLog(false);
        if (nativeJournal) builder.threadJournalStore(new InMemoryThreadJournalStore());
        try (HarnessAgent agent = builder.build()) {
            RuntimeContext ctx =
                    RuntimeContext.builder().sessionId("restored-conversation").build();
            if (!dedicatedSummaryModel) {
                ctx.put(Model.class, primary);
            }
            agent.getDelegate()
                    .updateAgentState(
                            ctx,
                            "restore_history",
                            state -> state.contextMutable().addAll(priorHistory()));
            Msg input = message(MsgRole.USER, "Continue with the next step.");
            Mono<Msg> response =
                    stream
                            ? agent.streamEvents(input, ctx)
                                    .ofType(AgentResultEvent.class)
                                    .single()
                                    .map(AgentResultEvent::getResult)
                            : agent.call(input, ctx);
            if (nativeJournal && !dedicatedSummaryModel) {
                // Native Thread recovery retains its per-call selection. This fixture's smaller
                // model cannot summarize the history, so its overflow must propagate.
                IllegalStateException error =
                        assertThrows(IllegalStateException.class, () -> response.block(WAIT));
                assertEquals("context_length_exceeded", error.getMessage());
                assertTrue(primary.summaryCalls.get() > 0);
                assertEquals(0, summary.calls.get());
                return;
            }
            Msg reply =
                    assertDoesNotThrow(
                            () -> response.block(WAIT),
                            () ->
                                    "The dedicated larger-context model can compact this history"
                                        + " and make the primary request fit; actual summary calls:"
                                        + " primary="
                                            + primary.summaryCalls.get()
                                            + ", dedicated="
                                            + summary.calls.get());
            assertNotNull(reply);
            assertEquals("ok", reply.getTextContent());
            assertTrue(
                    summary.calls.get() > 0,
                    "The dedicated or build-time default model must handle the summary");
            assertEquals(
                    0,
                    primary.summaryCalls.get(),
                    "The primary model must not replace the selected compaction model");
        }
    }

    private static List<Msg> priorHistory() {
        List<Msg> prior = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            prior.add(message(MsgRole.USER, "request " + i + PAD));
            prior.add(message(MsgRole.ASSISTANT, "answer " + i + PAD));
        }
        return prior;
    }

    private static Msg message(MsgRole role, String text) {
        return Msg.builder().role(role).textContent(text).build();
    }

    private static Flux<ChatResponse> text(String text) {
        return Flux.just(
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(text).build()))
                        .finishReason("stop")
                        .build());
    }

    private static final class LimitedPrimaryModel implements Model {
        private final AtomicInteger summaryCalls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "small-context-primary";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (messages.stream()
                    .anyMatch(m -> m.getTextContent().contains("memory extraction assistant"))) {
                return text("NO_REPLY");
            }
            boolean summary =
                    messages.stream()
                            .anyMatch(
                                    m ->
                                            m.getTextContent()
                                                    .contains("Context Extraction Assistant"));
            if (summary) summaryCalls.incrementAndGet();
            long conversationChars =
                    messages.stream()
                            .filter(m -> m.getRole() != MsgRole.SYSTEM)
                            .mapToLong(m -> m.getTextContent().length())
                            .sum();
            if (conversationChars > 20_000) {
                return Flux.error(new IllegalStateException("context_length_exceeded"));
            }
            return text("ok");
        }
    }

    private static final class LargeSummaryModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getModelName() {
            return "large-context-dedicated-summary";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            if (messages.stream()
                    .anyMatch(m -> m.getTextContent().contains("memory extraction assistant"))) {
                return text("NO_REPLY");
            }
            calls.incrementAndGet();
            return text(
                    "The preceding discussion established the requirements and design decisions.");
        }
    }
}
