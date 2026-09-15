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
package io.agentscope.core.model;

import java.time.Duration;
import java.util.function.Predicate;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

/**
 * Centralized builder that turns an {@link ExecutionConfig} into a Reactor
 * {@link RetryBackoffSpec}, honouring <em>every</em> configured backoff knob.
 *
 * <p>This helper deduplicates the retry-spec construction that was previously
 * inlined (and had drifted out of sync) in {@link ModelUtils}, the tool
 * executor and the embedding utilities. It also fixes a long-standing "dead
 * field" bug: {@link ExecutionConfig#getBackoffMultiplier() backoffMultiplier}
 * was exposed as a configurable option but was never actually applied &mdash;
 * the inline chains used {@link Retry#backoff(long, Duration)} whose built-in
 * multiplier defaults to {@code 2}, silently ignoring any user-supplied value.
 * The fix delegates to {@link RetryBackoffSpec#multiplier(double)} so the
 * configured multiplier finally takes effect (defaulting to {@code 2.0} for
 * backward compatibility).
 *
 * <p><b>Null-knob defaults</b> (preserved from the previous inline code so this
 * refactoring is behaviour-neutral for existing call sites):
 *
 * <ul>
 *   <li>{@code initialBackoff} unset &rarr; 1 second
 *   <li>{@code maxBackoff} unset &rarr; 10 seconds
 *   <li>{@code retryOn} unset &rarr; retry all errors
 *   <li>{@code backoffMultiplier} unset &rarr; {@code 2.0} (Reactor default)
 * </ul>
 *
 * <p>Callers are responsible for the retry-enabled guard (i.e.
 * {@code maxAttempts != null && maxAttempts > 1}); this builder assumes retry
 * is enabled and does not re-check that condition. The returned spec may be
 * further customised, e.g. with
 * {@link RetryBackoffSpec#doBeforeRetry(java.util.function.Consumer)} for
 * context-specific retry logging.
 */
public final class RetrySpecs {

    /** Default exponential multiplier, matching Reactor's {@link Retry#backoff(long, Duration)}. */
    static final double DEFAULT_MULTIPLIER = 2.0d;

    /** Default first-backoff duration used when {@link ExecutionConfig#getInitialBackoff()} is null. */
    static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofSeconds(1);

    /** Default backoff cap used when {@link ExecutionConfig#getMaxBackoff()} is null. */
    static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);

    private RetrySpecs() {
        // Utility class, no instantiation.
    }

    /**
     * Build a {@link RetryBackoffSpec} from the given {@link ExecutionConfig}, applying the
     * standard defaults for any unset backoff knob and ensuring the configured
     * {@link ExecutionConfig#getBackoffMultiplier() multiplier} is propagated to Reactor.
     *
     * <p>The resulting spec uses jitter {@code 0.5} (same as the previous inline
     * implementation) and is ready to be passed to
     * {@link reactor.core.publisher.Flux#retryWhen(Retry)} /
     * {@link reactor.core.publisher.Mono#retryWhen(Retry)}.
     *
     * @param config the execution configuration (must not be {@code null} and must carry a
     *     {@code maxAttempts > 1}; the caller is responsible for the retry-enabled guard)
     * @return a configured {@link RetryBackoffSpec}; callers may further attach
     *     {@link RetryBackoffSpec#doBeforeRetry(java.util.function.Consumer)} for logging
     */
    public static RetryBackoffSpec build(ExecutionConfig config) {
        Duration initialBackoff = config.getInitialBackoff();
        if (initialBackoff == null) {
            initialBackoff = DEFAULT_INITIAL_BACKOFF;
        }

        Duration maxBackoff = config.getMaxBackoff();
        if (maxBackoff == null) {
            maxBackoff = DEFAULT_MAX_BACKOFF;
        }

        Predicate<Throwable> retryOn = config.getRetryOn();
        if (retryOn == null) {
            retryOn = error -> true; // retry all errors by default
        }

        Double multiplier = config.getBackoffMultiplier();
        double effectiveMultiplier = multiplier != null ? multiplier : DEFAULT_MULTIPLIER;

        return Retry.backoff(config.getMaxAttempts() - 1L, initialBackoff)
                .maxBackoff(maxBackoff)
                .multiplier(effectiveMultiplier)
                .jitter(0.5d)
                .filter(retryOn);
    }
}
