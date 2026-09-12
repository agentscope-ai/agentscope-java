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

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
 * {@code cooldown} (default {@link #DEFAULT_COOLDOWN}), then automatically
 * becomes eligible again (lazy recovery — no scheduler or background threads; recovery is
 * verified by real traffic). This prevents a persistently broken model from being hammered on
 * every request while keeping the implementation fully in-memory and side-effect free.
 *
 * <p>Mid-stream failures (after at least one chunk was delivered) are deliberately
 * <b>not</b> retried against a fallback: switching mid-response can duplicate already-delivered
 * content. Such a failure is recorded (cooldown is still applied) and propagated as-is.
 *
 * <p>Type-level capabilities ({@link #getModelName()}, {@link #supportsNativeStructuredOutput()},
 * {@link #supportsNativeStructuredOutputWithTools()}, {@link #getContextWindowSize()}) delegate to
 * the currently active candidate.
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
    private final Duration cooldown;
    private final ConcurrentHashMap<String, Long> coolUntilMillis;
    private final AtomicReference<Model> activeModel;

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
        this(primary, fallbacks, cooldown, new ConcurrentHashMap<>());
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
        this.cooldown = cooldown != null ? cooldown : DEFAULT_COOLDOWN;
        this.coolUntilMillis = sharedCoolUntilMillis;
        this.activeModel = new AtomicReference<>(primary);
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return attempt(0, null, messages, tools, options);
    }

    private Flux<ChatResponse> attempt(
            int index,
            Throwable lastFailure,
            List<Msg> messages,
            List<ToolSchema> tools,
            GenerateOptions options) {
        if (index >= candidates.size()) {
            Throwable error =
                    lastFailure != null
                            ? lastFailure
                            : new ModelException(
                                    "All model candidates in the fallback chain failed");
            return Flux.error(error);
        }

        Model candidate = candidates.get(index);
        String candidateName = candidate.getModelName();

        if (isCooling(candidateName)) {
            return attempt(index + 1, lastFailure, messages, tools, options);
        }

        activeModel.set(candidate);
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
                                recordFailure(candidateName);
                                LOG.warn(
                                        "Model {} failed ({}), switching to fallback candidate {}",
                                        candidateName,
                                        error.getMessage(),
                                        candidates
                                                .get(Math.min(index + 1, candidates.size() - 1))
                                                .getModelName(),
                                        error);
                                yield attempt(index + 1, error, messages, tools, options);
                            }
                        };
                    }
                    // First signal delivered OK — forward the remainder of the stream.
                    return flux.onErrorResume(
                            midStreamError -> {
                                // Mid-stream failure: do not switch (content may already have
                                // been delivered); record the cooldown and propagate as-is.
                                recordFailure(candidateName);
                                return Flux.error(midStreamError);
                            });
                });
    }

    /** Skips candidates currently inside their cooldown window. */
    private boolean isCooling(String modelName) {
        Long coolUntil = coolUntilMillis.get(modelName);
        return coolUntil != null && coolUntil > System.currentTimeMillis();
    }

    /** Marks a candidate as cooling for {@link #cooldown}. */
    private void recordFailure(String modelName) {
        coolUntilMillis.put(modelName, System.currentTimeMillis() + cooldown.toMillis());
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
        return activeModel.get().getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return activeModel.get().supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return activeModel.get().supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return activeModel.get().getContextWindowSize();
    }

    private enum FailureCategory {
        SWITCHABLE,
        REQUEST_SIDE
    }
}
