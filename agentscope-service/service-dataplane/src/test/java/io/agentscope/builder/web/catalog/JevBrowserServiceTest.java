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
import io.agentscope.examples.jev.JevBrowserExample;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JevBrowserServiceTest {
    static final String CONFIG =
            "{\"jev\":{\"browser\":{\"mode\":\"ENFORCE\",\"threshold\":0.8,\"version\":\"browser-v1\"}}}";

    @Test
    void defaultsOffAndRejectsSecretsUrlsAndInvalidBoundsBeforeClient() {
        try (var support =
                new JevServiceSupport(
                        "",
                        () -> {
                            throw new AssertionError("no client");
                        })) {
            assertThat(support.browserTool(null)).isEmpty();
            assertThat(support.middlewares(CONFIG)).isEmpty();
            assertThat(support.browserTool(CONFIG.replace("ENFORCE", "OFF"))).isEmpty();
            for (String bad :
                    List.of(
                            CONFIG.replace("0.8", "1.1"),
                            CONFIG.replace("\"threshold\":0.8,", ""),
                            CONFIG.replace("\"version\":", "\"maxSteps\":0,\"version\":"),
                            CONFIG.replace(
                                    "\"version\":",
                                    "\"url\":\"https://untrusted.example\",\"version\":"),
                            CONFIG.replace("\"version\":", "\"apiKey\":\"bad\",\"version\":")))
                assertThatThrownBy(() -> support.browserTool(bad))
                        .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void toolRequiresHostSourceAndTraceOmitsPageContent() throws Exception {
        var client = mock(JevClient.class);
        when(client.systemOne(any()))
                .thenAnswer(c -> JevBrowserExample.syntheticAnswers(c.getArgument(0)));
        try (var support = new JevServiceSupport("", () -> client)) {
            var actions = new AtomicInteger();
            var closures = new AtomicInteger();
            var records = new CopyOnWriteArrayList<JevExecution.Record>();
            var latch = new CountDownLatch(3);
            var ctx =
                    RuntimeContext.builder()
                            .userId("u")
                            .sessionId("s")
                            .put(
                                    JevBrowserSession.Source.class,
                                    new JevBrowserSession.Source(
                                            req ->
                                                    new JevBrowserExample.ScriptedSession(
                                                            JevBrowserExample.scope(req.context()),
                                                            actions,
                                                            closures),
                                            JevBrowserExample::verify))
                            .put(
                                    JevServiceSupport.TraceSink.class,
                                    new JevServiceSupport.TraceSink(
                                            r -> {
                                                records.add(r);
                                                latch.countDown();
                                            }))
                            .build();
            var tool = support.browserTool(CONFIG).orElseThrow();
            var result = tool.read(JevBrowserExample.GOAL, ctx).block(Duration.ofSeconds(5));
            assertThat(result.status()).isEqualTo("VERIFIED");
            assertThat(actions.get()).isEqualTo(1);
            assertThat(closures.get()).isEqualTo(1);
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(records).anyMatch(r -> r.purpose().equals("browser_task"));
            assertThat(records.toString())
                    .doesNotContain("warranty", "fixture.test", "Find the standard");
            assertThatThrownBy(
                            () ->
                                    tool.read(
                                                    "goal",
                                                    RuntimeContext.builder()
                                                            .userId("u")
                                                            .sessionId("s")
                                                            .build())
                                            .block())
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
