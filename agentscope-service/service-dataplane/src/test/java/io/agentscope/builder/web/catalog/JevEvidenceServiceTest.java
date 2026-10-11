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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.jev.JevEvidenceExample;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class JevEvidenceServiceTest {
    static final String CONFIG =
            """
            {"jev":{"retrieval":{"mode":"SHADOW","rejectionThreshold":0.2,
            "injectionThreshold":0.7,"contradictionThreshold":0.7,
            "relevanceThreshold":0.45,"evidenceThreshold":0.55,"version":"evidence-v1"}}}
            """;

    @Test
    void defaultsOffAndRejectsInvalidConfigurationBeforeCreatingAClient() {
        try (var support =
                new JevServiceSupport(
                        "",
                        () -> {
                            throw new AssertionError("no client");
                        })) {
            assertThat(support.evidenceTool(null)).isEmpty();
            assertThat(support.middlewares(CONFIG)).isEmpty();
            assertThat(support.evidenceTool("{\"jev\":{\"retrieval\":{\"mode\":\"OFF\"}}}"))
                    .isEmpty();
            for (String bad :
                    List.of(
                            CONFIG.replace("0.2", "0.8"),
                            CONFIG.replace("\"evidenceThreshold\":0.55,", ""),
                            CONFIG.replace("\"version\":", "\"topK\":1000,\"version\":"),
                            CONFIG.replace("\"version\":", "\"apiKey\":\"bad\",\"version\":")))
                assertThatThrownBy(() -> support.evidenceTool(bad))
                        .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void scopesEvidenceAndRecordsMetadataWithoutQueryOrPassageContent() throws Exception {
        var client = mock(JevClient.class);
        when(client.systemOne(any()))
                .thenAnswer(call -> JevEvidenceExample.syntheticAnswers(call.getArgument(0)));
        try (var support = new JevServiceSupport("", () -> client)) {
            var records = new CopyOnWriteArrayList<JevExecution.Record>();
            var latch = new CountDownLatch(1);
            var context =
                    RuntimeContext.builder()
                            .userId("u")
                            .sessionId("s")
                            .put(
                                    JevEvidenceTool.Source.class,
                                    new JevEvidenceTool.Source(
                                            req -> Mono.just(JevEvidenceExample.passages()),
                                            (ctx, p) -> !p.id().equals("private")))
                            .put(
                                    JevServiceSupport.TraceSink.class,
                                    new JevServiceSupport.TraceSink(
                                            r -> {
                                                records.add(r);
                                                latch.countDown();
                                            }))
                            .build();
            var result =
                    support.evidenceTool(CONFIG.replace("SHADOW", "ENFORCE"))
                            .orElseThrow()
                            .search(JevEvidenceExample.QUESTION, context)
                            .block(Duration.ofSeconds(5));
            assertThat(result.passages())
                    .singleElement()
                    .satisfies(
                            p -> {
                                assertThat(p.id()).isEqualTo("policy-7");
                                assertThat(p.classification()).isEqualTo("CONFLICTING");
                            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(records.get(0).purpose()).isEqualTo("evidence_retrieval");
            assertThat(records.toString())
                    .doesNotContain("Refunds", "policy-7", "private-source-marker");
        }
    }
}
