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

/**
 * Resolves the per-call sandbox binding key ({@code "userId/sessionId"}) used to slot
 * live {@link Sandbox} instances in a shared filesystem proxy.
 *
 * <p>Distinct from {@link SandboxIsolationKey}, which governs the coarser acquire / lease /
 * persist isolation granularity (scope-based). This key is the per-session handle under which
 * {@code SandboxLifecycleMiddleware} binds a sandbox for the duration of one call.
 */
public final class SandboxBindingKey {

    private static final String ANONYMOUS_USER = "__anon__";

    private SandboxBindingKey() {}

    /**
     * Resolves {@code "userId/sessionId"} from the given context.
     *
     * @param ctx runtime context; may be {@code null}
     * @return the binding key, or {@code null} when {@code sessionId} is absent (signalling
     *     that no sandbox should be bound for this call)
     */
    public static String resolve(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        String sid = ctx.getSessionId();
        if (sid == null || sid.isBlank()) {
            return null;
        }
        String uid = ctx.getUserId();
        return (uid == null || uid.isBlank() ? ANONYMOUS_USER : uid) + "/" + sid;
    }
}
