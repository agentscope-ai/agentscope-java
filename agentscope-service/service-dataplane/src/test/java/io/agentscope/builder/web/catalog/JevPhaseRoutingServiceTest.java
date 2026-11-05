/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.web.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.examples.jev.JevPhaseRoutingExample;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Snapshot;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Source;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class JevPhaseRoutingServiceTest {
    static final String CONFIG =
            """
            {"jev":{"routing":{"mode":"ENFORCE","threshold":0.8,"picksEffort":true,
            "routes":[{"id":"plan","description":"Architecture","models":["strong","fast"]},
            {"id":"execute","description":"Routine execution","models":["fast","strong"]}]}}}
            """;

    @Test
    void validatesRoutesAndAllowlistBeforeCreatingClient() {
        try (var support =
                new JevServiceSupport(
                        "fast,strong",
                        () -> {
                            throw new AssertionError("no client");
                        })) {
            assertThat(support.middlewares(null)).isEmpty();
            assertThat(support.middlewares(CONFIG.replace("ENFORCE", "OFF"))).isEmpty();
            for (String invalid :
                    List.of(
                            CONFIG.replace("\"threshold\":0.8,", ""),
                            CONFIG.replace("\"strong\"", "\"external\""),
                            CONFIG.replace("\"picksEffort\":true", "\"apiKey\":\"forbidden\""),
                            CONFIG.replace("\"id\":\"execute\"", "\"id\":\"plan\"")))
                assertThatThrownBy(() -> support.middlewares(invalid))
                        .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void emitsSeparateJudgmentAndDispatchRecordsWithoutPrompt() {
        var client = mock(JevClient.class);
        when(client.systemOne(any()))
                .thenAnswer(c -> JevPhaseRoutingExample.syntheticAnswers(c.getArgument(0)));
        try (var support = new JevServiceSupport("fast,strong", () -> client)) {
            var records = new CopyOnWriteArrayList<JevExecution.Record>();
            var latch = new CountDownLatch(2);
            var fast = JevPhaseRoutingExample.scriptedModel("fast", new AtomicInteger());
            var strong = JevPhaseRoutingExample.scriptedModel("strong", new AtomicInteger());
            var pool =
                    List.of(
                            JevPhaseRoutingExample.candidate("fast", fast),
                            JevPhaseRoutingExample.candidate("strong", strong));
            var ctx =
                    RuntimeContext.builder()
                            .userId("u")
                            .sessionId("s")
                            .put(
                                    Source.class,
                                    new Source(
                                            (c, i) ->
                                                    Mono.just(
                                                            new Snapshot(
                                                                    pool, 100, 100, null, null,
                                                                    0))))
                            .put(
                                    JevServiceSupport.TraceSink.class,
                                    new JevServiceSupport.TraceSink(
                                            r -> {
                                                records.add(r);
                                                latch.countDown();
                                            }))
                            .build();
            var middleware = support.middlewares(CONFIG).get(0);
            var input =
                    new ModelCallInput(
                            List.of(new UserMessage("private-request-marker")),
                            List.of(),
                            null,
                            fast);
            middleware
                    .onAgent(
                            null,
                            ctx,
                            new AgentInput(input.messages()),
                            ignored ->
                                    middleware.onModelCall(
                                            null,
                                            ctx,
                                            input,
                                            i -> {
                                                assertThat(i.model()).isSameAs(strong);
                                                return Flux.just(
                                                        new ModelCallEndEvent(
                                                                "r", new ChatUsage(30, 10, 0)));
                                            }))
                    .blockLast();
            try {
                assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            assertThat(records)
                    .extracting(JevExecution.Record::purpose)
                    .containsExactlyInAnyOrder("phase-routing", "routing-call");
            assertThat(records.toString()).doesNotContain("private-request-marker");
            assertThat(records)
                    .anySatisfy(
                            r -> {
                                assertThat(r.purpose()).isEqualTo("routing-call");
                                assertThat(r.recommendation())
                                        .containsEntry("dispatchedModel", "strong")
                                        .containsEntry("inputTokens", "30");
                            });
        }
    }
}
