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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable key that uniquely identifies a sandbox state slot for a given
 * {@link IsolationScope}.
 *
 * <p>Use {@link #resolve} to obtain a key from a {@link RuntimeContext}. The result is
 * {@link Optional#empty()} when the required context field is absent (e.g., {@code USER} scope
 * without a {@code userId}), meaning state lookup should be skipped.
 */
public final class SandboxIsolationKey {

    private static final Logger log = LoggerFactory.getLogger(SandboxIsolationKey.class);

    static final String GLOBAL_VALUE = "__global__";

    private final IsolationScope scope;
    private final String value;

    private SandboxIsolationKey(IsolationScope scope, String value) {
        this.scope = scope;
        this.value = value;
    }

    /**
     * Resolves an isolation key from the given scope, runtime context, and agent ID.
     *
     * <p>Resolution rules:
     * <ul>
     *   <li>{@code SESSION} – requires a non-null {@code sessionId}; value =
     *       {@code sessionId}. Returns empty if absent.</li>
     *   <li>{@code USER} – uses {@code userId} when present. When userId is absent,
     *       falls back to {@code SESSION} scope using the sessionId. Returns empty
     *       only when both userId and sessionId are absent.</li>
     *   <li>{@code AGENT} – value = {@code agentId} (always present).</li>
     *   <li>{@code GLOBAL} – value = {@value #GLOBAL_VALUE} (always present).</li>
     *   <li>{@code null} scope – treated as {@code USER}.</li>
     * </ul>
     *
     * @param scope     the desired isolation scope; {@code null} defaults to {@code USER}
     * @param ctx       the runtime context for the current call; may be {@code null}
     * @param agentId   the agent name resolved at build time; must not be null
     * @return resolved key, or empty if the required context field is absent
     */
    public static Optional<SandboxIsolationKey> resolve(
            IsolationScope scope, RuntimeContext ctx, String agentId) {
        IsolationScope effective = scope != null ? scope : IsolationScope.USER;
        return switch (effective) {
            case SESSION -> {
                if (ctx == null || ctx.getSessionId() == null || ctx.getSessionId().isBlank()) {
                    yield Optional.empty();
                }
                yield Optional.of(
                        new SandboxIsolationKey(IsolationScope.SESSION, ctx.getSessionId()));
            }
            case USER -> {
                if (ctx != null && ctx.getUserId() != null && !ctx.getUserId().isBlank()) {
                    yield Optional.of(
                            new SandboxIsolationKey(IsolationScope.USER, ctx.getUserId()));
                }
                // Fall back to SESSION when userId is absent
                if (ctx != null && ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
                    log.debug(
                            "[sandbox] USER isolation scope requested but userId is absent"
                                    + " — falling back to SESSION scope using sessionId");
                    yield Optional.of(
                            new SandboxIsolationKey(IsolationScope.SESSION, ctx.getSessionId()));
                }
                log.warn(
                        "[sandbox] USER isolation scope requested but both userId and sessionId"
                                + " are absent — skipping state lookup; a fresh sandbox will be"
                                + " created");
                yield Optional.empty();
            }
            case AGENT ->
                    Optional.of(
                            new SandboxIsolationKey(
                                    IsolationScope.AGENT, Objects.requireNonNull(agentId)));
            case GLOBAL ->
                    Optional.of(new SandboxIsolationKey(IsolationScope.GLOBAL, GLOBAL_VALUE));
        };
    }

    /**
     * Returns the isolation scope.
     *
     * @return scope
     */
    public IsolationScope getScope() {
        return scope;
    }

    /**
     * Returns the discriminating value within the scope (e.g. session id, user id, agent name).
     *
     * @return value string
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns the key used for {@link SandboxExecutionGuard} acquisition.
     *
     * <p>The persisted slot for {@link IsolationScope#USER} scope is
     * {@code sandbox/user/{agentId}/{userId}} (see {@link SessionSandboxStateStore}), so the
     * execution guard has to discriminate by agent as well. Without the agent dimension a
     * declared subagent, spawned inside its parent's call window, would request the same guard
     * key the parent already holds and block forever — the parent cannot release the lease until
     * the subagent returns.
     *
     * <p>Other scopes are returned unchanged: {@link IsolationScope#SESSION} and
     * {@link IsolationScope#AGENT} already discriminate parent from child (a derived child
     * session id / a distinct agent id), and {@link IsolationScope#GLOBAL} shares a single
     * persisted slot by definition, where serialisation is required.
     *
     * @param agentId the agent id of the execution acquiring the sandbox; required for
     *     {@link IsolationScope#USER}
     * @return the guard key for {@code agentId}
     * @throws IllegalArgumentException if the scope is {@link IsolationScope#USER} and
     *     {@code agentId} is null or blank — returning {@code this} would hand back exactly the
     *     key that deadlocks nested calls
     */
    public SandboxIsolationKey forExecutionGuard(String agentId) {
        if (scope != IsolationScope.USER) {
            return this;
        }
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException(
                    "agentId is required for the USER-scope execution guard key; without it a"
                            + " nested call would acquire the same guard key its parent already"
                            + " holds and deadlock");
        }
        // Mirrors SessionSandboxStateStore#slotSessionId's USER composition so the guard and
        // the persisted slot stay in lockstep.
        return new SandboxIsolationKey(scope, agentId + "/" + value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SandboxIsolationKey that)) return false;
        return scope == that.scope && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, value);
    }

    @Override
    public String toString() {
        return "SandboxIsolationKey{scope=" + scope + ", value='" + value + "'}";
    }
}
