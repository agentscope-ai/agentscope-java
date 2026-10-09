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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.managed.SessionAgentBuildSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.examples.jev.JevSupervisionExample;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class JevSupervisionServiceTest {
    static final String CONFIG =
            """
            {"jev":{"supervision":{"mode":"SHADOW","threshold":0.8,"rejectionThreshold":0.2,
             "version":"orders-v1"}}}
            """;

    @Test
    void offDoesNotReadCredentialsAndInvalidConfigurationFailsBeforeClientCreation() {
        var support =
                new JevServiceSupport(
                        "",
                        () -> {
                            throw new AssertionError("client must not be constructed");
                        });
        try {
            assertThat(support.middlewares("{\"jev\":{\"supervision\":{\"mode\":\"OFF\"}}}"))
                    .isEmpty();
            for (String config :
                    List.of(
                            CONFIG.replace("SHADOW", "ENFORCE"),
                            CONFIG.replace("0.8", "0.1"),
                            CONFIG.replace("\"version\":", "\"minIntervalMillis\":0,\"version\":"),
                            CONFIG.replace("\"threshold\":0.8,", ""),
                            CONFIG.replace(
                                    "\"version\":",
                                    "\"baseUrl\":\"http://untrusted\",\"version\":"))) {
                assertThatThrownBy(() -> support.middlewares(config))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        } finally {
            support.close();
        }
    }

    @Test
    void traceMetadataReachesTheExistingRunSinkWithoutSensitiveContext() throws Exception {
        JevClient client = mock(JevClient.class);
        when(client.systemOne(any()))
                .thenAnswer(
                        invocation ->
                                JevSupervisionExample.syntheticAnswers(invocation.getArgument(0)));
        var support = new JevServiceSupport("", () -> client);
        try {
            var middleware = support.middlewares(CONFIG).get(0);
            List<JevExecution.Record> records = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(12);
            var ctx =
                    RuntimeContext.builder()
                            .sessionId("session-one")
                            .put(
                                    JevServiceSupport.TraceSink.class,
                                    new JevServiceSupport.TraceSink(
                                            r -> {
                                                records.add(r);
                                                latch.countDown();
                                            }))
                            .build();
            var user = new UserMessage("SENSITIVE_ORDER_DETAILS");
            var result = new AgentResultEvent(new AssistantMessage("SENSITIVE_ANSWER"));
            var events =
                    middleware
                            .onAgent(
                                    null,
                                    ctx,
                                    new AgentInput(List.of(user)),
                                    input ->
                                            middleware.onModelCall(
                                                    null,
                                                    ctx,
                                                    new ModelCallInput(
                                                            List.of(user), List.of(), null, null),
                                                    ignored -> Flux.<AgentEvent>just(result)))
                            .collectList()
                            .block(Duration.ofSeconds(5));
            assertThat(events).containsExactly(result);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(records)
                    .extracting(JevExecution.Record::purpose)
                    .contains(
                            "task_supervision",
                            "supervision.advice",
                            "supervision.core.completion__implementation_complete");
            assertThat(records.toString()).doesNotContain("SENSITIVE");
            assertThat(records.get(1).recommendation())
                    .containsEntry("advice", "REQUEST_VERIFICATION");
        } finally {
            support.close();
        }
    }

    @Test
    void ScheduleAndThresholdChangesAffectBuildIdentity() {
        var first = new SessionAgentBuildSpec(1, "env", null, CONFIG, null, null);
        var second =
                new SessionAgentBuildSpec(
                        1,
                        "env",
                        null,
                        CONFIG.replace("\"version\":", "\"maxAssessments\":2,\"version\":"),
                        null,
                        null);
        var third =
                new SessionAgentBuildSpec(1, "env", null, CONFIG.replace("0.8", "0.9"), null, null);
        assertThat(first.cacheSuffix())
                .isNotEqualTo(second.cacheSuffix())
                .isNotEqualTo(third.cacheSuffix());
    }
}
