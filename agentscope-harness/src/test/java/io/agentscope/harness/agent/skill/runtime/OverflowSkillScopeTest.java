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
package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.context.ContextItem;
import io.agentscope.harness.agent.middleware.ContextConventionsMiddleware;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OverflowSkillScopeTest {
    private final Agent owner = mock(Agent.class);
    private final ContextConventionsMiddleware middleware = new ContextConventionsMiddleware();

    @Test
    void retryFreezesCapturedHolderAndIsolatesLateToolsAndConsumesSeedOnce() {
        RuntimeContext actual =
                RuntimeContext.builder().sessionId("actual").put("business", "kept").build();
        RuntimeContext fallback = RuntimeContext.empty();
        AtomicReference<SkillRuntime.OverflowRetryScope> captured = new AtomicReference<>();
        SkillRuntime.withOverflowRetryScope(
                        owner,
                        scope -> {
                            captured.set(scope);
                            return middleware
                                    .onSystemPrompt(owner, actual, "")
                                    .flatMap(
                                            ignored -> {
                                                SkillRuntime.registerLoadedBody(
                                                        actual, "alpha", "alpha body");
                                                RuntimeContext oldTool =
                                                        RuntimeContext.builder(actual).build();
                                                List<ContextItem> frozen =
                                                        SkillRuntime.loadedBodies(actual);
                                                // A later replacement of the typed key must not
                                                // replace the captured holder.
                                                SkillRuntime.resetLoadedBodies(actual);
                                                SkillRuntime.registerLoadedBody(
                                                        actual,
                                                        "replacement",
                                                        "unrelated replacement");
                                                assertSame(actual, scope.captureForRetry(fallback));
                                                SkillRuntime.registerLoadedBody(
                                                        oldTool, "late", "late old result");
                                                RuntimeContext retry =
                                                        RuntimeContext.builder(actual).build();
                                                return middleware
                                                        .onSystemPrompt(owner, retry, "")
                                                        .flatMap(
                                                                prompt -> {
                                                                    assertEquals(
                                                                            frozen,
                                                                            SkillRuntime
                                                                                    .loadedBodies(
                                                                                            retry));
                                                                    assertEquals(
                                                                            "kept",
                                                                            retry.get("business"));
                                                                    SkillRuntime.registerLoadedBody(
                                                                            retry,
                                                                            "beta",
                                                                            "new retry result");
                                                                    assertEquals(
                                                                            2,
                                                                            SkillRuntime
                                                                                    .loadedBodies(
                                                                                            retry)
                                                                                    .size());
                                                                    assertEquals(
                                                                            2,
                                                                            SkillRuntime
                                                                                    .loadedBodies(
                                                                                            oldTool)
                                                                                    .size());
                                                                    assertEquals(
                                                                            1,
                                                                            SkillRuntime
                                                                                    .loadedBodies(
                                                                                            actual)
                                                                                    .size());
                                                                    RuntimeContext child =
                                                                            RuntimeContext.builder(
                                                                                            retry)
                                                                                    .build();
                                                                    return middleware
                                                                            .onSystemPrompt(
                                                                                    mock(
                                                                                            Agent
                                                                                                    .class),
                                                                                    child,
                                                                                    "")
                                                                            .then(
                                                                                    Mono.defer(
                                                                                            () -> {
                                                                                                assertTrue(
                                                                                                        SkillRuntime
                                                                                                                .loadedBodies(
                                                                                                                        child)
                                                                                                                .isEmpty());
                                                                                                assertEquals(
                                                                                                        2,
                                                                                                        SkillRuntime
                                                                                                                .loadedBodies(
                                                                                                                        retry)
                                                                                                                .size());
                                                                                                RuntimeContext
                                                                                                        again =
                                                                                                                RuntimeContext
                                                                                                                        .builder(
                                                                                                                                retry)
                                                                                                                        .build();
                                                                                                return middleware
                                                                                                        .onSystemPrompt(
                                                                                                                owner,
                                                                                                                again,
                                                                                                                "")
                                                                                                        .doOnNext(
                                                                                                                value ->
                                                                                                                        assertTrue(
                                                                                                                                SkillRuntime
                                                                                                                                        .loadedBodies(
                                                                                                                                                again)
                                                                                                                                        .isEmpty()));
                                                                                            }));
                                                                });
                                            });
                        })
                .block();
        assertSame(
                fallback,
                captured.get().captureForRetry(fallback),
                "terminal cleanup releases actual context");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureAndCancellationReleaseUnconsumedSeedAndResubscriptionStartsEmpty(boolean cancel) {
        RuntimeContext rc = RuntimeContext.builder().runId("same-run").build();
        RuntimeContext fallback = RuntimeContext.empty();
        AtomicReference<SkillRuntime.OverflowRetryScope> captured = new AtomicReference<>();
        Mono<String> execution =
                SkillRuntime.withOverflowRetryScope(
                        owner,
                        scope -> {
                            captured.set(scope);
                            return middleware
                                    .onSystemPrompt(owner, rc, "")
                                    .flatMap(
                                            ignored -> {
                                                assertTrue(SkillRuntime.loadedBodies(rc).isEmpty());
                                                SkillRuntime.registerLoadedBody(
                                                        rc, "alpha", "body");
                                                assertSame(rc, scope.captureForRetry(fallback));
                                                return cancel
                                                        ? Mono.never()
                                                        : Mono.error(
                                                                new IllegalStateException(
                                                                        "compaction failed"));
                                            });
                        });
        for (int attempt = 0; attempt < 2; attempt++) {
            if (cancel) StepVerifier.create(execution).thenCancel().verify();
            else StepVerifier.create(execution).verifyErrorMessage("compaction failed");
            assertSame(fallback, captured.get().captureForRetry(fallback));
        }
        SkillRuntime.withOverflowRetryScope(
                        owner,
                        scope -> {
                            assertSame(
                                    fallback,
                                    scope.captureForRetry(fallback),
                                    "no initialization cannot borrow a prior context");
                            return middleware
                                    .onSystemPrompt(owner, rc, "")
                                    .doOnNext(
                                            ignored ->
                                                    assertTrue(
                                                            SkillRuntime.loadedBodies(rc)
                                                                    .isEmpty()));
                        })
                .block();
    }
}
