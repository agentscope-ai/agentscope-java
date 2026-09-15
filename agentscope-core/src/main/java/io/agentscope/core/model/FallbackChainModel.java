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
package io.agentscope.core.model;

import io.agentscope.core.agent.config.FailoverListener;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * A pluggable {@link Model} wrapper that tries a chain of candidate models in order.
 *
 * <p>The first candidate is the primary model; on a failure that switching can plausibly
 * recover from (rate limit, server error, timeout, network error, or an auth failure such as
 * 401/403), the wrapper transparently retries the request against the next candidate in the
 * chain. Request-side failures (e.g. HTTP 400/422) are guaranteed to fail identically against
 * every candidate, so they are propagated immediately without consuming the fallback chain.
 *
 * <p>Each candidate keeps its own cooldown state: after a failure it is skipped for
 * {@code cooldown} (default {@link #DEFAULT_COOLDOWN}), then automatically becomes eligible
 * again (lazy recovery — no scheduler or background threads; recovery is verified by real
 * traffic). Cooldown entries are keyed by model identity (name + instance) and expired entries
 * are evicted on write, so the table stays bounded and candidates that merely share a name do
 * not cool each other down.
 *
 * <p>Mid-stream failures (after at least one chunk was delivered) are deliberately
 * <b>not</b> retried against a fallback: switching mid-response can duplicate already-delivered
 * content. Such a failure is recorded (cooldown is still applied) and propagated as-is.
 *
 * <p>Switches are observable through an optional {@link FailoverListener} (same contract as
 * {@code ReActAgent.Builder.failoverListener}): it is invoked synchronously at each switch site
 * with the failed candidate and the triggering error. Because the chain may serve concurrent
 * calls, this wrapper holds no per-call mutable state: capability queries
 * ({@link #getModelName()}, {@link #supportsNativeStructuredOutput()},
 * {@link #supportsNativeStructuredOutputWithTools()}, {@link #getContextWindowSize()}) report
 * the primary model's values — the chain's stable identity — and the resolved active candidate
 * never leaks across calls. Use the {@link FailoverListener} (or the warn logs) to observe
 * which candidate served a particular call.
 *
 * <p>This class is the framework-side building block for multi-level model fallback. Users can
 * wire it directly via {@code ReActAgent.Builder.model(...)} or through the builder convenience
 * switch {@code ReActAgent.Builder.fallbackModels(...)}.
 */
public class FallbackChainModel implements Model {

    /** Default cooldown window applied to a candidate after a switchable failure. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(FallbackChainModel.class);

    private final List<Model> candidates;
    private final Model primary;
    private final Duration cooldown;
    private final ConcurrentHashMap<String, Long> coolUntilMillis;
    private final FailoverListener failoverListener;

    /**
     * Creates a fallback chain with the default cooldown.
     *
     * @param primary the primary model (must not be null)
     * @param fallbacks ordered fallback models; may be null or empty
     * @throws NullPointerException if {@code primary} is null
     */
    public FallbackChainModel(Model primary, List<Model> fallbacks) {
        this(primary, fallbacks, DEFAULT_COOLDOWN);
    }

    /**
     * Creates a fallback chain with a custom cooldown window.
     *
     * @param primary the primary model (must not be null)
     * @param fallbacks ordered fallback models; may be null or empty
     * @param cooldown cooldown applied to each candidate after a switchable failure; {@code null}
     *     falls back to {@link #DEFAULT_COOLDOWN}
     * @throws NullPointerException if {@code primary} is null
     */
    public FallbackChainModel(Model primary, List<Model> fallbacks, Duration cooldown) {
        this(primary, fallbacks, cooldown, new ConcurrentHashMap<>(), null);
    }

    /**
     * Creates a fallback chain sharing an existing cooldown table.
     *
     * <p>The shared table keeps cooldown state alive across multiple {@link #stream} calls (the
     * many reasoning rounds of an agent loop, or successive requests): a candidate that failed in
     * one call is skipped by every caller until its window expires. Passing a fresh map per
     * wrapper bounds cooldown to a single stream call.
     *
     * @param primary the primary model (must not be null)
     * @param fallbacks ordered fallback models; may be null or empty
     * @param cooldown cooldown applied to each candidate after a switchable failure; {@code null}
     *     falls back to {@link #DEFAULT_COOLDOWN}
     * @param sharedCoolUntilMillis shared cooldown table (modifiable, not null)
     * @throws NullPointerException if {@code primary} or {@code sharedCoolUntilMillis} is null
     */
    public FallbackChainModel(
            Model primary,
            List<Model> fallbacks,
            Duration cooldown,
            ConcurrentHashMap<String, Long> sharedCoolUntilMillis) {
        this(primary, fallbacks, cooldown, sharedCoolUntilMillis, null);
    }

    /**
     * Creates a fallback chain with a cooldown table and a failover listener.
     *
     * @param primary the primary model (must not be null)
     * @param fallbacks ordered fallback models; may be null or empty
     * @param cooldown cooldown applied to each candidate after a switchable failure; {@code null}
     *     falls back to {@link #DEFAULT_COOLDOWN}
     * @param sharedCoolUntilMillis shared cooldown table (modifiable, not null)
     * @param failoverListener listener notified at each switch site (may be null)
     * @throws NullPointerException if {@code primary} or {@code sharedCoolUntilMillis} is null
     */
    public FallbackChainModel(
            Model primary,
            List<Model> fallbacks,
            Duration cooldown,
            ConcurrentHashMap<String, Long> sharedCoolUntilMillis,
            FailoverListener failoverListener) {
        if (primary == null) {
            throw new NullPointerException("primary model must not be null");
        }
        if (sharedCoolUntilMillis == null) {
            throw new NullPointerException("sharedCoolUntilMillis must not be null");
        }
        List<Model> chain = new ArrayList<>();
        chain.add(primary);
        if (fallbacks != null) {
            for (Model fallback : fallbacks) {
                if (fallback != null) {
                    chain.add(fallback);
                }
            }
        }
        this.candidates = List.copyOf(chain);
        this.primary = primary;
        this.cooldown = cooldown != null ? cooldown : DEFAULT_COOLDOWN;
        this.coolUntilMillis = sharedCoolUntilMillis;
        this.failoverListener = failoverListener;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return attempt(0, null, 0, messages, tools, options);
    }

    private Flux<ChatResponse> attempt(
            int index,
            Throwable lastFailure,
            int cooldownSkips,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        if (index >= candidates.size()) {
            Throwable error =
                    lastFailure != null
                            ? lastFailure
                            : new ModelException(
                                    cooldownSkips > 0
                                            ? "All "
                                                    + cooldownSkips
                                                    + " fallback candidates are in cooldown;"
                                                    + " nothing was attempted this call"
                                            : "All model candidates in the fallback chain failed");
            return Flux.error(error);
        }

        Model candidate = candidates.get(index);
        String candidateKey = candidateKey(candidate);

        if (isCooling(candidateKey)) {
            return attempt(index + 1, lastFailure, cooldownSkips + 1, messages, tools, options);
        }

        Flux<ChatResponse> candidateFlux = candidate.stream(messages, tools, options);

        return candidateFlux.switchOnFirst(
                (signal, flux) -> {
                    if (signal.isOnError()) {
                        Throwable error = signal.getThrowable();
                        return switch (classify(error)) {
                            case REQUEST_SIDE -> {
                                // This failure will repeat identically on every candidate.
                                yield Flux.error(error);
                            }
                            case SWITCHABLE -> {
                                recordFailure(candidateKey);
                                if (index + 1 < candidates.size()) {
                                    Model next = candidates.get(index + 1);
                                    LOG.warn(
                                            "Model {} failed ({}), switching to fallback candidate"
                                                    + " {}",
                                            candidate.getModelName(),
                                            error.getMessage(),
                                            next.getModelName(),
                                            error);
                                    notifyFailover(candidate, error);
                                } else {
                                    LOG.warn(
                                            "Fallback chain exhausted after candidate {} failed"
                                                    + " ({})",
                                            candidate.getModelName(),
                                            error.getMessage(),
                                            error);
                                    notifyFailover(candidate, error);
                                }
                                yield attempt(
                                        index + 1, error, cooldownSkips, messages, tools, options);
                            }
                        };
                    }
                    // First signal delivered OK — forward the remainder of the stream.
                    return flux.onErrorResume(
                            midStreamError -> {
                                // Mid-stream failure: do not switch (content may already have
                                // been delivered); record the cooldown and propagate as-is.
                                recordFailure(candidateKey);
                                return Flux.error(midStreamError);
                            });
                });
    }

    /** Skips candidates currently inside their cooldown window. */
    private boolean isCooling(String key) {
        Long coolUntil = coolUntilMillis.get(key);
        return coolUntil != null && coolUntil > System.currentTimeMillis();
    }

    /**
     * Marks a candidate as cooling for {@link #cooldown}. Expired entries are evicted on write so
     * the table stays bounded over the lifetime of a long-lived agent.
     */
    private void recordFailure(String key) {
        long now = System.currentTimeMillis();
        if (coolUntilMillis.size() > 0 && !coolUntilMillis.isEmpty()) {
            coolUntilMillis.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        coolUntilMillis.put(key, now + cooldown.toMillis());
    }

    /**
     * Notifies the failover listener at a switch site. An exception thrown by the listener is
     * contained: it is logged and never affects the switch or the fallback call that follows
     * (same contract as {@code ReActAgent}'s legacy failover path).
     */
    private void notifyFailover(Model failedCandidate, Throwable error) {
        if (failoverListener == null) {
            return;
        }
        try {
            failoverListener.onFailover(failedCandidate, error);
        } catch (Exception e) {
            LOG.warn("Failover listener threw an exception, ignoring", e);
        }
    }

    /**
     * Cooldown key: model name plus instance identity. Two candidates that expose the same model
     * name behind different endpoints/keys are distinct instances and must not cool each other
     * down; the instance identity keeps their entries separate.
     */
    private static String candidateKey(Model model) {
        return model.getModelName() + '@' + System.identityHashCode(model);
    }

    /**
     * Failure classification for fallback switching.
     *
     * <p>{@code SWITCHABLE}: another candidate may succeed — rate limit (429), server error
     * (5xx), timeout, network/IO error, or auth failure (401/403, where switching to a different
     * key/model can recover). Includes unknown errors, mirroring the conservative existing
     * "switch on first error" behaviour.
     *
     * <p>{@code REQUEST_SIDE}: the request itself is invalid (400/422 and other 4xx) and will
     * fail identically on every candidate, so switching is pointless.
     */
    private static FailureCategory classify(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpTransportException hte) {
                Integer status = hte.getStatusCode();
                if (status != null) {
                    return statusCategory(status);
                }
                // No status code: connection-level errors are switchable.
                return FailureCategory.SWITCHABLE;
            }
            if (current instanceof ModelHttpException mhe) {
                Integer status = mhe.getStatusCode();
                if (status != null) {
                    return statusCategory(status);
                }
                return FailureCategory.SWITCHABLE;
            }
            if (current instanceof TimeoutException || current instanceof IOException) {
                return FailureCategory.SWITCHABLE;
            }
            current = current.getCause();
        }
        // Unknown error types: be conservative and try the next candidate (same behaviour as
        // the original single-fallback switchOnFirst implementation).
        return FailureCategory.SWITCHABLE;
    }

    private static FailureCategory statusCategory(int status) {
        if (status >= 400 && status < 500 && status != 401 && status != 403 && status != 429) {
            // Request-side 4xx (400/404/422...) — switching cannot help.
            return FailureCategory.REQUEST_SIDE;
        }
        // 401/403/429/5xx and everything else — switching may help.
        return FailureCategory.SWITCHABLE;
    }

    @Override
    public String getModelName() {
        return primary.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return primary.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return primary.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return primary.getContextWindowSize();
    }

    private enum FailureCategory {
        SWITCHABLE,
        REQUEST_SIDE
    }
}
