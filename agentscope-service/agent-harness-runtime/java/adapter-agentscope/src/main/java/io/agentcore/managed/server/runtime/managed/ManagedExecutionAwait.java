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
package io.agentcore.managed.server.runtime.managed;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Bridge for synchronous direct callers; MQ ownership uses asynchronous completion. */
final class ManagedExecutionAwait {
    private ManagedExecutionAwait() {}

    static void run(Function<Mono<Throwable>, Mono<Void>> work) {
        var stop = Sinks.<Throwable>one();
        var completion = work.apply(stop.asMono()).toFuture();
        boolean interrupted = false;
        long deadline = 0;
        try {
            while (true) {
                try {
                    if (interrupted)
                        completion.get(
                                Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    else completion.get();
                    return;
                } catch (InterruptedException interruption) {
                    if (!interrupted) {
                        interrupted = true;
                        deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                        // Cancelling the subscription here would hide the original commit error
                        // and release ownership before usingWhen/recorder cleanup completes.
                        stop.tryEmitValue(
                                new ManagedTransportException(
                                        "Execution interrupted", interruption));
                    }
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof RuntimeException error) throw error;
                    if (failure.getCause() instanceof Error error) throw error;
                    throw new ManagedTransportException(
                            "Asynchronous execution failed", failure.getCause());
                } catch (TimeoutException delayed) {
                    completion.cancel(true);
                    throw new ManagedTransportException(
                            "Execution cancellation did not finish", delayed);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
