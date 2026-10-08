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
package io.agentscope.builder.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.EnvironmentSpecFactory;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.MemoryMountService;
import io.agentscope.builder.web.managed.SessionAgentBuildSpec;
import io.agentscope.builder.web.managed.SessionResourceMountService;
import io.agentscope.builder.web.managed.VaultCredentialResolver;
import io.agentscope.builder.web.toolbus.ToolConfirmationMiddleware;
import io.agentscope.builder.web.toolbus.ToolEventBus;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.examples.jev.JevCodeReviewExample;
import io.agentscope.examples.jev.JevSupervisionExample;
import io.agentscope.examples.jev.JevTraceEvaluationExample;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JevTraceManagedAgentTest {
    @TempDir Path workspace;

    @Test
    void actualServiceBuildCacheRunsHarnessAndDisablingEvaluationRestoresFlow() throws Exception {
        validateManagedBuild("evaluation", JevTraceServiceTest.CONFIG, "evaluation.completeness");
    }

    @Test
    void actualServiceBuildRunsSupervisionAndDisablingRestoresFlow() throws Exception {
        validateManagedBuild("supervision", JevSupervisionServiceTest.CONFIG, "supervision.advice");
    }

    @Test
    void actualServiceBuildExposesReviewToolAndKeepsItOutWhenDisabled() throws Exception {
        validateManagedBuild("review", JevCodeReviewServiceTest.CONFIG, "code_review");
    }

    @Test
    void reviewToolCannotBypassMissingServicePermissionPolicy() throws Exception {
        validateManagedBuild("review", JevCodeReviewServiceTest.CONFIG, "code_review", false);
    }

    @Test
    void actualServiceBuildExposesEvidenceToolAndDisablingRemovesIt() throws Exception {
        validateManagedBuild("retrieval", JevEvidenceServiceTest.CONFIG, "evidence_retrieval");
    }

    @Test
    void actualServiceBuildRoutesTheModelAndDisablingRestoresOriginal() throws Exception {
        validateManagedBuild("routing", JevPhaseRoutingServiceTest.CONFIG, "phase-routing");
    }

    @Test
    void actualServiceBuildRunsBrowserAndDisablingRemovesTool() throws Exception {
        validateManagedBuild("browser", JevBrowserServiceTest.CONFIG, "browser_task");
    }

    @Test
    void browserCannotBypassMissingServicePermissionPolicy() throws Exception {
        validateManagedBuild("browser", JevBrowserServiceTest.CONFIG, "browser_task", false);
    }

    private void validateManagedBuild(String purpose, String config, String expectedPurpose)
            throws Exception {
        validateManagedBuild(purpose, config, expectedPurpose, true);
    }

    private void validateManagedBuild(
            String purpose, String config, String expectedPurpose, boolean policyAvailable)
            throws Exception {
        boolean toolPurpose =
                purpose.equals("review")
                        || purpose.equals("retrieval")
                        || purpose.equals("browser");
        String toolName =
                purpose.equals("browser")
                        ? "read_browser"
                        : purpose.equals("retrieval") ? "search_evidence" : "review_code_snapshot";
        int expectedCalls =
                purpose.equals("browser")
                        ? 2
                        : purpose.equals("review") ? 6 : purpose.equals("retrieval") ? 3 : 1;
        AtomicInteger evaluations = new AtomicInteger();
        JevClient client = mock(JevClient.class);
        when(client.systemOne(any()))
                .thenAnswer(
                        call -> {
                            evaluations.incrementAndGet();
                            if (purpose.equals("browser"))
                                return io.agentscope.examples.jev.JevBrowserExample
                                        .syntheticAnswers(call.getArgument(0));
                            if (purpose.equals("routing"))
                                return io.agentscope.examples.jev.JevPhaseRoutingExample
                                        .syntheticAnswers(call.getArgument(0));
                            if (purpose.equals("retrieval"))
                                return io.agentscope.examples.jev.JevEvidenceExample
                                        .syntheticAnswers(call.getArgument(0));
                            if (toolPurpose)
                                return JevCodeReviewExample.syntheticAnswers(call.getArgument(0));
                            return purpose.equals("supervision")
                                    ? JevSupervisionExample.syntheticAnswers(call.getArgument(0))
                                    : JevTraceEvaluationExample.syntheticAnswers(
                                            call.getArgument(0));
                        });
        var support = new JevServiceSupport("fast,strong", () -> client);
        var control = mock(ControlPlaneClient.class);
        var session =
                new ManagedSessionDto(
                        "eval-session",
                        "owner",
                        "agent",
                        "owner",
                        1,
                        "latest",
                        null,
                        "local",
                        null,
                        null,
                        null,
                        null,
                        "idle",
                        null,
                        0,
                        0,
                        null);
        when(control.resolveSession("eval-session"))
                .thenReturn(
                        new SessionResolveResult(
                                session,
                                Map.of(
                                        "name",
                                        "evaluation-test",
                                        "system",
                                        "Answer the user.",
                                        "maxIters",
                                        2),
                                null,
                                null,
                                null,
                                Map.of(),
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null));
        var receivedToolResults = new java.util.concurrent.CopyOnWriteArrayList<ToolResultBlock>();
        var model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "offline";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        messages.stream()
                                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                                .forEach(receivedToolResults::add);
                        if (toolPurpose
                                && tools.stream().anyMatch(t -> t.getName().equals(toolName))
                                && messages.stream()
                                        .noneMatch(
                                                m ->
                                                        !m.getContentBlocks(ToolResultBlock.class)
                                                                .isEmpty()))
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            new ToolUseBlock(
                                                                    "review-call",
                                                                    toolName,
                                                                    purpose.equals("browser")
                                                                            ? Map.of(
                                                                                    "goal",
                                                                                    io.agentscope
                                                                                            .examples
                                                                                            .jev
                                                                                            .JevBrowserExample
                                                                                            .GOAL)
                                                                            : purpose.equals(
                                                                                            "retrieval")
                                                                                    ? Map.of(
                                                                                            "query",
                                                                                            "Are all"
                                                                                                + " refunds"
                                                                                                + " guaranteed?")
                                                                                    : Map.of(
                                                                                            "snapshotId",
                                                                                            "authorized"),
                                                                    purpose.equals("browser")
                                                                            ? "{\"goal\":\"Find the"
                                                                                  + " standard"
                                                                                  + " warranty"
                                                                                  + " duration.\"}"
                                                                            : purpose.equals(
                                                                                            "retrieval")
                                                                                    ? "{\"query\":\"Are"
                                                                                          + " all refunds"
                                                                                          + " guaranteed?\"}"
                                                                                    : "{\"snapshotId\":\"authorized\"}",
                                                                    null)))
                                            .build());
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Order shipped.")
                                                                .build()))
                                        .build());
                    }
                };
        var routedCalls = new AtomicInteger();
        var routedModel =
                new ChatModelBase() {
                    public String getModelName() {
                        return "routed-model";
                    }

                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        routedCalls.incrementAndGet();
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Order shipped.")
                                                                .build()))
                                        .build());
                    }
                };
        var routePool =
                List.of(
                        io.agentscope.examples.jev.JevPhaseRoutingExample.candidate(
                                "fast", routedModel),
                        io.agentscope.examples.jev.JevPhaseRoutingExample.candidate(
                                "strong", routedModel));
        var sessions = mock(DataSessionService.class);
        when(sessions.resolve("eval-session"))
                .thenAnswer(
                        ignored -> policyAvailable ? control.resolveSession("eval-session") : null);
        var builds =
                new HarnessAgentBuildService(
                        Optional.of(model),
                        mock(ToolEventBus.class),
                        new SharedWorkspacePaths(workspace),
                        mock(EnvironmentSpecFactory.class),
                        new ToolConfirmationMiddleware(null, sessions),
                        mock(MemoryMountService.class),
                        mock(VaultCredentialResolver.class),
                        new InMemoryAgentStateStore(),
                        mock(SessionResourceMountService.class),
                        mock(DefinitionStore.class),
                        control);
        builds.setJevServiceSupport(support);
        try {
            var spec = new SessionAgentBuildSpec(1, "local", null, config, null, null);
            var agent = builds.getOrBuildAgent(session, spec);
            assertThat(builds.getOrBuildAgent(session, spec)).isSameAs(agent);
            List<JevExecution.Record> records = new CopyOnWriteArrayList<>();
            CountDownLatch latch =
                    new CountDownLatch(
                            purpose.equals("browser")
                                    ? 3
                                    : toolPurpose ? 1 : purpose.equals("routing") ? 2 : 3);
            var ctx =
                    RuntimeContext.builder()
                            .put(
                                    io.agentscope.extensions.judge.jev.browser.JevBrowserSession
                                            .Source.class,
                                    new io.agentscope.extensions.judge.jev.browser.JevBrowserSession
                                            .Source(
                                            req ->
                                                    new io.agentscope.examples.jev.JevBrowserExample
                                                            .ScriptedSession(
                                                            io.agentscope.examples.jev
                                                                    .JevBrowserExample.scope(
                                                                    req.context()),
                                                            new AtomicInteger(),
                                                            new AtomicInteger()),
                                            io.agentscope.examples.jev.JevBrowserExample::verify))
                            .sessionId(session.id())
                            .userId(session.ownerId())
                            .put(
                                    io.agentscope.extensions.judge.jev.routing.JevRouteCatalog
                                            .Source.class,
                                    new io.agentscope.extensions.judge.jev.routing.JevRouteCatalog
                                            .Source(
                                            (current, input) ->
                                                    Mono.just(
                                                            new io.agentscope.extensions.judge.jev
                                                                    .routing.JevRouteCatalog
                                                                    .Snapshot(
                                                                    routePool, 100, 100, null, null,
                                                                    0))))
                            .put(
                                    io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool
                                            .Source.class,
                                    new io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool
                                            .Source(
                                            req ->
                                                    Mono.just(
                                                            io.agentscope.examples.jev
                                                                    .JevEvidenceExample.passages()),
                                            (current, passage) -> !passage.id().equals("private")))
                            .put(
                                    JevCodeReviewTool.Source.class,
                                    new JevCodeReviewTool.Source(
                                            (context, id) ->
                                                    Mono.just(JevCodeReviewExample.snapshot())))
                            .put(
                                    JevServiceSupport.TraceSink.class,
                                    new JevServiceSupport.TraceSink(
                                            r -> {
                                                records.add(r);
                                                latch.countDown();
                                            }))
                            .build();
            var events =
                    agent.streamEvents(List.of(new UserMessage("Where is the order?")), ctx)
                            .collectList()
                            .block(Duration.ofSeconds(10));
            var answer =
                    events.stream()
                            .filter(AgentResultEvent.class::isInstance)
                            .map(AgentResultEvent.class::cast)
                            .findFirst()
                            .orElseThrow();
            assertThat(answer.getResult().getTextContent()).isEqualTo("Order shipped.");
            if (!policyAvailable) {
                assertThat(evaluations.get()).isZero();
                assertThat(receivedToolResults)
                        .singleElement()
                        .satisfies(
                                result -> {
                                    assertThat(result.getState())
                                            .isEqualTo(
                                                    io.agentscope.core.message.ToolResultState
                                                            .DENIED);
                                    assertThat(result.getId()).isEqualTo("review-call");
                                });
                return;
            }
            assertThat(latch.await(5, TimeUnit.SECONDS))
                    .as(
                            "review calls=%s, tool output=%s",
                            evaluations.get(),
                            events.stream()
                                    .filter(
                                            io.agentscope.core.event.ToolResultTextDeltaEvent.class
                                                    ::isInstance)
                                    .map(
                                            io.agentscope.core.event.ToolResultTextDeltaEvent.class
                                                    ::cast)
                                    .map(
                                            io.agentscope.core.event.ToolResultTextDeltaEvent
                                                    ::getDelta)
                                    .toList())
                    .isTrue();
            assertThat(evaluations.get()).isEqualTo(expectedCalls);
            assertThat(records)
                    .anySatisfy(
                            r -> {
                                assertThat(r.purpose()).isEqualTo(expectedPurpose);
                                assertThat(r.status()).isEqualTo(JevExecution.Status.DECIDED);
                            });
            if (purpose.equals("routing")) {
                assertThat(routedCalls.get()).isEqualTo(1);
                assertThat(records)
                        .anySatisfy(
                                r -> {
                                    assertThat(r.purpose()).isEqualTo("routing-call");
                                    assertThat(r.recommendation())
                                            .containsEntry("dispatchedModel", "routed-model");
                                });
            }
            if (toolPurpose)
                assertThat(receivedToolResults)
                        .singleElement()
                        .satisfies(
                                result -> {
                                    assertThat(result.getState())
                                            .isEqualTo(
                                                    io.agentscope.core.message.ToolResultState
                                                            .SUCCESS);
                                    assertThat(result.getId()).isEqualTo("review-call");
                                });
            var off =
                    new SessionAgentBuildSpec(
                            1,
                            "local",
                            null,
                            "{\"jev\":{\"" + purpose + "\":{\"mode\":\"OFF\"}}}",
                            null,
                            null);
            var disabled = builds.getOrBuildAgent(session, off);
            assertThat(disabled).isNotSameAs(agent);
            disabled.streamEvents(List.of(new UserMessage("Where is the order?")), ctx)
                    .blockLast(Duration.ofSeconds(10));
            assertThat(evaluations.get()).isEqualTo(expectedCalls);
            if (purpose.equals("routing")) assertThat(routedCalls.get()).isEqualTo(1);
        } finally {
            builds.close();
            support.close();
        }
    }
}
