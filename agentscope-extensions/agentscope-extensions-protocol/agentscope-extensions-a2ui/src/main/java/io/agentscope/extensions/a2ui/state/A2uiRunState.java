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
package io.agentscope.extensions.a2ui.state;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.a2ui.envelope.A2uiConstants;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable A2UI state scoped to one agent run (one user turn, all tool calls inside it).
 *
 * <p>It is deliberately an <em>object held by</em> the {@link RuntimeContext} rather than a plain
 * value written into it: the framework derives a fresh context per tool call with a shallow copy
 * ({@code RuntimeContext.Builder#from}), so {@code rc.put(key, value)} on that copy is invisible to
 * the next call, while a reference copied into the new map still points at this instance. Storing
 * the holder once at run level therefore gives every tool call of the same run a shared view, and a
 * new run gets a new holder — which is what makes "the surface of this turn" and "already
 * delivered this turn" well defined (spec §7).
 */
public final class A2uiRunState {

    private final AtomicReference<String> surfaceId = new AtomicReference<>();
    private final AtomicReference<String> deliveredEnvelope = new AtomicReference<>();

    /** @return the run's holder, creating and installing it when this run has none yet */
    public static A2uiRunState install(RuntimeContext rc) {
        A2uiRunState existing = of(rc);
        if (existing != null) {
            return existing;
        }
        A2uiRunState created = new A2uiRunState();
        if (rc != null) {
            rc.put(A2uiConstants.RC_KEY_RUN_STATE, created);
        }
        return created;
    }

    /** @return the run's holder, or {@code null} when the caller runs without {@code A2uiMiddleware} */
    public static A2uiRunState of(RuntimeContext rc) {
        return rc == null ? null : rc.get(A2uiConstants.RC_KEY_RUN_STATE, A2uiRunState.class);
    }

    /** @return the surface this run already delivered, or {@code null} when it delivered none */
    public String surfaceId() {
        return surfaceId.get();
    }

    public void surfaceId(String surfaceId) {
        this.surfaceId.set(surfaceId);
    }

    /** @return the envelope JSON this run last delivered, or {@code null} */
    public String deliveredEnvelope() {
        return deliveredEnvelope.get();
    }

    /** Record what the run delivered, so a repeated request can be answered without rendering. */
    public void delivered(String surfaceId, String envelope) {
        this.surfaceId.set(surfaceId);
        this.deliveredEnvelope.set(envelope);
    }
}
