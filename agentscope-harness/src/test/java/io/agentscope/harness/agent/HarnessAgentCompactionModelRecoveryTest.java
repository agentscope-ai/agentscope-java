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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Dedicated compaction models must remain selected during overflow recovery. */
class HarnessAgentCompactionModelRecoveryTest {
    private static final String PAD = " Previous requirements and design decisions.".repeat(20);
    private static final Duration WAIT = Duration.ofSeconds(30);
    @TempDir Path workspace;

    @Test
    void proactiveCompactionUsesConfiguredLargerContextSummaryModel() {
        verifySelectedSummaryModel(40);
    }

    @Test
    void emergencyCompactionMustUseConfiguredLargerContextSummaryModel() {
        // Forty-nine messages are below the normal default fifty-message threshold. A history
        // collected with a larger model can be loaded when switching to a smaller primary model.
        verifySelectedSummaryModel(50);
    }

    @Test
    void nativeJournalEmergencyCompactionUsesConfiguredLargerContextSummaryModel() {
        verifySelectedSummaryModel(50, true);
    }

    @Test
    void snapshotEmergencyCompactionWithoutDedicatedModelKeepsBuildTimeDefault() {
        verifySelectedSummaryModel(50, false, false);
    }

    private void verifySelectedSummaryModel(int triggerMessages) {
        verifySelectedSummaryModel(triggerMessages, false);
    }

    private void verifySelectedSummaryModel(int triggerMessages, boolean nativeJournal) {
        verifySelectedSummaryModel(triggerMessages, nativeJournal, true);
    }

    private void verifySelectedSummaryModel(
            int triggerMessages, boolean nativeJournal, boolean dedicatedSummaryModel) {
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
            List<Msg> prior = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                prior.add(message(MsgRole.USER, "request " + i + PAD));
                prior.add(message(MsgRole.ASSISTANT, "answer " + i + PAD));
            }
            agent.getDelegate()
                    .updateAgentState(
                            ctx, "restore_history", state -> state.contextMutable().addAll(prior));
            Msg reply =
                    assertDoesNotThrow(
                            () ->
                                    agent.call(
                                                    message(
                                                            MsgRole.USER,
                                                            "Continue with the next step."),
                                                    ctx)
                                            .block(WAIT),
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
