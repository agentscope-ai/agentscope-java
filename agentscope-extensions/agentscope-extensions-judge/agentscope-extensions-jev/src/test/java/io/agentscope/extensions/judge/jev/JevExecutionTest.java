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

package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevExecutionTest {
    @Test
    void disabledNeverInvokesOperation() {
        new JevExecution("test", JevExecution.Options.disabled())
                .execute(
                        RuntimeContext.empty(),
                        () -> {
                            throw new AssertionError();
                        })
                .block();
    }

    @Test
    void observerFailureDoesNotChangeDecision() {
        var runtime =
                new JevExecution(
                        "test",
                        new JevExecution.Options(
                                JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(1),
                                "v1",
                                (c, r) -> {
                                    throw new IllegalStateException();
                                }));
        assertEquals(
                "ok",
                runtime.execute(null, () -> Mono.just(JevExecution.Decision.decided("ok")))
                        .block()
                        .value());
    }

    @Test
    void failureDoesNotLeakExceptionText() {
        List<JevExecution.Record> records = new ArrayList<>();
        var runtime =
                new JevExecution(
                        "test",
                        new JevExecution.Options(
                                JevExecution.Mode.ENFORCE,
                                Duration.ofSeconds(1),
                                "v1",
                                (c, r) -> records.add(r)));
        assertEquals(
                JevExecution.Status.ERROR,
                runtime.execute(null, () -> Mono.error(new IllegalStateException("secret")))
                        .block()
                        .status());
        assertEquals("CALL_OR_RESPONSE_ERROR", records.get(0).reason());
        assertEquals(
                JevExecution.Status.ERROR, runtime.execute(null, Mono::empty).block().status());
    }

    @Test
    void timeoutIncludesRetryWait() {
        List<JevExecution.Record> records = new ArrayList<>();
        StepVerifier.withVirtualTime(
                        () ->
                                new JevExecution(
                                                "test",
                                                new JevExecution.Options(
                                                        JevExecution.Mode.SHADOW,
                                                        Duration.ofMillis(1500),
                                                        "v1",
                                                        (c, r) -> records.add(r)))
                                        .execute(
                                                null,
                                                () ->
                                                        Mono.<JevExecution.Decision<String>>error(
                                                                        new IllegalStateException())
                                                                .retryWhen(
                                                                        reactor.util.retry.Retry
                                                                                .fixedDelay(
                                                                                        5,
                                                                                        Duration
                                                                                                .ofSeconds(
                                                                                                        1)))))
                .thenAwait(Duration.ofMillis(1500))
                .assertNext(d -> assertEquals("TIMEOUT", d.reason()))
                .verifyComplete();
        assertEquals(1, records.size());
    }

    @Test
    void cancellationIsObservedAndPropagated() {
        AtomicBoolean cancelled = new AtomicBoolean();
        List<JevExecution.Record> records = new ArrayList<>();
        var runtime =
                new JevExecution(
                        "test",
                        new JevExecution.Options(
                                JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(2),
                                "v1",
                                (c, r) -> records.add(r)));
        StepVerifier.create(
                        runtime.execute(
                                null,
                                () ->
                                        Mono.<JevExecution.Decision<String>>never()
                                                .doOnCancel(() -> cancelled.set(true))))
                .thenCancel()
                .verify();
        assertTrue(cancelled.get());
        assertEquals(JevExecution.Status.CANCELLED, records.get(0).status());
    }
}
