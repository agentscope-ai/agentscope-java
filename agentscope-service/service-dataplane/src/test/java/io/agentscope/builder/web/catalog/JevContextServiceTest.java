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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.builder.web.managed.SessionAgentBuildSpec;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.jev.JevContextCompactionExample;
import io.agentscope.examples.jev.JevTraceEvaluationExample;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.context.FileJevContextArchive;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactionStrategy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JevContextServiceTest {
    @TempDir Path directory;
    static final String CONFIG =
            """
            {"jev":{"compaction":{"mode":"SHADOW","threshold":0.8,"rejectionThreshold":0.2,
            "eligibleTools":["read"],"preserveRecentMessages":1}}}
            """;

    @Test
    void offRequiresNoCredentialsAndConfigurationIsPartOfCacheIdentity() {
        try (var support =
                new JevServiceSupport(
                        "",
                        () -> {
                            throw new AssertionError("OFF must not create client");
                        })) {
            assertTrue(support.compaction(null, null).isEmpty());
            assertTrue(
                    support.compaction("{\"jev\":{\"compaction\":{\"mode\":\"OFF\"}}}", null)
                            .isEmpty());
            assertTrue(support.middlewares(CONFIG).isEmpty());
            var first = new SessionAgentBuildSpec(1, "local", null, CONFIG, null, null);
            var changed =
                    new SessionAgentBuildSpec(
                            1, "local", null, CONFIG.replace("0.8", "0.9"), null, null);
            assertTrue(!first.equals(changed));
        }
    }

    @Test
    void unsafeOverridesAndMissingThresholdsAreRejected() {
        try (var support = new JevServiceSupport("", () -> mock(JevClient.class))) {
            for (String value :
                    List.of(
                            CONFIG.replace("\"eligibleTools\"", "\"apiKey\""),
                            CONFIG.replace("\"threshold\":0.8,", ""),
                            CONFIG.replace("\"eligibleTools\"", "\"archivePath\""),
                            CONFIG.replace("0.8", "0.1")))
                assertThrows(IllegalArgumentException.class, () -> support.compaction(value, null));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> support.compaction(CONFIG.replace("SHADOW", "ENFORCE"), null));
        }
    }

    @Test
    void shadowStrategyUsesScopedServiceObservationWithoutApplyingSuggestion() throws Exception {
        var client = mock(JevClient.class);
        when(client.systemOne(any()))
                .thenAnswer(
                        call -> JevTraceEvaluationExample.syntheticAnswers(call.getArgument(0)));
        try (var support = new JevServiceSupport("", () -> client)) {
            List<JevExecution.Record> records = new ArrayList<>();
            CountDownLatch arrived = new CountDownLatch(1);
            var rc =
                    RuntimeContext.builder()
                            .userId("owner")
                            .sessionId("session")
                            .put(
                                    JevServiceSupport.TraceSink.class,
                                    new JevServiceSupport.TraceSink(
                                            record -> {
                                                records.add(record);
                                                arrived.countDown();
                                            }))
                            .build();
            var config =
                    support.compaction(CONFIG, new FileJevContextArchive(directory, 1_000_000))
                            .orElseThrow();
            assertTrue(
                    config.getStrategy()
                            .compact(
                                    new ConversationCompactionStrategy.Request(
                                            rc,
                                            JevContextCompactionExample.history(),
                                            "agent",
                                            "session",
                                            100000))
                            .block()
                            .isEmpty());
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            assertEquals("context_compaction", records.get(0).purpose());
            assertTrue(!records.get(0).toString().contains("obsolete file contents"));
        }
    }
}
