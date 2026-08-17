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
package io.agentscope.spring.boot.admin.dto;

import io.agentscope.core.state.PlanModeContextState;

/**
 * Wire view of {@link PlanModeContextState}, plus a server-side flag indicating whether the
 * {@code PlanModeMiddleware} was wired into the agent at build time.
 *
 * <p>{@code planMiddlewareEnabled=false} means toggling {@code planActive} only flips the
 * AgentState flag — the read-only enforcement chain is absent because the agent was built without
 * {@code Builder.enablePlanMode()}. Useful diagnostic when a write somehow slipped through during
 * "plan mode".
 */
public record PlanModeView(
        String sessionId,
        boolean planActive,
        String currentPlanFile,
        boolean planMiddlewareEnabled) {

    public static PlanModeView of(
            String sessionId, PlanModeContextState state, boolean middlewareEnabled) {
        if (state == null) {
            return new PlanModeView(sessionId, false, null, middlewareEnabled);
        }
        return new PlanModeView(
                sessionId, state.isPlanActive(), state.getCurrentPlanFile(), middlewareEnabled);
    }
}
