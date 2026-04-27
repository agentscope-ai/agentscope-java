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
     *   <li>{@code SESSION} – requires a non-null {@code sessionKey}; value =
     *       {@code sessionKey.toIdentifier()}. Returns empty if absent.</li>
     *   <li>{@code USER} – requires a non-blank {@code userId}; value = {@code userId}.
     *       Logs a warning and returns empty if absent.</li>
     *   <li>{@code AGENT} – value = {@code agentId} (always present).</li>
     *   <li>{@code GLOBAL} – value = {@value #GLOBAL_VALUE} (always present).</li>
     *   <li>{@code null} scope – treated as {@code SESSION}.</li>
     * </ul>
     *
     * @param scope     the desired isolation scope; {@code null} defaults to {@code SESSION}
     * @param ctx       the runtime context for the current call; may be {@code null}
     * @param agentId   the agent name resolved at build time; must not be null
     * @return resolved key, or empty if the required context field is absent
     */
    public static Optional<SandboxIsolationKey> resolve(
            IsolationScope scope, RuntimeContext ctx, String agentId) {
        IsolationScope effective = scope != null ? scope : IsolationScope.SESSION;
        return switch (effective) {
            case SESSION -> {
                if (ctx == null || ctx.getSessionKey() == null) {
                    yield Optional.empty();
                }
                yield Optional.of(
                        new SandboxIsolationKey(
                                IsolationScope.SESSION, ctx.getSessionKey().toIdentifier()));
            }
            case USER -> {
                if (ctx == null || ctx.getUserId() == null || ctx.getUserId().isBlank()) {
                    log.warn(
                            "[sandbox] USER isolation scope requested but userId is absent"
                                    + " — skipping state lookup; a fresh sandbox will be"
                                    + " created");
                    yield Optional.empty();
                }
                yield Optional.of(new SandboxIsolationKey(IsolationScope.USER, ctx.getUserId()));
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
