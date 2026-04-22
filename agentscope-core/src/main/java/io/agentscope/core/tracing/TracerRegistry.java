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

package io.agentscope.core.tracing;

import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * Registry for the global {@link Tracer} instance.
 * <p>
 * <b>Reactor global hook:</b> This class provides methods to enable or disable
 * a global Reactor
 * hook (via {@link #enableTracingHook()} and {@link #disableTracingHook()})
 * that propagates
 * tracing context across Reactor operators. Enabling this hook affects
 * <i>all</i> Reactor
 * operations in the JVM, which may have performance implications even for code
 * unrelated to tracing.
 * Use with care, and only enable the hook if you require tracing context
 * propagation for all
 * Reactor pipelines.
 */
public class TracerRegistry {
    private static final String HOOK_KEY = "agentscope-trace-context";
    private static volatile boolean hookEnabled = false;

    /**
     * Enables the global Reactor hook for tracing context propagation.
     * <p>
     * <b>Mechanism:</b> This registers a global hook using
     * {@link Hooks#onEachOperator(String, Function)}
     * that intercepts <i>every</i> Reactor operator creation involved in
     * {@code Flux} and {@code Mono}
     * chains. It wraps the {@code Subscriber} to ensure that downstream signals
     * ({@code onNext},
     * {@code onError}, {@code onComplete}) are processed within the tracing context
     * captured
     * at assembly time or from upstream.
     * <p>
     * <b>Activation:</b> The hook is active immediately after calling this method.
     * It affects all
     * subsequently assembled Reactor streams in the JVM.
     * <p>
     * <b>Performance:</b> Enabling this hook introduces overhead for every Reactor
     * operator
     * in the application, even those not strictly related to AgentScope tracing. It
     * involves
     * object allocation (wrapper subscribers) and context context
     * capturing/restoring.
     * It should be enabled only when automatic context propagation across
     * asynchronous boundaries
     * is required.
     * <p>
     * This method is safe to call multiple times; the hook will only be registered
     * once.
     */
    public static synchronized void enableTracingHook() {
        if (!hookEnabled) {
            Hooks.onEachOperator(
                    HOOK_KEY,
                    Operators.lift(
                            (scannable, subscriber) ->
                                    new CoreSubscriber<Object>() {
                                        @Override
                                        public void onSubscribe(Subscription s) {
                                            subscriber.onSubscribe(s);
                                        }

                                        @Override
                                        public void onNext(Object o) {
                                            TracerRegistry.get()
                                                    .runWithContext(
                                                            subscriber.currentContext(),
                                                            () -> {
                                                                subscriber.onNext(o);
                                                                return null;
                                                            });
                                        }

                                        @Override
                                        public void onError(Throwable t) {
                                            TracerRegistry.get()
                                                    .runWithContext(
                                                            subscriber.currentContext(),
                                                            () -> {
                                                                subscriber.onError(t);
                                                                return null;
                                                            });
                                        }

                                        @Override
                                        public void onComplete() {
                                            TracerRegistry.get()
                                                    .runWithContext(
                                                            subscriber.currentContext(),
                                                            () -> {
                                                                subscriber.onComplete();
                                                                return null;
                                                            });
                                        }

                                        @Override
                                        public Context currentContext() {
                                            return subscriber.currentContext();
                                        }
                                    }));
            hookEnabled = true;
        }
    }

    /**
     * Disables the global Reactor hook for tracing context propagation.
     * This removes the hook for all Reactor operators in the JVM.
     */
    public static synchronized void disableTracingHook() {
        if (hookEnabled) {
            Hooks.resetOnEachOperator(HOOK_KEY);
            hookEnabled = false;
        }
    }

    private static volatile Tracer tracer = new NoopTracer();

    public static void register(Tracer tracer) {
        TracerRegistry.tracer = tracer;
        if (tracer instanceof NoopTracer) {
            disableTracingHook();
        } else {
            enableTracingHook();
        }
    }

    public static Tracer get() {
        return tracer;
    }
}
