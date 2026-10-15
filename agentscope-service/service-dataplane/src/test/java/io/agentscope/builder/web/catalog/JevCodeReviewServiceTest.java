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
import io.agentscope.examples.jev.JevCodeReviewExample;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewTool;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class JevCodeReviewServiceTest {
    static final String CONFIG =
            """
            {"jev":{"review":{"mode":"SHADOW","threshold":0.7,"rejectionThreshold":0.2,
            "minConfidence":0.55,"routeSeverity":1.5,"blockingSeverity":2,"version":"review-v1"}}}
            """;

    @Test
    void disabledDoesNotReadKeysAndConfigurationIsValidatedBeforeClientCreation() {
        try (var support =
                new JevServiceSupport(
                        "",
                        () -> {
                            throw new AssertionError("client must not be created");
                        })) {
            assertThat(support.middlewares(CONFIG)).isEmpty(); // tool, not a middleware
            assertThat(support.reviewTool("{\"jev\":{\"review\":{\"mode\":\"OFF\"}}}")).isEmpty();
            for (String invalid :
                    List.of(
                            CONFIG.replace("SHADOW", "ENFORCE"),
                            CONFIG.replace("0.7", "0.1"),
                            CONFIG.replace("\"minConfidence\":0.55,", ""),
                            CONFIG.replace("\"version\":", "\"apiKey\":\"forbidden\",\"version\":"),
                            CONFIG.replace("\"version\":", "\"maxRequests\":0,\"version\":")))
                assertThatThrownBy(() -> support.reviewTool(invalid))
                        .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void toolUsesRunScopedEvidenceAndMetadataOnlyTracing() throws Exception {
        JevClient client = mock(JevClient.class);
        when(client.systemOne(any()))
                .thenAnswer(call -> JevCodeReviewExample.syntheticAnswers(call.getArgument(0)));
        try (var support = new JevServiceSupport("", () -> client)) {
            var records = new CopyOnWriteArrayList<JevExecution.Record>();
            var latch = new CountDownLatch(1);
            var ctx =
                    RuntimeContext.builder()
                            .userId("owner")
                            .sessionId("review-session")
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
            var result =
                    support.reviewTool(CONFIG)
                            .orElseThrow()
                            .review("authorized", ctx)
                            .block(Duration.ofSeconds(5));
            assertThat(result.status()).isEqualTo(JevExecution.Status.DECIDED);
            assertThat(result.value().findings()).hasSize(1);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(records.get(0).purpose()).isEqualTo("code_review");
            assertThat(records.get(0).recommendation()).containsEntry("findings", "1");
            assertThat(records.toString())
                    .doesNotContain("return record", "Forbidden()", "src/auth.py");
        }
    }

    @Test
    void limitsParticipateInBuildCacheIdentity() {
        var first = new SessionAgentBuildSpec(1, "env", null, CONFIG, null, null);
        var second =
                new SessionAgentBuildSpec(
                        1,
                        "env",
                        null,
                        CONFIG.replace("\"version\":", "\"maxFollowUps\":0,\"version\":"),
                        null,
                        null);
        assertThat(first.cacheSuffix()).isNotEqualTo(second.cacheSuffix());
    }
}
