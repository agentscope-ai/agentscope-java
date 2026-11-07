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
package io.agentscope.harness.agent.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class AgentSessionAwaitTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Clock PAST = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void awaitReturnsInterruptedWhenWriterExpiresOrReleases(boolean released) {
        var log = startedTask(PAST, false);
        var writer = log.acquire("worker", Duration.ofMinutes(2));
        if (released) log.release(writer);
        assertTrue(log.head().leaseUntil() < System.currentTimeMillis());

        // Reopen persisted state using the observer's current clock.
        var reopened = new JournalSessionLog(new LocalSessionStorage(directory), "session");
        try (var agent = observer(reopened)) {
            var session = session(agent);
            var task = session.task("accepted");
            assertEquals("interrupted", task.status());
            StepVerifier.create(session.await(task))
                    .assertNext(result -> assertEquals("interrupted", result.status()))
                    .expectComplete()
                    .verify(WAIT);
        }
    }

    @Test
    void awaitStillWaitsForLiveWriterToReleaseAfterRunEnd() {
        var log = startedTask(Clock.systemUTC(), true);
        var writer = log.acquire("worker", Duration.ofHours(1));
        try (var agent = observer(log)) {
            var session = session(agent);
            var task = session.task("accepted");
            assertEquals("interrupted", task.status());
            StepVerifier.create(session.await(task))
                    .expectSubscription()
                    .expectNoEvent(Duration.ofMillis(250))
                    .then(() -> log.release(writer))
                    .assertNext(result -> assertEquals("interrupted", result.status()))
                    .expectComplete()
                    .verify(WAIT);
        } finally {
            if ("worker".equals(log.head().owner())) log.release(writer);
        }
    }

    @Test
    void awaitDoesNotWaitForAReplacementWriter() {
        var crashed = startedTask(PAST, false);
        crashed.acquire("worker", Duration.ofMinutes(2));
        var log = new JournalSessionLog(new LocalSessionStorage(directory), "session");
        var replacement = log.acquire("replacement", Duration.ofHours(1));
        try (var agent = observer(log)) {
            var session = session(agent);
            StepVerifier.create(session.await(session.task("accepted")))
                    .assertNext(
                            result -> {
                                assertEquals("interrupted", result.status());
                                assertEquals("worker", result.runId());
                            })
                    .expectComplete()
                    .verify(WAIT);
            assertEquals("replacement", log.head().owner());
        } finally {
            log.release(replacement);
        }
    }

    private JournalSessionLog startedTask(Clock clock, boolean ended) {
        var log = new JournalSessionLog(new LocalSessionStorage(directory), "session", clock);
        var command =
                new SessionInbox.Command(
                        "accepted", "submit", "turn", List.of(new UserMessage("work")), List.of());
        new SessionInbox(log).locked(tx -> tx.accept(command));
        var recorder = new SessionRecorder(log, "turn", "worker", null);
        try {
            recorder.append("inbox/started", Map.of("commandId", "accepted", "kind", "submit"));
            recorder.start(
                            command.messages(),
                            AgentState.builder().userId("u").sessionId("s").build())
                    .block(WAIT);
            if (ended) recorder.recordNow("run/end", Map.of("status", "interrupted"));
        } finally {
            // Dispose the recorder's heartbeat. Tests reacquire the same owner to represent
            // a writer that stopped before release, without leaving a background task alive.
            recorder.close();
        }
        return log;
    }

    private AgentSession session(HarnessAgent agent) {
        return agent.session(RuntimeContext.builder().userId("u").sessionId("s").build());
    }

    private HarnessAgent observer(SessionLog log) {
        Model model =
                new Model() {
                    public String getModelName() {
                        return "session-observer-test";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> input, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.error(new AssertionError("Observation must not start a model"));
                    }
                };
        return HarnessAgent.builder()
                .name("observer")
                .workspace(directory.resolve("workspace"))
                .model(model)
                .sessionLogStore((key, context) -> log)
                .disableMemoryHooks()
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableToolsConfig()
                .build();
    }
}
