/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.processor;

import io.agentscope.core.agui.model.RunAgentInput;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** Per-subscription ownership and serialized access to the synchronous resume store. */
final class AguiRunLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AguiRunLifecycle.class);
    private final AguiResumeCoordinator coordinator;
    private final RunAgentInput input;
    private boolean claimed;
    private boolean closed;

    AguiRunLifecycle(AguiResumeCoordinator coordinator, RunAgentInput input) {
        this.coordinator = coordinator;
        this.input = input;
    }

    AguiResumeCoordinator.ResumeContractResult begin() {
        return coordinator.beginRun(input, () -> claimed = true);
    }

    /**
     * Serialize setup and state writes with cleanup. In particular, cancellation must wait for
     * an in-flight claim to return before deciding whether this subscription owns a release.
     */
    <T> Mono<T> call(Callable<T> action) {
        return Mono.fromCallable(
                        () -> {
                            synchronized (this) {
                                return closed ? null : action.call();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    Mono<Void> finish(String phase, Throwable primaryError) {
        AtomicInteger attempts = new AtomicInteger();
        return Mono.<Void>fromRunnable(
                        () -> {
                            synchronized (this) {
                                closed = true;
                                if (!claimed) {
                                    return;
                                }
                                attempts.incrementAndGet();
                                coordinator.finishRun(input.getThreadId(), input.getRunId());
                                claimed = false;
                                if (attempts.get() > 1) {
                                    logger.info(
                                            "AG-UI release recovered: threadId={}, runId={},"
                                                    + " phase={}, attempts={}",
                                            input.getThreadId(),
                                            input.getRunId(),
                                            phase,
                                            attempts.get());
                                }
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(
                        Retry.backoff(2, Duration.ofMillis(50))
                                .maxBackoff(Duration.ofMillis(100))
                                .jitter(0)
                                .filter(RuntimeException.class::isInstance)
                                .doBeforeRetry(
                                        retry ->
                                                logger.warn(
                                                        "AG-UI release failed; retrying:"
                                                            + " threadId={}, runId={}, phase={},"
                                                            + " attempt={}",
                                                        input.getThreadId(),
                                                        input.getRunId(),
                                                        phase,
                                                        attempts.get(),
                                                        retry.failure()))
                                .onRetryExhaustedThrow((spec, retry) -> retry.failure()))
                .onErrorResume(
                        error -> {
                            if (primaryError != null && primaryError != error) {
                                primaryError.addSuppressed(error);
                            }
                            logger.error(
                                    "AG-UI release exhausted; owner may require recovery:"
                                            + " threadId={}, runId={}, phase={}, attempts={}",
                                    input.getThreadId(),
                                    input.getRunId(),
                                    phase,
                                    attempts.get(),
                                    error);
                            // Cleanup cannot retract events or report to a disconnected client.
                            // Keep its failure observable without replacing the primary failure.
                            return Mono.empty();
                        });
    }
}
